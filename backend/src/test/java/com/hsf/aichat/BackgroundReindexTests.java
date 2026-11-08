package com.hsf.aichat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsf.aichat.entity.ProviderConfig;
import com.hsf.aichat.entity.ProviderType;
import com.hsf.aichat.repository.ProviderConfigRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * When the embedding provider changes, documents embedded with the old model are re-indexed on a background
 * thread; while that is not possible (the embedding service is rate-limited), the search uses keywords.
 * The Demo provider stays active for chat; the fake AI server plays the new embedding service.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:reindexdb;MODE=MSSQLServer;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackgroundReindexTests {

    private static final String FLAKY_EMBEDDINGS = "/flaky/embeddings";
    private static final String SLOW_EMBEDDINGS = "/slow/embeddings";
    private static final String QUESTION = "{\"content\":\"How much is a salted cream coffee?\",\"useDocuments\":true}";
    private static final String EMBEDDING_NOTE = "The embedding service is busy, so the document search used keywords.";

    static FakeAiServer ai;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    ProviderConfigRepository providers;

    @BeforeAll
    static void startFakeAi() throws IOException {
        ai = new FakeAiServer();
    }

    @AfterAll
    static void stopFakeAi() {
        ai.stop();
    }

    @Test
    void newEmbeddingProviderReindexesTheDocumentsInTheBackground() throws Exception {
        addDocument("cafe.txt", "Green Leaf Cafe menu. A salted cream coffee, size L, costs 45000 VND.");
        addDocument("library.txt", "Students may borrow up to five library books for fourteen days.");
        assertThat(searchModes()).containsExactly("vector"); // embedded by the Demo provider, today's embedder
        mvc.perform(get("/api/status")).andExpect(jsonPath("$.reindexing").isBoolean());

        // 1. the new embedding service is rate-limited: the re-index stops and the documents keep their old vectors
        ai.failEmbeddings(true);
        Map<String, Object> fake = Map.of("name", "Fake Embeddings", "type", "OPENAI_COMPATIBLE", "baseUrl", ai.url("flaky"),
                "model", "fake-chat", "embeddingModel", "fake-embed", "apiKey", "test-key", "useForEmbeddings", true);
        String res = mvc.perform(post("/api/providers").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fake)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long fakeId = json.readTree(res).get("id").asLong();

        waitUntil("the re-index tried and stopped", () -> ai.hits(FLAKY_EMBEDDINGS) >= 1 && !reindexing());
        assertThat(searchModes()).containsExactly("outdated");
        assertThat(embeddingModels()).containsExactly("DEMO:hashing-512");
        // outdated vectors cannot be compared with the new model: keyword search, without calling the embedder
        int embedCalls = ai.hits(FLAKY_EMBEDDINGS);
        assertThat(firstSourceMethod(ask())).isEqualTo("keyword");
        assertThat(ai.hits(FLAKY_EMBEDDINGS)).isEqualTo(embedCalls);

        // 2. the service works again: saving the provider again starts a new run, now it re-indexes everything
        ai.failEmbeddings(false);
        mvc.perform(put("/api/providers/" + fakeId).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fake)))
                .andExpect(status().isOk());
        waitUntil("every document uses the new model",
                () -> searchModes().equals(Set.of("vector")) && embeddingModels().equals(Set.of("OPENAI_COMPATIBLE:fake-embed")));
        assertThat(firstSourceMethod(ask())).isEqualTo("vector");

        // 3. embedding the question fails: the answer still comes, from a keyword search, and says so
        ai.failEmbeddings(true);
        JsonNode answer = ask();
        assertThat(firstSourceMethod(answer)).isEqualTo("keyword");
        assertThat(answer.get("notice").asText()).isEqualTo(EMBEDDING_NOTE);
        // only the embeddings rest: the chat of the same provider is not put on cooldown
        assertThat(providerField(fakeId, "cooldownSeconds")).isEqualTo("0");

        // 4. while the embeddings rest, the next question does not even try (no slow call that would fail)
        embedCalls = ai.hits(FLAKY_EMBEDDINGS);
        JsonNode next = ask();
        assertThat(ai.hits(FLAKY_EMBEDDINGS)).isEqualTo(embedCalls);
        assertThat(firstSourceMethod(next)).isEqualTo("keyword");
        assertThat(next.get("notice").asText()).isEqualTo(EMBEDDING_NOTE);

        putDemoBackForEmbeddings(fakeId);
    }

    @Test
    void reindexAllIsRefusedWhileTheBackgroundRunIsBusy() throws Exception {
        addDocument("menu.txt", "Pho costs 35000 VND. Banh mi costs 20000 VND.");
        ai.holdEmbeddings(true); // the new embedding service answers only when the test lets it
        Map<String, Object> slow = Map.of("name", "Slow Embeddings", "type", "OPENAI_COMPATIBLE", "baseUrl", ai.url("slow"),
                "model", "slow-chat", "embeddingModel", "slow-embed", "apiKey", "test-key", "useForEmbeddings", true);
        String res = mvc.perform(post("/api/providers").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(slow)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long slowId = json.readTree(res).get("id").asLong();
        try {
            waitUntil("the background run is busy", () -> reindexing() && ai.hits(SLOW_EMBEDDINGS) == 1);

            // "Re-index all" now would embed the same chunks a second time: refused
            mvc.perform(post("/api/documents/reindex"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value(
                            "The document search is already being updated in the background. Try again in a minute."));
            assertThat(ai.hits(SLOW_EMBEDDINGS)).isEqualTo(1);
        } finally {
            ai.holdEmbeddings(false);
        }
        waitUntil("the background run finished", () -> !reindexing());
        putDemoBackForEmbeddings(slowId);
    }

    /** Makes the Demo provider the embedding provider again, deletes the test provider, waits for the re-index */
    private void putDemoBackForEmbeddings(long testProviderId) throws Exception {
        ProviderConfig demo = providers.findAll().stream().filter(p -> p.getType() == ProviderType.DEMO)
                .findFirst().orElseThrow();
        Map<String, Object> demoBody = Map.of("name", demo.getName(), "type", "DEMO", "model", demo.getModel(),
                "embeddingModel", demo.getEmbeddingModel(), "useForEmbeddings", true);
        mvc.perform(put("/api/providers/" + demo.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(demoBody)))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/providers/" + testProviderId)).andExpect(status().isNoContent());
        waitUntil("the documents are back on the Demo model",
                () -> embeddingModels().equals(Set.of("DEMO:hashing-512")) && !reindexing());
    }

    private void addDocument(String name, String content) throws Exception {
        mvc.perform(post("/api/documents/text").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", name, "content", content))))
                .andExpect(status().isCreated());
    }

    private JsonNode ask() throws Exception {
        String conv = mvc.perform(post("/api/conversations")).andReturn().getResponse().getContentAsString();
        long id = json.readTree(conv).get("id").asLong();
        String res = mvc.perform(post("/api/conversations/" + id + "/messages")
                        .contentType(MediaType.APPLICATION_JSON).content(QUESTION))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(res);
    }

    private static String firstSourceMethod(JsonNode answer) {
        return answer.get("sources").get(0).get("method").asText();
    }

    private Set<String> searchModes() throws Exception {
        return documentField("searchMode");
    }

    private Set<String> embeddingModels() throws Exception {
        return documentField("embeddingModel");
    }

    /** The different values of one field over all documents */
    private Set<String> documentField(String field) throws Exception {
        String res = mvc.perform(get("/api/documents")).andReturn().getResponse().getContentAsString();
        Set<String> values = new TreeSet<>();
        for (JsonNode doc : json.readTree(res)) {
            values.add(doc.get(field).asText());
        }
        return values;
    }

    /** One field of one provider in GET /api/providers, as text */
    private String providerField(long providerId, String field) throws Exception {
        String res = mvc.perform(get("/api/providers")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (JsonNode p : json.readTree(res)) {
            if (p.get("id").asLong() == providerId) {
                return p.get(field).asText();
            }
        }
        throw new AssertionError("Provider " + providerId + " not in the list");
    }

    private boolean reindexing() throws Exception {
        String res = mvc.perform(get("/api/status")).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("reindexing").asBoolean();
    }

    /** The re-index runs on a background thread: check every 100 ms, for up to 10 s */
    private static void waitUntil(String what, Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.call()) {
            assertThat(System.currentTimeMillis()).as("waiting until " + what).isLessThan(deadline);
            Thread.sleep(100);
        }
    }
}
