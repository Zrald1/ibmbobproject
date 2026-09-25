package com.argos.argos_backend.ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.argos.argos_backend.config.AiProperties;
import com.argos.argos_backend.dto.ChatMessage;
import com.argos.argos_backend.exception.ExternalServiceException;
import com.argos.argos_backend.service.CommandPlannerService;
import com.argos.argos_backend.service.CommandPlannerService.Plan;

/**
 * Shared behaviour for the three Argos model providers.
 *
 * <p>When an API key is configured the provider performs a live
 * OpenAI-compatible {@code /chat/completions} call; any failure is surfaced as
 * an {@link ExternalServiceException} so {@link AiModelRouter} can cascade to
 * the next model. When no key is present the provider runs a deterministic
 * offline simulation built on {@link CommandPlannerService}, which guarantees
 * protocol-valid tool tags with zero external dependencies.</p>
 */
public abstract class AbstractAiProvider implements AiModelProvider {

    private static final Logger log = LoggerFactory.getLogger(AbstractAiProvider.class);

    protected final CommandPlannerService planner;
    protected final AiProperties properties;
    protected final RestClient restClient;

    protected AbstractAiProvider(CommandPlannerService planner,
                                 AiProperties properties,
                                 RestClient.Builder restClientBuilder) {
        this.planner = planner;
        this.properties = properties;
        Duration timeout = Duration.ofMillis(Math.max(1000, properties.getRequestTimeoutMs()));
        this.restClient = restClientBuilder.clone().build();
        // timeout is advisory; connect/read defaults are applied by the platform client
        log.debug("{} provider initialised (timeout={}ms)", getModel().getId(), timeout.toMillis());
    }

    @Override
    public boolean isAvailable() {
        return properties.providerFor(getModel().getId()).hasKey();
    }

    @Override
    public String generateChatReply(String message, List<ChatMessage> history, String screenContext) {
        AiProperties.Provider cfg = properties.providerFor(getModel().getId());
        if (cfg.hasKey()) {
            try {
                String live = liveCompletion(cfg, PromptConstants.SYSTEM_PROMPT, history, message, screenContext);
                if (live != null && !live.isBlank()) {
                    return ensureExpressionTag(live);
                }
            } catch (ExternalServiceException e) {
                throw e; // let the router cascade
            } catch (Exception e) {
                throw new ExternalServiceException(getModel().getId() + " upstream call failed: " + e.getMessage(), e);
            }
        }
        // Offline / demo simulation.
        Plan plan = planner.plan(message, screenContext);
        return compose(personaChatReply(plan, message), plan.tags());
    }

    @Override
    public String generateThought(String prompt, String screenContext, String currentApp) {
        AiProperties.Provider cfg = properties.providerFor(getModel().getId());
        if (cfg.hasKey()) {
            try {
                String live = liveCompletion(cfg, PromptConstants.THOUGHT_SYSTEM_PROMPT, List.of(),
                        thoughtPrompt(prompt, screenContext, currentApp), screenContext);
                if (live != null && !live.isBlank()) {
                    return ensureExpressionTag(live);
                }
            } catch (ExternalServiceException e) {
                throw e;
            } catch (Exception e) {
                throw new ExternalServiceException(getModel().getId() + " upstream thought failed: " + e.getMessage(), e);
            }
        }
        String expression = thoughtExpression(prompt, currentApp);
        return compose(personaThought(prompt, currentApp, expression),
                List.of(PromptConstants.expr(expression)));
    }

    // ------------------------------------------------------------------
    // Persona hooks - each model restyles conversational replies.
    // ------------------------------------------------------------------

    /**
     * Persona-flavoured, tag-free conversational text. Action/file intents keep
     * the planner's accurate wording; only small-talk is restyled.
     */
    protected String personaChatReply(Plan plan, String message) {
        return switch (plan.intent()) {
            case "GREETING", "SOCIAL", "QUESTION", "EMOTION", "CHAT" -> personaConversational(plan, message);
            default -> plan.suggestedReply();
        };
    }

