package com.argos.argos_backend.service;

import org.springframework.stereotype.Service;

import com.argos.argos_backend.dto.ChatResponse;
import com.argos.argos_backend.service.CommandPlannerService.Plan;

/**
 * Last-resort offline assistance. When no AI model (live or simulated) can
 * serve a request, this service still produces a helpful reply with valid
 * Argos tool tags by delegating to the deterministic {@link CommandPlannerService}.
 */
@Service
public class FallbackService {

    private final CommandPlannerService planner;

    public FallbackService(CommandPlannerService planner) {
        this.planner = planner;
    }

    /**
     * Build an offline chat response for the given message.
     *
     * @param message       the user's message
     * @param screenContext optional screen context
     * @return a {@link ChatResponse} carrying both {@code response} and {@code reply}
     */
    public ChatResponse createFallbackResponse(String message, String screenContext) {
        Plan plan = planner.plan(message, screenContext);
        return ChatResponse.ok(
                plan.replyWithTags(),
                plan.expression(),
                plan.gesture(),
                "local-planner",
                plan.action(),
                "fallback",
                true);
    }
}
