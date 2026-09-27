package com.argos.argos_backend.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer tests for {@code POST /api/thought}: the proactive "thought bubble"
 * endpoint accepting both the Python ({@code prompt}) and desktop
 * ({@code screen_context}/{@code current_app}/{@code model}) shapes.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ThoughtControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void generatesThoughtFromPrompt() throws Exception {
        mockMvc.perform(post("/api/thought")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"user is watching a video\",\"current_app\":\"YouTube\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.thought").isNotEmpty())
                .andExpect(jsonPath("$.expression").isNotEmpty())
                .andExpect(jsonPath("$.model").isNotEmpty());
    }

    @Test
    void honoursModelSelection() throws Exception {
        mockMvc.perform(post("/api/thought")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"thinking about code\",\"model\":\"gemini-3.8-flash\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("gemini-3.8-flash"))
                .andExpect(jsonPath("$.thought").isNotEmpty());
    }

    @Test
    void acceptsScreenContextShape() throws Exception {
        mockMvc.perform(post("/api/thought")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"screen_context\":\"a spreadsheet is open\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.thought").isNotEmpty());
    }
}
