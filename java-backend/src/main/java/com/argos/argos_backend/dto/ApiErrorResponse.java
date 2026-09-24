package com.argos.argos_backend.dto;

import java.time.Instant;
import java.util.Map;

public record ApiErrorResponse(
    boolean success,
    String detail,
    String message,
    String error,
    int status,
    String path,
    Instant timestamp,
    Map<String, String> validationErrors
) {
    public ApiErrorResponse(
        boolean success,
        String message,
        int status,
        String path,
        Instant timestamp,
        Map<String, String> validationErrors
    ) {
        this(success, message, message, message, status, path, timestamp, validationErrors);
    }
}
