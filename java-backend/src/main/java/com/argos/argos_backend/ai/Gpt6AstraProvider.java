package com.argos.argos_backend.ai;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.argos.argos_backend.config.AiProperties;
import com.argos.argos_backend.service.CommandPlannerService;
import com.argos.argos_backend.service.CommandPlannerService.Plan;

/**
 * GPT-6 Astra - the high-reasoning, executive-planning voice. Concise,
 * decisive, and focused on getting the task done.
 */
@Component
public class Gpt6AstraProvider extends AbstractAiProvider {

    public Gpt6AstraProvider(CommandPlannerService planner,
                             AiProperties properties,
                             RestClient.Builder restClientBuilder) {
        super(planner, properties, restClientBuilder);
    }

    @Override
    public AiModel getModel() {
        return AiModel.GPT_6_ASTRA;
    }

    @Override
    protected String personaConversational(Plan plan, String message) {
        return switch (plan.intent()) {
            case "GREETING" -> "Hello. Argos online and ready - what's the objective?";
            case "SOCIAL" -> plan.suggestedReply();
            case "QUESTION" -> "Let me reason through that. Here's my assessment.";
            case "EMOTION" -> plan.suggestedReply();
            default -> "Understood. I've analysed the request and here's my plan.";
        };
    }

    @Override
    protected String personaThought(String prompt, String currentApp, String expression) {
        String app = (currentApp == null || currentApp.isBlank()) ? "your current task" : currentApp.trim();
        return "Noting your focus on " + app + " - standing by to assist.";
    }
}
