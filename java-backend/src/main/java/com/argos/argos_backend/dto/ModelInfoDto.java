package com.argos.argos_backend.dto;

public record ModelInfoDto(
    String id,
    String name,
    String description,
    boolean available,
    String specialties,
    long requestCount
) {}
