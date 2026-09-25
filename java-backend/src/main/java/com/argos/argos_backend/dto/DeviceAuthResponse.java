package com.argos.argos_backend.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;

/**
 * Device authentication response conforming to {@code backend/README.md}:
 * {@code access_token}, {@code token_type}, {@code device_id} and a nested
 * {@code user} object - plus the backwards-compatible camelCase fields
 * ({@code accessToken}, {@code deviceId}, {@code isNewDevice}) used by the
 * existing Java fallback contract.
 */
public class DeviceAuthResponse {

    /** The authenticated device's user projection. */
    public record User(long id, String username) {
    }

    private final boolean success;
    private final String accessToken;
    private final String tokenType;
    private final String deviceId;
    private final User user;
    private final String message;
    private final boolean newDevice;

    public DeviceAuthResponse(boolean success, String accessToken, String tokenType,
                              String deviceId, User user, String message, boolean newDevice) {
        this.success = success;
        this.accessToken = accessToken;
        this.tokenType = tokenType;
        this.deviceId = deviceId;
        this.user = user;
        this.message = message;
        this.newDevice = newDevice;
    }

    /**
     * Serialises both the README (snake_case) contract and the legacy camelCase
     * fields. This is the only Jackson-visible member, giving exact control over
     * the emitted JSON.
     */
    @JsonAnyGetter
    public Map<String, Object> asJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("success", success);
        json.put("access_token", accessToken);
        json.put("token_type", tokenType);
        json.put("device_id", deviceId);
        json.put("user", user);
        json.put("message", message);
        // Backwards-compatible fields.
        json.put("accessToken", accessToken);
        json.put("deviceId", deviceId);
        json.put("isNewDevice", newDevice);
        return json;
    }
}
