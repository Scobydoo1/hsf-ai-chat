package com.hsf.aichat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Choosing which documents to search (useDocuments / documentIds), with the offline Demo provider.
 * Own H2 database with three small documents.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:scopedb;MODE=MSSQLServer;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentScopeTests {

    private static final String COFFEE = "How much is a salted cream coffee?";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    long cafeId;
    long libraryId;
    long examId;

    @BeforeAll
    void addDocuments() throws Exception {
        cafeId = addDocument("cafe.txt", "Green Leaf Cafe menu. A salted cream coffee, size L, costs 45000 VND.");
        libraryId = addDocument("library.txt", "Students may borrow up to five library books for fourteen days.");
        examId = addDocument("exam.txt", "The fee for re-checking an exam is 50000 VND per subject.");
    }

    @Test
    void onlyTheChosenDocumentsAreSearched() throws Exception {
        JsonNode answer = send(newConversation("CHAT"),
                "{\"content\":\"" + COFFEE + "\",\"useDocuments\":true,\"documentIds\":[" + libraryId + "," + examId + "]}");

        assertThat(answer.get("documentsSearched").asInt()).isEqualTo(2);
        assertThat(answer.get("sources")).isNotEmpty();
        for (JsonNode source : answer.get("sources")) {
            assertThat(source.get("documentId").asLong()).isIn(libraryId, examId); // never the café, though it matches best
        }
    }

    @Test
    void noChosenDocumentsMeansAllDocuments() throws Exception {
        long conversation = newConversation("CHAT");
        for (String ids : new String[]{"", ",\"documentIds\":null", ",\"documentIds\":[]"}) {
            JsonNode answer = send(conversation, "{\"content\":\"" + COFFEE + "\",\"useDocuments\":true" + ids + "}");

            assertThat(answer.get("documentsSearched").asInt()).isEqualTo(3);
            JsonNode best = answer.get("sources").get(0);
            assertThat(best.get("documentId").asLong()).isEqualTo(cafeId);
            assertThat(best.get("documentName").asText()).isEqualTo("cafe.txt");
            assertThat(best.get("method").asText()).isEqualTo("vector");
        }
    }

    @Test
    void useDocumentsFalseIsAPlainChat() throws Exception {
        // a RAG conversation, and ids sent anyway: useDocuments=false still wins
        JsonNode answer = send(newConversation("RAG"),
                "{\"content\":\"" + COFFEE + "\",\"useDocuments\":false,\"documentIds\":[" + cafeId + "]}");

        assertThat(answer.get("sources")).isEmpty();
        assertThat(answer.get("documentsSearched").isNull()).isTrue();
        assertThat(answer.get("notice").isNull()).isTrue();
    }

    @Test
    void olderClientsFollowTheConversationMode() throws Exception {
        // no useDocuments field: a RAG conversation searches every document, a plain chat none
        JsonNode rag = send(newConversation("RAG"), "{\"content\":\"" + COFFEE + "\"}");
        assertThat(rag.get("documentsSearched").asInt()).isEqualTo(3);
        assertThat(rag.get("sources").get(0).get("documentId").asLong()).isEqualTo(cafeId);

        JsonNode chat = send(newConversation("CHAT"), "{\"content\":\"" + COFFEE + "\"}");
        assertThat(chat.get("documentsSearched").isNull()).isTrue();
        assertThat(chat.get("sources")).isEmpty();
    }

    @Test
    void savedMessagesKeepTheNewFields() throws Exception {
        long conversation = newConversation("CHAT");
        send(conversation, "{\"content\":\"" + COFFEE + "\",\"useDocuments\":true,\"documentIds\":[" + cafeId + "]}");

        String res = mvc.perform(get("/api/conversations/" + conversation + "/messages"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode messages = json.readTree(res);
        assertThat(messages).hasSize(2);

        JsonNode question = messages.get(0);
        assertThat(question.get("role").asText()).isEqualTo("user");
        assertThat(question.get("durationMs").isNull()).isTrue();
        assertThat(question.get("documentsSearched").isNull()).isTrue();

        JsonNode reply = messages.get(1);
        assertThat(reply.get("role").asText()).isEqualTo("assistant");
        assertThat(reply.get("durationMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(reply.get("documentsSearched").asInt()).isEqualTo(1);
        assertThat(reply.get("notice").isNull()).isTrue();
        assertThat(reply.get("sources").get(0).get("documentId").asLong()).isEqualTo(cafeId);
    }

    private long addDocument(String name, String content) throws Exception {
        String body = json.writeValueAsString(Map.of("name", name, "content", content));
        String res = mvc.perform(post("/api/documents/text").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("document").get("id").asLong();
    }

    private long newConversation(String mode) throws Exception {
        String res = mvc.perform(post("/api/conversations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"" + mode + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asLong();
    }

    private JsonNode send(long conversationId, String body) throws Exception {
        String res = mvc.perform(post("/api/conversations/" + conversationId + "/messages")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(res);
    }
}
