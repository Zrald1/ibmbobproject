package com.argos.argos_backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatResponse(
    boolean success,
    String source,
    String reply,
    String response,
    String expression,
    @JsonProperty("hand_gesture")
    String handGesture,
    String model,
    ActionDto action,
    boolean retrySuggested
) {
    public ChatResponse(
        boolean success,
        String source,
        String text,
        ActionDto action,
        boolean retrySuggested
    ) {
        this(success, source, text, text, "HAPPY", "REST", "fallback", action, retrySuggested);
    }

    public ChatResponse(
        boolean success,
        String source,
        String text,
        String expression,
        String handGesture,
        String model,
        ActionDto action,
        boolean retrySuggested
    ) {
        this(success, source, text, text, expression, handGesture, model, action, retrySuggested);
    }
}
