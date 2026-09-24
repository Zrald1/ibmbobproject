package com.argos.argos_backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeviceAuthRequest(
    @NotBlank(message = "deviceId is required")
    @Size(max = 255, message = "deviceId must not exceed 255 characters")
    @JsonProperty("device_id")
    @JsonAlias({"deviceId", "device_id"})
    String deviceId,

    @Size(max = 100, message = "deviceName must not exceed 100 characters")
    @JsonProperty("device_name")
    @JsonAlias({"deviceName", "device_name"})
    String deviceName
) {}