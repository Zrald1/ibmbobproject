package com.argos.argos_backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.argos.argos_backend.ai.AiModelRouter;
import com.argos.argos_backend.ai.PromptConstants;
import com.argos.argos_backend.dto.ThoughtRequest;
import com.argos.argos_backend.dto.ThoughtResponse;

@Service
public class ThoughtService {

    private static final Logger log = LoggerFactory.getLogger(ThoughtService.class);

    private final AiModelRouter aiModelRouter;

    public ThoughtService(AiModelRouter aiModelRouter) {
        this.aiModelRouter = aiModelRouter;
    }

    public ThoughtResponse generateThought(ThoughtRequest request) {
        String prompt = (request == null) ? "Argos companion active" : request.getEffectivePrompt();
        String requestedModel = (request == null) ? null : request.model();

        try {
            String rawThought = aiModelRouter.routeThought(prompt, requestedModel);
            String expression = PromptConstants.extractExpression(rawThought, "HAPPY");
            String gesture = PromptConstants.extractGesture(rawThought, "REST");
            String modelKey = aiModelRouter.resolveModel(requestedModel).getId();

            return new ThoughtResponse(rawThought, expression, gesture, modelKey);
        } catch (Exception e) {
            log.warn("Thought generation error: {}. Returning default resilient thought.", e.getMessage());
            return new ThoughtResponse(
                "Argos is observing your screen context attentively! [TOOL:EXPR:HAPPY]",
                "HAPPY",
                "REST",
                "local-fallback"
            );
        }
    }
}
