package com.argos.argos_backend.service;

import org.springframework.stereotype.Service;

import com.argos.argos_backend.dto.ChatResponse;
import com.argos.argos_backend.service.CommandPlannerService.PlannedToolResult;

@Service
public class FallbackService {

    private final CommandPlannerService commandPlannerService;

    public FallbackService(CommandPlannerService commandPlannerService) {
        this.commandPlannerService = commandPlannerService;
    }

    public ChatResponse createFallbackResponse(String message, String screenContext) {
        PlannedToolResult plan = commandPlannerService.plan(message, screenContext);

        return new ChatResponse(
            true,
            "fallback",
            plan.reply(),
            plan.expression(),
            plan.handGesture(),
            "local-planner",
            plan.action(),
            true
        );
    }
}