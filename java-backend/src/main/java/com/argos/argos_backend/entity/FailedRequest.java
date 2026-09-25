package com.argos.argos_backend.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Telemetry record for a failed upstream call. Used for auditing, resilience
 * metrics and later recovery/retry analysis.
 */
@Entity
@Table(name = "failed_requests", indexes = {
        @Index(name = "idx_failed_created", columnList = "created_at")
})
public class FailedRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 255)
    private String deviceId;

    @Column(length = 128)
    private String endpoint;

    @Column(length = 64)
    private String model;

    @Column(length = 4000)
    private String payload;

    @Column(length = 2000)
    private String errorMessage;

    @Column(nullable = false)
    private boolean resolved;

    @Column(nullable = false)
    private Instant createdAt;

    public FailedRequest() {
    }

    public FailedRequest(String deviceId, String endpoint, String model,
                         String payload, String errorMessage) {
        this.deviceId = deviceId;
        this.endpoint = endpoint;
        this.model = model;
        this.payload = clip(payload, 4000);
        this.errorMessage = clip(errorMessage, 2000);
        this.resolved = false;
    }

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = clip(payload, 4000);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = clip(errorMessage, 2000);
    }

    public boolean isResolved() {
        return resolved;
    }

    public void setResolved(boolean resolved) {
        this.resolved = resolved;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
