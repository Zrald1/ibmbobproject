package com.argos.argos_backend.exception;

import com.argos.argos_backend.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleRequestBodyValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        Map<String, String> validationErrors = new LinkedHashMap<>();

        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            validationErrors.put(
                    fieldError.getField(),
                    fieldError.getDefaultMessage()
            );
        }

        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Request validation failed",
                request,
                validationErrors
        );
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodValidation(
            HandlerMethodValidationException exception,
            HttpServletRequest request
    ) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Request validation failed",
                request,
                Map.of()
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidJson(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Invalid JSON request body",
                request,
                Map.of()
        );
    }

    @ExceptionHandler(ExternalServiceException.class)
    public ResponseEntity<ApiErrorResponse> handleExternalServiceError(
            ExternalServiceException exception,
            HttpServletRequest request
    ) {
        return buildErrorResponse(
                HttpStatus.SERVICE_UNAVAILABLE,
                exception.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedError(
            Exception exception,
            HttpServletRequest request
    ) {
        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected server error occurred",
                request,
                Map.of()
        );
    }

    private ResponseEntity<ApiErrorResponse> buildErrorResponse(
            HttpStatus status,
            String message,
            HttpServletRequest request,
            Map<String, String> validationErrors
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                false,
                message,
                status.value(),
                request.getRequestURI(),
                Instant.now(),
                validationErrors
        );

        return ResponseEntity.status(status).body(response);
    }
}