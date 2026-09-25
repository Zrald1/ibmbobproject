package com.argos.argos_backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Size;

/**
 * Proactive "thought bubble" request. Accepts the Python shape
 * ({@code prompt}) as well as the richer desktop shape
 * ({@code screen_context}, {@code current_app}, {@code model}).
 */
public record ThoughtRequest(

        @Size(max = 4000, message = "prompt must not exceed 4000 characters")
        String prompt,

        @JsonProperty("screen_context")
        @JsonAlias({"screenContext"})
        @Size(max = 6000, message = "screen_context must not exceed 6000 characters")
        String screenContext,

        @JsonProperty("current_app")
        @JsonAlias({"currentApp", "app"})
        @Size(max = 255, message = "current_app must not exceed 255 characters")
        String currentApp,

        @JsonAlias({"model_id", "modelId"})
        String model
) {

    /** The best available textual context for the model. */
    public String effectivePrompt() {
        if (prompt != null && !prompt.isBlank()) {
            return prompt.trim();
        }
        if (screenContext != null && !screenContext.isBlank()) {
            return screenContext.trim();
        }
        return "";
    }
}
