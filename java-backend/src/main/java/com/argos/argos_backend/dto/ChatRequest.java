package com.argos.argos_backend.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Chat request supporting BOTH client shapes:
 * <ul>
 *   <li>Python/primary Android: {@code {message, history, screen_context}}</li>
 *   <li>Java fallback Android/desktop: {@code {message, screenContext, deviceId, model}}</li>
 * </ul>
 * {@code deviceId} is optional so the primary shape (which omits it) still validates.
 */
public record ChatRequest(

        @JsonAlias({"device_id"})
        @Size(max = 255, message = "deviceId must not exceed 255 characters")
        String deviceId,

        @NotBlank(message = "message is required")
        @Size(max = 2000, message = "message must not exceed 2000 characters")
        String message,

        @JsonAlias({"screen_context"})
        @Size(max = 6000, message = "screenContext must not exceed 6000 characters")
        String screenContext,

        List<ChatMessage> history,

        @JsonAlias({"model_id", "modelId"})
        String model
) {

    /** Convenience: normalised deviceId, defaulting to a stable anonymous id. */
    public String effectiveDeviceId() {
        return (deviceId == null || deviceId.isBlank()) ? "argos-anonymous" : deviceId.trim();
    }

    /** Convenience: null-safe history. */
    public List<ChatMessage> safeHistory() {
        return history == null ? List.of() : history;
    }
}
