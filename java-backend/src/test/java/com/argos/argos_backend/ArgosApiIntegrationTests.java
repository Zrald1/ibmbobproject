package com.argos.argos_backend;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class ArgosApiIntegrationTests {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    @DisplayName("GET /api/health returns ok status and metadata")
    void testHealthEndpoint() throws Exception {
        mockMvc.perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("ok")))
            .andExpect(jsonPath("$.version", is("1.0.0")))
            .andExpect(jsonPath("$.fallbackAvailable", is(true)))
            .andExpect(jsonPath("$.models", hasSize(3)));
    }

    @Test
    @DisplayName("GET /api/models returns available multi-model list")
    void testModelsEndpoint() throws Exception {
        mockMvc.perform(get("/api/models"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(3)))
            .andExpect(jsonPath("$[0].id", is("gpt-6-astra")))
            .andExpect(jsonPath("$[1].id", is("gemini-3.8-flash")))
            .andExpect(jsonPath("$[2].id", is("fable-5.1")));
    }

    @Test
    @DisplayName("POST /api/auth/device registers new device and returns token")
    void testDeviceAuth() throws Exception {
        String authBody = """
            {
                "device_id": "test-device-uuid-001",
                "device_name": "Pixel 8 Pro"
            }
            """;

        mockMvc.perform(post("/api/auth/device")
                .contentType(MediaType.APPLICATION_JSON)
                .content(authBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.device_id", is("test-device-uuid-001")))
            .andExpect(jsonPath("$.access_token", notNullValue()))
            .andExpect(jsonPath("$.token_type", is("bearer")))
            .andExpect(jsonPath("$.user.username", notNullValue()));

        // Subsequent authentication for same device
        mockMvc.perform(post("/api/auth/device")
                .contentType(MediaType.APPLICATION_JSON)
                .content(authBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.registered", is(false)));
    }

    @Test
    @DisplayName("POST /api/chat processes chat message and returns response with expression and gestures")
    void testChatEndpoint() throws Exception {
        String chatBody = """
            {
                "device_id": "test-device-uuid-001",
                "message": "Hello Argos!",
                "screen_context": "Home screen",
                "history": [
                    {"role": "user", "content": "Hi"},
                    {"role": "assistant", "content": "Hello!"}
                ],
                "model": "gemini-3.8-flash"
            }
            """;

        mockMvc.perform(post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(chatBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.reply", notNullValue()))
            .andExpect(jsonPath("$.response", notNullValue()))
            .andExpect(jsonPath("$.expression", notNullValue()))
            .andExpect(jsonPath("$.hand_gesture", notNullValue()))
            .andExpect(jsonPath("$.model", is("gemini-3.8-flash")));
    }

    @Test
    @DisplayName("POST /api/chat with X-Argos-Model header routes to requested model")
    void testChatWithHeaderModel() throws Exception {
        String chatBody = """
            {
                "message": "Please write a story note for me"
            }
            """;

        mockMvc.perform(post("/api/chat")
                .header("X-Argos-Model", "fable-5.1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(chatBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.model", is("fable-5.1")));
    }

    @Test
    @DisplayName("POST /api/chat validates empty message and returns standard error structure")
    void testChatValidationError() throws Exception {
        String emptyBody = """
            {
                "message": ""
            }
            """;

        mockMvc.perform(post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(emptyBody))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail", notNullValue()))
            .andExpect(jsonPath("$.message", notNullValue()))
            .andExpect(jsonPath("$.status", is(400)));
    }

    @Test
    @DisplayName("POST /api/thought generates contextual thought bubble")
    void testThoughtEndpoint() throws Exception {
        String thoughtBody = """
            {
                "screen_context": "User is viewing YouTube",
                "current_app": "com.google.android.youtube"
            }
            """;

        mockMvc.perform(post("/api/thought")
                .contentType(MediaType.APPLICATION_JSON)
                .content(thoughtBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.thought", notNullValue()))
            .andExpect(jsonPath("$.expression", notNullValue()));
    }

    @Test
    @DisplayName("POST /api/transcribe accepts audio file and returns transcription")
    void testTranscribeEndpoint() throws Exception {
        byte[] dummyAudio = new byte[1000];
        for (int i = 0; i < dummyAudio.length; i++) {
            dummyAudio[i] = (byte) (i % 127);
        }

        MockMultipartFile audioFile = new MockMultipartFile(
            "audio",
            "test_sample.wav",
            "audio/wav",
            dummyAudio
        );

        mockMvc.perform(multipart("/api/transcribe").file(audioFile))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.text", notNullValue()))
            .andExpect(jsonPath("$.confidence", is(0.98)));
    }

    @Test
    @DisplayName("POST /api/voice accepts audio file, transcribes, and responds with chat response")
    void testVoiceEndpoint() throws Exception {
        byte[] dummyAudio = new byte[1000];
        for (int i = 0; i < dummyAudio.length; i++) {
            dummyAudio[i] = (byte) (i % 127);
        }

        MockMultipartFile audioFile = new MockMultipartFile(
            "file",
            "voice_recording.wav",
            "audio/wav",
            dummyAudio
        );

        mockMvc.perform(multipart("/api/voice")
                .file(audioFile)
                .param("screen_context", "Browsing apps")
                .param("model", "gpt-6-astra"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.reply", notNullValue()))
            .andExpect(jsonPath("$.response", notNullValue()))
            .andExpect(jsonPath("$.expression", notNullValue()));
    }
}
