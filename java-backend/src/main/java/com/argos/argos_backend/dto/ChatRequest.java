package com.argos.argos_backend.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
    @JsonProperty("device_id")
    @JsonAlias({"deviceId", "device_id"})
    String deviceId,

    @NotBlank(message = "message is required")
    @Size(max = 4000, message = "message must not exceed 4000 characters")
    String message,

    @JsonProperty("screen_context")
    @JsonAlias({"screenContext", "screen_context"})
    @Size(max = 10000, message = "screenContext must not exceed 10000 characters")
    String screenContext,

    List<Map<String, String>> history,

    String model
) {}
