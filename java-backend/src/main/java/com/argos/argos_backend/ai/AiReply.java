package com.argos.argos_backend.ai;

import java.util.List;

/**
 * The outcome of a routed model call.
 *
 * @param text          the reply/thought text including tool tags
 * @param model         the wire id of the model that ultimately served the request
 * @param source        {@code ai} (live upstream), {@code simulation} (offline
 *                      provider) or {@code planner} (local deterministic fallback)
 * @param degraded      {@code true} when the requested/primary model could not be
 *                      used and the router cascaded to a fallback
 * @param attempted     the ordered model ids tried during cascade
 * @param failureReason the last upstream error message, or {@code null} when the
 *                      primary model served the request cleanly
 */
public record AiReply(
        String text,
        String model,
        String source,
        boolean degraded,
        List<String> attempted,
        String failureReason) {

    public static AiReply of(String text, String model, String source) {
        return new AiReply(text, model, source, false, List.of(model), null);
    }
}
