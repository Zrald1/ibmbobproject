package com.argos.argos_backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end API tests covering the discovery, health and device-auth
 * endpoints, verifying the full Spring context boots with the H2 default
 * database and zero API keys (offline simulation mode).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ArgosApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void modelsEndpointListsAllThreeModels() throws Exception {
        mockMvc.perform(get("/api/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.default_model").value("gpt-6-astra"))
                .andExpect(jsonPath("$.models.length()").value(3))
                .andExpect(jsonPath("$.models[0].id").isNotEmpty())
                .andExpect(jsonPath("$.models[0].status").value("simulation"))
                .andExpect(jsonPath("$.metrics").exists());
    }

    @Test
    void healthEndpointReportsUp() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database.status").value("UP"))
                .andExpect(jsonPath("$.ai.default_model").value("gpt-6-astra"))
                .andExpect(jsonPath("$.fallbackAvailable").value(true));
    }

    @Test
    void deviceAuthRegistersNewDevice() throws Exception {
        String deviceId = "itest-" + UUID.randomUUID();
        mockMvc.perform(post("/api/auth/device")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\",\"deviceName\":\"Lab Phone\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.token_type").value("bearer"))
                .andExpect(jsonPath("$.device_id").value(deviceId))
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value("Lab Phone"))
                .andExpect(jsonPath("$.isNewDevice").value(true))
                // Legacy camelCase fields remain for older clients.
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.deviceId").value(deviceId));
    }

    @Test
    void deviceAuthRejectsBlankDeviceId() throws Exception {
        mockMvc.perform(post("/api/auth/device")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.status").value(400));
    }
}
