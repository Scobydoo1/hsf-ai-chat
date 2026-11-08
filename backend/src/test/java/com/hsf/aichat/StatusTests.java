package com.hsf.aichat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsf.aichat.entity.ProviderConfig;
import com.hsf.aichat.entity.ProviderType;
import com.hsf.aichat.repository.ProviderConfigRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The requests a sleeping free server gets most: Render's health check and the front-end's keep-awake ping call
 * GET /api/status, so it must not touch the database (then the free Neon database can sleep).
 * Own H2 database, so nothing leaks into the other test classes.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:statusdb;MODE=MSSQLServer;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StatusTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    ProviderConfigRepository providers;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Test
    void statusNeverTouchesTheDatabase() throws Exception {
        // Hibernate counts every SQL statement it sends while statistics are on
        Statistics sql = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        sql.setStatisticsEnabled(true);
        try {
            sql.clear();
            for (int i = 0; i < 3; i++) {
                mvc.perform(get("/api/status")).andExpect(status().isOk());
            }
            assertThat(sql.getPrepareStatementCount()).isZero();

            mvc.perform(get("/api/providers")).andExpect(status().isOk()); // proof that the counter works
            assertThat(sql.getPrepareStatementCount()).isPositive();
        } finally {
            sql.setStatisticsEnabled(false);
        }
    }

    @Test
    void statusFollowsEveryProviderChange() throws Exception {
        ProviderConfig demo = providers.findAll().stream().filter(p -> p.getType() == ProviderType.DEMO)
                .findFirst().orElseThrow();
        assertStatus(demo.getName(), "demo", demo.getName());

        Map<String, Object> body = new HashMap<>(Map.of("name", "Status AI", "type", "DEMO", "model", "status-model",
                "embeddingModel", "hashing-512"));
        String res = mvc.perform(post("/api/providers").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(res).get("id").asLong();
        assertStatus(demo.getName(), "demo", demo.getName()); // added, but not active yet

        mvc.perform(post("/api/providers/" + id + "/activate")).andExpect(status().isOk());
        assertStatus("Status AI", "status-model", demo.getName());

        body.putAll(Map.of("name", "Renamed AI", "model", "renamed-model", "useForEmbeddings", true));
        mvc.perform(put("/api/providers/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk());
        assertStatus("Renamed AI", "renamed-model", "Renamed AI");

        mvc.perform(delete("/api/providers/" + id)).andExpect(status().isNoContent());
        assertStatus(demo.getName(), "demo", null); // the Demo takes over the chat; no embedding provider is left

        // put the Demo provider back as the embedding provider
        Map<String, Object> demoBody = Map.of("name", demo.getName(), "type", "DEMO", "model", demo.getModel(),
                "embeddingModel", demo.getEmbeddingModel(), "useForEmbeddings", true);
        mvc.perform(put("/api/providers/" + demo.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(demoBody)))
                .andExpect(status().isOk());
        assertStatus(demo.getName(), "demo", demo.getName());
    }

    @Test
    void unknownUrlsAreA404NotAServerError() throws Exception {
        // bots try random URLs on public servers: a short 404, no 500 with a stack trace in the log
        mvc.perform(get("/wp-login.php"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    private void assertStatus(String activeProvider, String activeModel, String embeddingProvider) throws Exception {
        String res = mvc.perform(get("/api/status")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode current = json.readTree(res);
        assertThat(current.get("activeProvider").asText(null)).isEqualTo(activeProvider);
        assertThat(current.get("activeModel").asText(null)).isEqualTo(activeModel);
        assertThat(current.get("embeddingProvider").asText(null)).isEqualTo(embeddingProvider);
    }
}
