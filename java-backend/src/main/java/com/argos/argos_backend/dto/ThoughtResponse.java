package com.argos.argos_backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Proactive thought reply. Mirrors the Python contract ({@code thought}) and
 * adds the parsed expression/gesture plus the serving model.
 */
public record ThoughtResponse(
        String thought,
        String expression,
        @JsonProperty("hand_gesture") String handGesture,
        String model,
        boolean success,
        String source
) {

    public static ThoughtResponse of(String thought, String expression, String handGesture,
                                     String model, String source) {
        return new ThoughtResponse(thought, expression, handGesture, model, true, source);
    }
}
