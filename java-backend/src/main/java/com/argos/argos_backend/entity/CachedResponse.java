package com.argos.argos_backend.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Response cache entry keyed by a hash of the normalised request, used to serve
 * repeat queries instantly and to reduce upstream model load.
 */
@Entity
@Table(name = "cached_responses")
public class CachedResponse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String queryHash;

    @Column(nullable = false, length = 8000)
    private String reply;

    @Column(length = 32)
    private String expression;

    @Column(length = 32)
    private String handGesture;

    @Column(length = 64)
    private String model;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    public CachedResponse() {
    }

    public CachedResponse(String queryHash, String reply, String expression,
                          String handGesture, String model, Instant expiresAt) {
        this.queryHash = queryHash;
        this.reply = truncate(reply);
        this.expression = expression;
        this.handGesture = handGesture;
        this.model = model;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
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

    public String getQueryHash() {
        return queryHash;
    }

    public void setQueryHash(String queryHash) {
        this.queryHash = queryHash;
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = truncate(reply);
    }

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public String getHandGesture() {
        return handGesture;
    }

    public void setHandGesture(String handGesture) {
        this.handGesture = handGesture;
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

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
