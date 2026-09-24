package com.argos.argos_backend.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "failed_requests")
public class FailedRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 255)
    private String deviceId;

    @Column(length = 100)
    private String endpoint;

    @Column(length = 2000)
    private String errorMessage;

    @Column(length = 50)
    private String attemptedModel;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public FailedRequest() {}

    public FailedRequest(String deviceId, String endpoint, String errorMessage, String attemptedModel) {
        this.deviceId = deviceId;
        this.endpoint = endpoint;
        this.errorMessage = errorMessage;
        this.attemptedModel = attemptedModel;
    }

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
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

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getAttemptedModel() {
        return attemptedModel;
    }

    public void setAttemptedModel(String attemptedModel) {
        this.attemptedModel = attemptedModel;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
