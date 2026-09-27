package com.argos.argos_backend.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer tests for the voice endpoints ({@code POST /api/transcribe} and
 * {@code POST /api/voice}). Without an STT key the backend runs in simulation
 * mode, so transcription returns an empty text and the voice pipeline replies
 * with a graceful "didn't catch that" turn.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VoiceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private MockMultipartFile audio() {
        return new MockMultipartFile("file", "clip.wav", "audio/wav",
                new byte[]{1, 2, 3, 4});
    }

    @Test
    void transcribeReturnsSimulationResult() throws Exception {
        mockMvc.perform(multipart("/api/transcribe").file(audio()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("simulation"))
                .andExpect(jsonPath("$.confidence").exists());
    }

    @Test
    void transcribeRejectsMissingAudio() throws Exception {
        mockMvc.perform(multipart("/api/transcribe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void voicePipelineAlwaysReplies() throws Exception {
        mockMvc.perform(multipart("/api/voice")
                        .file(audio())
                        .param("deviceId", "test-device-voice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.response").isNotEmpty())
                .andExpect(jsonPath("$.reply").isNotEmpty())
                .andExpect(jsonPath("$.expression").isNotEmpty());
    }
}
