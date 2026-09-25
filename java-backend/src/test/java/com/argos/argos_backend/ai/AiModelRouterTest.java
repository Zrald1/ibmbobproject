package com.argos.argos_backend.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.argos.argos_backend.config.AiProperties;
import com.argos.argos_backend.service.CommandPlannerService;

/**
 * Unit tests for {@link AiModelRouter}: default resolution, per-request model
 * selection, offline simulation replies, availability and telemetry. The router
 * is built manually with fresh providers so no Spring context is required.
 */
class AiModelRouterTest {

    private final CommandPlannerService planner = new CommandPlannerService();
    private final AiProperties properties = new AiProperties();
    private final AiModelRouter router = new AiModelRouter(
            List.of(
                    new Gpt6AstraProvider(planner, properties, RestClient.builder()),
                    new GeminiFlashProvider(planner, properties, RestClient.builder()),
                    new FableProvider(planner, properties, RestClient.builder())),
            planner,
            properties);

    @Test
    void defaultsToGpt6Astra() {
        assertEquals(AiModel.GPT_6_ASTRA, router.defaultModel());
    }

    @Test
    void resolvesRequestedModelById() {
        assertEquals(AiModel.GEMINI_3_8_FLASH, router.resolve("gemini-3.8-flash"));
        assertEquals(AiModel.FABLE_5_1, router.resolve("fable-5.1"));
        assertEquals(AiModel.GPT_6_ASTRA, router.resolve("GPT-6 Astra"));
        // Unknown identifiers fall back to the configured default.
        assertEquals(AiModel.GPT_6_ASTRA, router.resolve("does-not-exist"));
        assertEquals(AiModel.GPT_6_ASTRA, router.resolve(null));
    }

    @Test
    void generatesChatReplyInSimulationMode() {
        AiReply reply = router.generateChat("hello there", List.of(), null, null);
        assertNotNull(reply.text());
        assertFalse(reply.text().isBlank());
        assertEquals("gpt-6-astra", reply.model());
        assertEquals("simulation", reply.source());
        assertFalse(reply.degraded());
        assertTrue(reply.text().contains("[TOOL:EXPR:"),
                "every reply must carry an expression tag, got: " + reply.text());
    }

    @Test
    void honoursRequestedModel() {
        AiReply reply = router.generateChat("open youtube", List.of(), null, "fable-5.1");
        assertEquals("fable-5.1", reply.model());
        assertFalse(reply.degraded());
        assertTrue(reply.text().contains("[TOOL:OPEN:com.google.android.youtube]"),
                "expected an OPEN tool tag, got: " + reply.text());
    }

    @Test
    void generatesThought() {
        AiReply reply = router.generateThought("user is watching a video", null, "YouTube", null);
        assertNotNull(reply.text());
        assertFalse(reply.text().isBlank());
        assertEquals("gpt-6-astra", reply.model());
    }

    @Test
    void reportsAllModelsAsSimulationWithoutKeys() {
        Map<String, String> availability = router.availability();
        assertEquals("simulation", availability.get("gpt-6-astra"));
        assertEquals("simulation", availability.get("gemini-3.8-flash"));
        assertEquals("simulation", availability.get("fable-5.1"));
    }

    @Test
    void tracksMetrics() {
        router.generateChat("tell me a joke", List.of(), null, null);
        Map<String, Object> metrics = router.metrics();
        assertTrue(((Number) metrics.get("totalRequests")).longValue() >= 1);
        assertNotNull(metrics.get("perModel"));
        assertTrue(metrics.get("perModel") instanceof Map<?, ?>);
    }
}
