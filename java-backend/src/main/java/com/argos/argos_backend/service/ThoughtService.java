package com.argos.argos_backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.argos.argos_backend.ai.AiModelRouter;
import com.argos.argos_backend.ai.AiReply;
import com.argos.argos_backend.ai.PromptConstants;
import com.argos.argos_backend.dto.ThoughtRequest;
import com.argos.argos_backend.dto.ThoughtResponse;

/**
 * Generates short, proactive "thought bubble" comments about what the user
 * seems to be doing, using the routed model with graceful degradation.
 */
@Service
public class ThoughtService {

    private static final Logger log = LoggerFactory.getLogger(ThoughtService.class);

    private final AiModelRouter router;
    private final CommandPlannerService planner;

    public ThoughtService(AiModelRouter router, CommandPlannerService planner) {
        this.router = router;
        this.planner = planner;
    }

    public ThoughtResponse generateThought(ThoughtRequest request) {
        String prompt = request == null ? "" : request.effectivePrompt();
        String screenContext = request == null ? null : request.screenContext();
        String currentApp = request == null ? null : request.currentApp();
        String model = request == null ? null : request.model();

        try {
            AiReply reply = router.generateThought(prompt, screenContext, currentApp, model);
            String text = reply.text();
            String expression = planner.extractExpression(text);
            String gesture = planner.extractGesture(text);
            return ThoughtResponse.of(text, expression, gesture, reply.model(), reply.source());
        } catch (Exception e) {
            log.error("Thought generation failed, using neutral fallback", e);
            String text = "Just here, watching over things. "
                    + PromptConstants.expr(PromptConstants.DEFAULT_EXPRESSION);
            return ThoughtResponse.of(text, PromptConstants.DEFAULT_EXPRESSION,
                    PromptConstants.DEFAULT_GESTURE, router.defaultModel().getId(), "fallback");
        }
    }
}
