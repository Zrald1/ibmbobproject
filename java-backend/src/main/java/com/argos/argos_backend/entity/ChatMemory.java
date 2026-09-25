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
 * A single persisted conversation turn, keyed by device/session so the backend
 * can rebuild recent context and survive restarts.
 */
@Entity
@Table(name = "chat_memory", indexes = {
        @Index(name = "idx_chat_device_time", columnList = "device_id,created_at")
})
public class ChatMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String deviceId;

    @Column(nullable = false, length = 32)
    private String role;

    @Column(nullable = false, length = 8000)
    private String content;

    @Column(length = 64)
    private String model;

    @Column(nullable = false)
    private Instant createdAt;

    public ChatMemory() {
    }

    public ChatMemory(String deviceId, String role, String content, String model) {
        this.deviceId = deviceId;
        this.role = role;
        this.content = truncate(content);
        this.model = model;
    }

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 8000 ? value.substring(0, 8000) : value;
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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = truncate(content);
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
