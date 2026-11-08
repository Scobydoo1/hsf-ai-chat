package com.hsf.aichat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsf.aichat.entity.ProviderConfig;
import com.hsf.aichat.entity.ProviderType;
import com.hsf.aichat.repository.ProviderConfigRepository;
import com.hsf.aichat.service.ProviderHealth;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Automatic switching between AI platforms when one fails.
 * Two OpenAI-compatible providers point at a fake AI server: "Limited AI" (active) always answers 429,
 * "Backup AI" always works. Own H2 database, so nothing leaks into the other test classes.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:autoswitchdb;MODE=MSSQLServer;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AutoSwitchTests {

    private static final String LIMITED_CHAT = "/limited/chat/completions";

    static FakeAiServer ai;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    ProviderConfigRepository providers;

    @Autowired
    ProviderHealth health;

    long limitedId;
    long backupId;

    @BeforeAll
    static void startFakeAi() throws IOException {
        ai = new FakeAiServer();
    }

    @AfterAll
    static void stopFakeAi() {
        ai.stop();
    }

    @BeforeEach
    void limitedProviderIsActive() throws Exception {
        limitedId = addProvider("Limited AI", "limited");
        backupId = addProvider("Backup AI", "ok");
        activate(limitedId);
    }

    @AfterEach
    void putDemoBack() throws Exception {
        long demoId = providers.findAll().stream().filter(p -> p.getType() == ProviderType.DEMO)
                .findFirst().orElseThrow().getId();
        activate(demoId);
        for (ProviderConfig p : providers.findAll()) {
            if (p.getType() != ProviderType.DEMO) {
                mvc.perform(delete("/api/providers/" + p.getId())).andExpect(status().isNoContent());
            }
        }
        health.clearAll();
    }

    @Test
    void rateLimitedProviderHandsTheQuestionToTheOtherOne() throws Exception {
        JsonNode answer = send(newConversation(), "{\"content\":\"Hello\"}");

        assertThat(answer.get("provider").asText()).isEqualTo("Backup AI");
        assertThat(answer.get("content").asText()).isEqualTo("Fake answer");
        assertThat(answer.get("notice").asText())
                .isEqualTo("Limited AI hit its rate limit (HTTP 429), so Backup AI answered instead.");
        assertThat(answer.get("durationMs").asLong()).isGreaterThanOrEqualTo(0);

        // Limited AI now rests for the 30 s its Retry-After header asked for
        JsonNode limited = provider(limitedId);
        assertThat(limited.get("cooldownSeconds").asInt()).isBetween(1, 30);
        assertThat(limited.get("lastError").asText()).isEqualTo("Rate limit reached (HTTP 429)");
        JsonNode backup = provider(backupId);
        assertThat(backup.get("cooldownSeconds").asInt()).isZero();
        assertThat(backup.get("lastError").isNull()).isTrue();
    }

    @Test
    void restingProviderIsSkipped() throws Exception {
        long conversation = newConversation();
        send(conversation, "{\"content\":\"First question\"}"); // Limited AI answers 429 and starts resting
        int limitedCalls = ai.hits(LIMITED_CHAT);

        JsonNode second = send(conversation, "{\"content\":\"Second question\"}");

        assertThat(ai.hits(LIMITED_CHAT)).isEqualTo(limitedCalls); // not called again while resting
        assertThat(second.get("provider").asText()).isEqualTo("Backup AI");
        assertThat(second.get("notice").asText())
                .startsWith("Limited AI is resting after hitting its limit (about ")
                .endsWith(" s left), so Backup AI answered instead.");
    }

    @Test
    void withoutAutoFallbackTheErrorComesBackAndNothingIsSaved() throws Exception {
        long conversation = newConversation();

        postMessage(conversation, "{\"content\":\"Hello\",\"autoFallback\":false}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT"))
                .andExpect(jsonPath("$.provider").value("Limited AI"))
                .andExpect(jsonPath("$.error").value(containsString("HTTP 429")));

        // nothing was saved: no messages, and the chat kept its title
        mvc.perform(get("/api/conversations/" + conversation + "/messages")).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/conversations"))
                .andExpect(jsonPath("$[?(@.id == " + conversation + ")].title").value(hasItem("New chat")));
    }

    @Test
    void providerIdAnswersWithThatProvider() throws Exception {
        int limitedCalls = ai.hits(LIMITED_CHAT);

        JsonNode answer = send(newConversation(), "{\"content\":\"Hello\",\"providerId\":" + backupId + "}");

        assertThat(answer.get("provider").asText()).isEqualTo("Backup AI");
        assertThat(answer.get("model").asText()).isEqualTo("ok-model");
        assertThat(answer.get("notice").isNull()).isTrue();
        assertThat(ai.hits(LIMITED_CHAT)).isEqualTo(limitedCalls); // the active provider was not asked
    }

    @Test
    void demoIsNeverUsedAsABackup() throws Exception {
        mvc.perform(delete("/api/providers/" + backupId)).andExpect(status().isNoContent()); // Limited AI + Demo left

        postMessage(newConversation(), "{\"content\":\"Hello\"}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT"))
                .andExpect(jsonPath("$.provider").value("Limited AI"));
    }

    @Test
    void whenEveryProviderFailsTheErrorListsThemAll() throws Exception {
        mvc.perform(delete("/api/providers/" + backupId)).andExpect(status().isNoContent());
        long downId = addProvider("Down AI", "down");

        postMessage(newConversation(), "{\"content\":\"Hello\"}")
                .andExpect(status().isBadGateway())
                // code and provider describe the same failure: the first one, of the provider the user chose
                .andExpect(jsonPath("$.code").value("RATE_LIMIT"))
                .andExpect(jsonPath("$.provider").value("Limited AI"))
                .andExpect(jsonPath("$.error")
                        .value("Limited AI: rate limit reached (HTTP 429) · Down AI: not responding (HTTP 503)"));

        JsonNode down = provider(downId);
        assertThat(down.get("cooldownSeconds").asInt()).isBetween(1, 30);
        assertThat(down.get("lastError").asText()).isEqualTo("Not responding (HTTP 503)");
    }

    @Test
    void aSkippedRestingProviderStillNamesTheError() throws Exception {
        send(newConversation(), "{\"content\":\"Hello\"}"); // Limited AI starts resting, Backup AI answers
        mvc.perform(delete("/api/providers/" + backupId)).andExpect(status().isNoContent());
        addProvider("Down AI", "down");

        // Limited AI is skipped (resting) and Down AI fails: the error is still about Limited AI, the chosen one
        postMessage(newConversation(), "{\"content\":\"Hello again\"}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT"))
                .andExpect(jsonPath("$.provider").value("Limited AI"))
                .andExpect(jsonPath("$.error").value(startsWith("Limited AI: resting after hitting its limit (about ")))
                .andExpect(jsonPath("$.error").value(endsWith(" s left) · Down AI: not responding (HTTP 503)")));
    }

    @Test
    void askingARestingProviderByNameTriesItFirst() throws Exception {
        long conversation = newConversation();
        send(conversation, "{\"content\":\"First question\"}"); // Limited AI answers 429 and starts resting
        int limitedCalls = ai.hits(LIMITED_CHAT);

        // "Ask Limited AI": it is called again although it rests; Backup AI still steps in when it fails
        JsonNode answer = send(conversation, "{\"content\":\"Second question\",\"providerId\":" + limitedId + "}");
        assertThat(ai.hits(LIMITED_CHAT)).isEqualTo(limitedCalls + 1);
        assertThat(answer.get("provider").asText()).isEqualTo("Backup AI");
        assertThat(answer.get("notice").asText())
                .isEqualTo("Limited AI hit its rate limit (HTTP 429), so Backup AI answered instead.");

        // ...and without autoFallback its own error comes back
        postMessage(conversation, "{\"content\":\"Third question\",\"providerId\":" + limitedId + ",\"autoFallback\":false}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT"))
                .andExpect(jsonPath("$.provider").value("Limited AI"));
        assertThat(ai.hits(LIMITED_CHAT)).isEqualTo(limitedCalls + 2);
    }

    @Test
    void theHistorySentToTheAiStartsWithAQuestion() throws Exception {
        activate(backupId);
        long conversation = newConversation();
        for (int i = 1; i <= 6; i++) {
            send(conversation, "{\"content\":\"Question " + i + "\"}");
        }

        // before question 6 there were 10 saved messages; the newest 9 start with answer 1, which is left out
        JsonNode sent = ai.lastRequest("/ok/chat/completions").get("messages");
        assertThat(sent).hasSize(1 + 8 + 1); // system prompt + 4 questions with their answers + the new question
        assertThat(sent.get(0).get("role").asText()).isEqualTo("system");
        assertThat(sent.get(1).get("role").asText()).isEqualTo("user");
        assertThat(sent.get(1).get("content").asText()).isEqualTo("Question 2");
        assertThat(sent.get(9).get("content").asText()).isEqualTo("Question 6");
    }

    @Test
    void activatingAProviderEndsItsRest() throws Exception {
        send(newConversation(), "{\"content\":\"Hello\"}"); // Limited AI starts resting
        assertThat(provider(limitedId).get("cooldownSeconds").asInt()).isPositive();

        activate(limitedId); // the user picks it on purpose

        assertThat(provider(limitedId).get("cooldownSeconds").asInt()).isZero();
    }

    private long addProvider(String name, String path) throws Exception {
        String body = json.writeValueAsString(Map.of("name", name, "type", "OPENAI_COMPATIBLE",
                "baseUrl", ai.url(path), "model", path + "-model", "apiKey", "test-key"));
        String res = mvc.perform(post("/api/providers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asLong();
    }

    private void activate(long providerId) throws Exception {
        mvc.perform(post("/api/providers/" + providerId + "/activate")).andExpect(status().isOk());
    }

    private JsonNode provider(long providerId) throws Exception {
        String res = mvc.perform(get("/api/providers")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (JsonNode p : json.readTree(res)) {
            if (p.get("id").asLong() == providerId) {
                return p;
            }
        }
        throw new AssertionError("Provider " + providerId + " not in the list");
    }

    private long newConversation() throws Exception {
        String res = mvc.perform(post("/api/conversations")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asLong();
    }

    private ResultActions postMessage(long conversationId, String body) throws Exception {
        return mvc.perform(post("/api/conversations/" + conversationId + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Sends a message that must succeed and returns the answer */
    private JsonNode send(long conversationId, String body) throws Exception {
        String res = postMessage(conversationId, body).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(res);
    }
}