    protected abstract String personaConversational(Plan plan, String message);

    protected abstract String personaThought(String prompt, String currentApp, String expression);

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    protected String compose(String text, List<String> tags) {
        String base = text == null ? "" : text.trim();
        if (tags == null || tags.isEmpty()) {
            return base;
        }
        return base + " " + String.join(" ", tags);
    }

    /** Guarantee the reply carries at least one EXPR tag so the robot reacts. */
    protected String ensureExpressionTag(String reply) {
        if (reply == null) {
            return PromptConstants.expr(PromptConstants.DEFAULT_EXPRESSION);
        }
        String upper = reply.toUpperCase(Locale.ROOT);
        if (upper.contains("[TOOL:EXPR:") || upper.contains("[TOOL:EXPRESSION:")) {
            return reply;
        }
        return reply.trim() + " " + PromptConstants.expr(PromptConstants.DEFAULT_EXPRESSION);
    }

    protected String thoughtPrompt(String prompt, String screenContext, String currentApp) {
        StringBuilder sb = new StringBuilder();
        if (currentApp != null && !currentApp.isBlank()) {
            sb.append("Current app: ").append(currentApp.trim()).append(". ");
        }
        if (prompt != null && !prompt.isBlank()) {
            sb.append(prompt.trim());
        } else if (screenContext != null && !screenContext.isBlank()) {
            sb.append("Screen context: ").append(screenContext.trim());
        }
        return sb.toString();
    }

    protected String thoughtExpression(String prompt, String currentApp) {
        String p = ((prompt == null ? "" : prompt) + " " + (currentApp == null ? "" : currentApp))
                .toLowerCase(Locale.ROOT);
        if (p.contains("youtube") || p.contains("video") || p.contains("music") || p.contains("spotify")) {
            return "HAPPY";
        }
        if (p.contains("error") || p.contains("crash") || p.contains("failed")) {
            return "CONFUSED";
        }
        if (p.contains("late") || p.contains("tired") || p.contains("night")) {
            return "SLEEPING";
        }
        if (p.contains("search") || p.contains("reading") || p.contains("study")) {
            return "THINKING";
        }
        return "THINKING";
    }

    @SuppressWarnings("unchecked")
    private String liveCompletion(AiProperties.Provider cfg, String systemPrompt,
                                  List<ChatMessage> history, String message, String screenContext) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        if (history != null) {
            for (ChatMessage m : history) {
                if (m != null && m.role() != null && m.content() != null) {
                    messages.add(Map.of("role", m.role(), "content", m.content()));
                }
            }
        }
        if (screenContext != null && !screenContext.isBlank()) {
            messages.add(Map.of("role", "system", "content", "Screen context: " + screenContext));
        }
        messages.add(Map.of("role", "user", "content", message == null ? "" : message));

        String model = (cfg.getModel() == null || cfg.getModel().isBlank())
                ? getModel().getId() : cfg.getModel();
        Map<String, Object> body = Map.of("model", model, "messages", messages);

        String base = cfg.getApiBase() == null || cfg.getApiBase().isBlank()
                ? "https://api.openai.com/v1" : cfg.getApiBase().replaceAll("/+$", "");

        Map<String, Object> response = restClient.post()
                .uri(base + "/chat/completions")
                .header("Authorization", "Bearer " + cfg.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        if (response == null) {
            throw new ExternalServiceException(getModel().getId() + " returned an empty completion");
        }
        Object choices = response.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            Object msg = first.get("message");
            if (msg instanceof Map<?, ?> msgMap) {
                Object content = msgMap.get("content");
                if (content != null) {
                    return content.toString();
                }
            }
        }
        throw new ExternalServiceException(getModel().getId() + " returned an unexpected completion shape");
    }
}
