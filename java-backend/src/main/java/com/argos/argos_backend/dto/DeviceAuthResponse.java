package com.argos.argos_backend.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DeviceAuthResponse(
    boolean success,
    @JsonProperty("device_id")
    String deviceId,
    @JsonProperty("access_token")
    String accessToken,
    @JsonProperty("token_type")
    String tokenType,
    Map<String, Object> user,
    boolean registered,
    String message
) {
    public DeviceAuthResponse(
        boolean success,
        String deviceId,
        String accessToken,
        boolean registered,
        String message
    ) {
        this(
            success,
            deviceId,
            accessToken,
            "bearer",
            Map.of("id", 1, "username", "device_" + (deviceId != null && deviceId.length() > 6 ? deviceId.substring(0, 6) : "user")),
            registered,
            message
        );
    }
}
