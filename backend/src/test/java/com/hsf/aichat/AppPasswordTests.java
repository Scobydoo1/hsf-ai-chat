package com.hsf.aichat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.password=secret123")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppPasswordTests {

    @Autowired
    MockMvc mvc;

    @Test
    void apiNeedsThePasswordHeader() throws Exception {
        mvc.perform(get("/api/providers")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/providers").header("X-App-Password", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/providers").header("X-App-Password", "secret123")).andExpect(status().isOk());
    }

    @Test
    void statusStaysPublicAndSaysAPasswordIsNeeded() throws Exception {
        mvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordRequired").value(true));
    }

    @Test
    void corsPreflightFromLocalFrontEndIsAllowed() throws Exception {
        mvc.perform(options("/api/providers")
                        .header("Origin", "http://localhost:5500")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "X-App-Password"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5500"));
    }
}
