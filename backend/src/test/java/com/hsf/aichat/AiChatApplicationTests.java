package com.hsf.aichat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsf.aichat.entity.ProviderConfig;
import com.hsf.aichat.repository.ProviderConfigRepository;
import com.hsf.aichat.service.TextChunker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiChatApplicationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    ProviderConfigRepository providers;

    @Test
    void statusShowsDemoProviderOnFirstRun() throws Exception {
        mvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.passwordRequired").value(false))
                .andExpect(jsonPath("$.activeProvider").value(containsString("Demo")));
    }

    @Test
    void chatModeAnswersWithActiveProvider() throws Exception {
        long id = createConversation("CHAT");
        mvc.perform(post("/api/conversations/" + id + "/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Xin chào, bạn là ai?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("assistant"))
                .andExpect(jsonPath("$.content").value(containsString("Xin chào, bạn là ai?")))
                .andExpect(jsonPath("$.provider").value(containsString("Demo")));

        mvc.perform(get("/api/conversations/" + id + "/messages"))
                .andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/conversations"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].title").value(hasItem("Xin chào, bạn là ai?")));
    }

    @Test
    void ragModeFindsTheRightDocumentPassage() throws Exception {
        mvc.perform(post("/api/documents/text").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"policy.txt\",\"content\":\"Students may borrow up to five library books for fourteen days. Late returns cost 5000 VND per day.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.document.chunkCount").value(1))
                .andExpect(jsonPath("$.document.embeddingModel").value("DEMO:hashing-512"));
        MockMultipartFile file = new MockMultipartFile("file", "menu.md", "text/markdown",
                "# Canteen menu\nPho costs 35000 VND. Banh mi costs 20000 VND.".getBytes());
        mvc.perform(multipart("/api/documents").file(file)).andExpect(status().isCreated());

        long id = createConversation("RAG");
        mvc.perform(post("/api/conversations/" + id + "/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"How many library books can students borrow?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources[0].documentName").value("policy.txt"))
                .andExpect(jsonPath("$.sources[0].method").value("vector"))
                .andExpect(jsonPath("$.content").value(containsString("policy.txt")));
    }

    @Test
    void providerKeysAreMaskedAndEncrypted() throws Exception {
        String body = """
                {"name":"Test OpenAI","type":"OPENAI_COMPATIBLE","baseUrl":"https://api.openai.com/v1",
                 "model":"gpt-5-mini","embeddingModel":"text-embedding-3-small","apiKey":"sk-test-1234567890abcd"}""";
        String response = mvc.perform(post("/api/providers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maskedKey").value("sk-te••••abcd"))
                .andExpect(jsonPath("$.hasKey").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("sk-test-1234567890abcd");

        long id = json.readTree(response).get("id").asLong();
        ProviderConfig saved = providers.findById(id).orElseThrow();
        assertThat(saved.getApiKey()).isEqualTo("sk-test-1234567890abcd"); // decrypted when read

        // update without a key keeps the old one; activate switches the chat platform
        mvc.perform(put("/api/providers/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("\"apiKey\":\"sk-test-1234567890abcd\"", "\"apiKey\":\"\"")))
                .andExpect(jsonPath("$.hasKey").value(true));
        mvc.perform(post("/api/providers/" + id + "/activate")).andExpect(jsonPath("$.active").value(true));
        JsonNode list = json.readTree(mvc.perform(get("/api/providers")).andReturn().getResponse().getContentAsString());
        assertThat(list.findValues("active").stream().filter(JsonNode::asBoolean).count()).isEqualTo(1);

        // put the Demo provider back so the other tests keep working
        long demoId = providers.findAll().stream().filter(p -> p.getName().startsWith("Demo")).findFirst().orElseThrow().getId();
        mvc.perform(post("/api/providers/" + demoId + "/activate")).andExpect(status().isOk());
        mvc.perform(delete("/api/providers/" + id)).andExpect(status().isNoContent());
    }

    @Test
    void validationErrorsComeBackAsJson() throws Exception {
        long id = createConversation("CHAT");
        mvc.perform(post("/api/conversations/" + id + "/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("content")));
        mvc.perform(get("/api/conversations/999999/messages"))
                .andExpect(status().isNotFound());
    }

    @Test
    void chunkerSplitsLongTextWithOverlap() {
        String text = "Sentence number one is here. ".repeat(200);
        List<String> chunks = TextChunker.split(text);
        assertThat(chunks).hasSizeGreaterThan(5);
        assertThat(chunks).allMatch(c -> c.length() <= TextChunker.CHUNK_SIZE);
    }

    @Test
    void malformedJsonIsABadRequestNotAServerError() throws Exception {
        long id = createConversation("CHAT");
        mvc.perform(post("/api/conversations/" + id + "/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("The request body is not valid JSON"));
    }

    @Test
    void timesAreSentWithTheirTimeZoneOffset() throws Exception {
        // e.g. "2026-11-08T03:30:00.123Z": browsers then group chats by the USER's day, not the server's (UTC on Render)
        long id = createConversation("CHAT");
        mvc.perform(get("/api/conversations"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].updatedAt")
                        .value(hasItem(matchesPattern(".*T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?(Z|[+-]\\d{2}:\\d{2})$"))));
    }

    private long createConversation(String mode) throws Exception {
        String res = mvc.perform(post("/api/conversations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"" + mode + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asLong();
    }
}
