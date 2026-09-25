package com.argos.argos_backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * A single conversation turn, matching the Python backend's
 * {@code {"role","content"}} history entries.
 */
public record ChatMessage(
        @JsonAlias({"name"}) String role,
        @JsonAlias({"text", "message"}) String content) {
}
