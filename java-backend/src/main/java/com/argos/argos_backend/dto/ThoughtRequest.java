package com.argos.argos_backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ThoughtRequest(
    String prompt,

    @JsonProperty("screen_context")
    @JsonAlias({"screenContext", "screen_context"})
    String screenContext,

    @JsonProperty("current_app")
    @JsonAlias({"currentApp", "current_app"})
    String currentApp,

    String model,

    @JsonProperty("device_id")
    @JsonAlias({"deviceId", "device_id"})
    String deviceId
) {
    public String getEffectivePrompt() {
        if (prompt != null && !prompt.isBlank()) {
            return prompt.trim();
        }
        if (screenContext != null && !screenContext.isBlank()) {
            return screenContext.trim();
        }
        if (currentApp != null && !currentApp.isBlank()) {
            return "User is currently viewing: " + currentApp.trim();
        }
        return "Argos background observation";
    }
}
