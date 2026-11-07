package com.hsf.aichat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The sample documents load on first start, and a question finds the right one. */
@SpringBootTest(properties = {"app.sample-data=true",
        "spring.datasource.url=jdbc:h2:mem:sampledb;MODE=MSSQLServer;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SampleDataTests {

    @Autowired
    MockMvc mvc;

    @Test
    void sampleDocumentsAreLoadedAndSearchable() throws Exception {
        mvc.perform(get("/api/documents")).andExpect(jsonPath("$", hasSize(4)));

        String conv = mvc.perform(post("/api/conversations").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"RAG\"}"))
                .andReturn().getResponse().getContentAsString();
        String id = conv.replaceAll(".*\"id\":(\\d+).*", "$1");

        mvc.perform(post("/api/conversations/" + id + "/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"How much is a salted cream coffee size L at Green Leaf Café?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources[0].documentName").value("greenleaf-cafe-handbook.md"));

        mvc.perform(post("/api/conversations/" + id + "/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Lệ phí phúc khảo là bao nhiêu tiền một môn?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources[0].documentName").value("quy-che-thi-mau.md"));
    }
}
