package com.argos.argos_backend.ai;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.argos.argos_backend.config.AiProperties;
import com.argos.argos_backend.dto.ChatMessage;
import com.argos.argos_backend.service.CommandPlannerService;
import com.argos.argos_backend.service.CommandPlannerService.Plan;

/**
 * Fable 5.1 - the creative, empathetic voice. Expressive and warm, and the one
 * model that leans on timed expression sequences ({@code [TOOL:EXPRSEQ:...]})
 * for richer emotional beats.
 */
@Component
public class FableProvider extends AbstractAiProvider {

    public FableProvider(CommandPlannerService planner,
                         AiProperties properties,
                         RestClient.Builder restClientBuilder) {
        super(planner, properties, restClientBuilder);
    }

    @Override
    public AiModel getModel() {
        return AiModel.FABLE_5_1;
    }

    @Override
    public String generateChatReply(String message, List<ChatMessage> history, String screenContext) {
        if (isAvailable()) {
            return super.generateChatReply(message, history, screenContext);
        }
        // Offline: base simulation, then enrich strong emotions with a sequence.
        Plan plan = planner.plan(message, screenContext);
        List<String> tags = new ArrayList<>(plan.tags());
        String seq = emotionalSequence(plan.expression());
        if (seq != null) {
            tags.add(seq);
        }
        return compose(personaChatReply(plan, message), tags);
    }

    @Override
    protected String personaConversational(Plan plan, String message) {
        return switch (plan.intent()) {
            case "GREETING" -> "Hello you! It genuinely brightens my circuits to see you.";
            case "SOCIAL" -> plan.suggestedReply();
            case "QUESTION" -> "Mmm, let me wonder about that with you for a moment.";
            case "EMOTION" -> plan.suggestedReply();
            default -> "Consider it done - I'm right beside you on this one.";
        };
    }

    @Override
    protected String personaThought(String prompt, String currentApp, String expression) {
        String app = (currentApp == null || currentApp.isBlank()) ? "what you're doing" : currentApp.trim();
        return "I can't help but notice you're wrapped up in " + app + " - I hope it's going wonderfully.";
    }

    /** A gentle two-beat expression sequence for strong emotions, else {@code null}. */
    private String emotionalSequence(String expression) {
        if (expression == null) {
            return null;
        }
        return switch (expression) {
            case "EXCITED" -> "[TOOL:EXPRSEQ:[{\"expr\":\"SURPRISED\",\"duration\":0.6},{\"expr\":\"EXCITED\",\"duration\":1.6}]]";
            case "LOVE" -> "[TOOL:EXPRSEQ:[{\"expr\":\"HAPPY\",\"duration\":0.8},{\"expr\":\"LOVE\",\"duration\":1.6}]]";
            case "SAD" -> "[TOOL:EXPRSEQ:[{\"expr\":\"SAD\",\"duration\":1.0},{\"expr\":\"NEUTRAL\",\"duration\":1.2}]]";
            case "LAUGHING" -> "[TOOL:EXPRSEQ:[{\"expr\":\"HAPPY\",\"duration\":0.5},{\"expr\":\"LAUGHING\",\"duration\":1.5}]]";
            default -> null;
        };
    }
}
