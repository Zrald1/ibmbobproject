package com.argos.argos_backend.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer tests for {@code POST /api/chat}. Verifies the Android-compatible
 * dual {@code response}/{@code reply} contract, expression tagging, per-request
 * model selection and validation error shape.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void chatReturnsBothResponseAndReply() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello there unique-1\",\"screen_context\":\"home screen\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.response").isNotEmpty())
                .andExpect(jsonPath("$.reply").isNotEmpty())
                .andExpect(jsonPath("$.expression").isNotEmpty())
                .andExpect(jsonPath("$.model").isNotEmpty());
    }

    @Test
    void chatAcceptsDesktopShapeWithDeviceIdAndModel() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"open youtube unique-2\","
                                + "\"screenContext\":\"desktop\","
                                + "\"deviceId\":\"test-device-chat\","
                                + "\"model\":\"fable-5.1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.model").value("fable-5.1"))
                .andExpect(jsonPath("$.response").value(
                        org.hamcrest.Matchers.containsString("[TOOL:OPEN:com.google.android.youtube]")));
    }

    @Test
    void chatHonoursModelHeader() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .header("X-Argos-Model", "gemini-3.8-flash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"what can you do unique-3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("gemini-3.8-flash"));
    }

    @Test
    void blankMessageIsRejectedWithDetail() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.status").value(400));
    }
}
