package com.argos.argos_backend.ai;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.argos.argos_backend.config.AiProperties;
import com.argos.argos_backend.service.CommandPlannerService;
import com.argos.argos_backend.service.CommandPlannerService.Plan;

/**
 * Gemini 3.8 Flash - the low-latency, reactive voice. Snappy, warm, and quick
 * to acknowledge what's on screen.
 */
@Component
public class GeminiFlashProvider extends AbstractAiProvider {

    public GeminiFlashProvider(CommandPlannerService planner,
                               AiProperties properties,
                               RestClient.Builder restClientBuilder) {
        super(planner, properties, restClientBuilder);
    }

    @Override
    public AiModel getModel() {
        return AiModel.GEMINI_3_8_FLASH;
    }

    @Override
    protected String personaConversational(Plan plan, String message) {
        return switch (plan.intent()) {
            case "GREETING" -> "Hey! Quick and ready - what do you need?";
            case "SOCIAL" -> plan.suggestedReply();
            case "QUESTION" -> "Ooh, let me grab that for you real quick.";
            case "EMOTION" -> plan.suggestedReply();
            default -> "On it - here's what I've got.";
        };
    }

    @Override
    protected String personaThought(String prompt, String currentApp, String expression) {
        String app = (currentApp == null || currentApp.isBlank()) ? "this" : currentApp.trim();
        return "Looks like you're deep into " + app + " - nice!";
    }
}
