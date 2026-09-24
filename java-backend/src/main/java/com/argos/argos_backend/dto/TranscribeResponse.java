package com.argos.argos_backend.dto;

public record TranscribeResponse(
    String text,
    double confidence
) {}
