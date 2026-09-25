package com.argos.argos_backend.ai;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.argos.argos_backend.config.AiProperties;
import com.argos.argos_backend.dto.ChatMessage;
import com.argos.argos_backend.service.CommandPlannerService;

/**
 * Central router that resolves the target model, invokes the matching provider
 * and cascades through the remaining models (and finally the local deterministic
 * command planner) whenever an upstream call fails.
 *
 * <p>Because every provider ships an offline simulation mode, the router always
 * returns a usable reply - it never propagates a hard failure to the caller.</p>
 */
@Service
public class AiModelRouter {

    private static final Logger log = LoggerFactory.getLogger(AiModelRouter.class);

    private final Map<AiModel, AiModelProvider> providers = new EnumMap<>(AiModel.class);
    private final CommandPlannerService planner;
    private final AiProperties properties;

    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong fallbackEvents = new AtomicLong();
    private final Map<AiModel, AtomicLong> usage = new EnumMap<>(AiModel.class);

    public AiModelRouter(List<AiModelProvider> providerBeans,
                         CommandPlannerService planner,
                         AiProperties properties) {
        this.planner = planner;
        this.properties = properties;
        for (AiModelProvider p : providerBeans) {
            providers.put(p.getModel(), p);
            usage.put(p.getModel(), new AtomicLong());
        }
        for (AiModel model : AiModel.values()) {
            usage.putIfAbsent(model, new AtomicLong());
        }
    }

    /** The default model id, resolved from configuration. */
    public AiModel defaultModel() {
        return AiModel.fromId(properties.getDefaultModel()).orElse(AiModel.GPT_6_ASTRA);
    }

    /** Resolve a client-requested model id, falling back to the default. */
    public AiModel resolve(String requestedModelId) {
        return AiModel.fromId(requestedModelId).orElseGet(this::defaultModel);
    }

    /** Ordered cascade: the requested model first, then the remaining models. */
    private List<AiModel> cascade(AiModel primary) {
        List<AiModel> order = new ArrayList<>();
        order.add(primary);
        for (AiModel m : AiModel.values()) {
            if (m != primary) {
                order.add(m);
            }
        }
        return order;
    }

    public AiReply generateChat(String message, List<ChatMessage> history,
                                String screenContext, String requestedModelId) {
        totalRequests.incrementAndGet();
        AiModel primary = resolve(requestedModelId);
        List<String> attempted = new ArrayList<>();
        String lastError = null;

        for (AiModel model : cascade(primary)) {
            AiModelProvider provider = providers.get(model);
            if (provider == null) {
                continue;
            }
            attempted.add(model.getId());
            try {
                String text = provider.generateChatReply(message, history, screenContext);
                if (text != null && !text.isBlank()) {
                    usage.get(model).incrementAndGet();
                    boolean degraded = model != primary;
                    if (degraded) {
                        fallbackEvents.incrementAndGet();
                    }
                    String source = provider.isAvailable() ? "ai" : "simulation";
                    return new AiReply(text.trim(), model.getId(), source, degraded,
                            List.copyOf(attempted), degraded ? lastError : null);
                }
                lastError = model.getId() + " returned a blank reply";
            } catch (Exception e) {
                lastError = model.getId() + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                log.warn("AI provider {} failed, cascading: {}", model.getId(), lastError);
            }
        }

        // Ultimate resilient fallback: the deterministic local command planner.
        fallbackEvents.incrementAndGet();
        String text = planner.plan(message, screenContext).replyWithTags();
        return new AiReply(text, primary.getId(), "planner", true,
                List.copyOf(attempted), lastError);
    }

    public AiReply generateThought(String prompt, String screenContext,
                                   String currentApp, String requestedModelId) {
        totalRequests.incrementAndGet();
        AiModel primary = resolve(requestedModelId);
        List<String> attempted = new ArrayList<>();
        String lastError = null;

        for (AiModel model : cascade(primary)) {
            AiModelProvider provider = providers.get(model);
            if (provider == null) {
                continue;
            }
            attempted.add(model.getId());
            try {
                String text = provider.generateThought(prompt, screenContext, currentApp);
                if (text != null && !text.isBlank()) {
                    usage.get(model).incrementAndGet();
                    boolean degraded = model != primary;
                    if (degraded) {
                        fallbackEvents.incrementAndGet();
                    }
                    String source = provider.isAvailable() ? "ai" : "simulation";
                    return new AiReply(text.trim(), model.getId(), source, degraded,
                            List.copyOf(attempted), degraded ? lastError : null);
                }
                lastError = model.getId() + " returned a blank thought";
            } catch (Exception e) {
                lastError = model.getId() + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                log.warn("AI provider {} thought failed, cascading: {}", model.getId(), lastError);
            }
        }

        fallbackEvents.incrementAndGet();
        String text = "Just here with you. " + PromptConstants.expr(PromptConstants.DEFAULT_EXPRESSION);
        return new AiReply(text, primary.getId(), "planner", true, List.copyOf(attempted), lastError);
    }

    /** Snapshot of router telemetry for {@code GET /api/models} and health. */
    public Map<String, Object> metrics() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("totalRequests", totalRequests.get());
        m.put("fallbackEvents", fallbackEvents.get());
        Map<String, Long> perModel = new java.util.LinkedHashMap<>();
        usage.forEach((model, counter) -> perModel.put(model.getId(), counter.get()));
        m.put("perModel", perModel);
        return m;
    }

    /** Availability of every model: {@code online} when a live key is present. */
    public Map<String, String> availability() {
        Map<String, String> status = new java.util.LinkedHashMap<>();
        for (AiModel model : AiModel.values()) {
            AiModelProvider provider = providers.get(model);
            boolean live = provider != null && provider.isAvailable();
            status.put(model.getId(), live ? "online" : "simulation");
        }
        return status;
    }

    public AiModelProvider providerFor(AiModel model) {
        return providers.get(model);
    }
}
