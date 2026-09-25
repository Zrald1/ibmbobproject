package com.argos.argos_backend.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Uniform error body. Includes BOTH {@code detail} (the first field the Android
 * {@code FloatingRobotService} parses on non-2xx responses, matching FastAPI's
 * {@code HTTPException}) and {@code message} for other clients.
 */
public record ApiErrorResponse(

        String detail,
        boolean success,
        String message,
        int status,
        String path,
        Instant timestamp,
        Map<String, String> validationErrors
) {

    public static ApiErrorResponse of(String detail, int status, String path,
                                      Map<String, String> validationErrors) {
        return new ApiErrorResponse(
                detail,
                false,
                detail,
                status,
                path,
                Instant.now(),
                validationErrors == null ? Map.of() : validationErrors);
    }
}
