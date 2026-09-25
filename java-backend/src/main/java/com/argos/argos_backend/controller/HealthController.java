package com.argos.argos_backend.controller;

import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.argos.argos_backend.ai.AiModelRouter;
import com.argos.argos_backend.repository.FailedRequestRepository;

/**
 * Comprehensive health endpoint reporting service status, database
 * connectivity, AI model availability, memory and uptime.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final DataSource dataSource;
    private final AiModelRouter router;
    private final FailedRequestRepository failedRequestRepository;

    public HealthController(DataSource dataSource,
                            AiModelRouter router,
                            FailedRequestRepository failedRequestRepository) {
        this.dataSource = dataSource;
        this.router = router;
        this.failedRequestRepository = failedRequestRepository;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "ARGOS Java fallback backend");
        body.put("version", "0.0.1-SNAPSHOT");
        body.put("timestamp", Instant.now().toString());
        body.put("uptimeSeconds", uptimeSeconds());
        body.put("database", databaseStatus());

        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("default_model", router.defaultModel().getId());
        ai.put("models", router.availability());
        ai.put("metrics", router.metrics());
        body.put("ai", ai);

        body.put("memory", memoryStats());
        body.put("unresolvedFailures", safeUnresolvedFailures());
        body.put("fallbackAvailable", true);

        return ResponseEntity.ok(body);
    }

    private long uptimeSeconds() {
        long start = ManagementFactory.getRuntimeMXBean().getStartTime();
        return Math.max(0, (System.currentTimeMillis() - start) / 1000);
    }

    private Map<String, Object> databaseStatus() {
        Map<String, Object> db = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            db.put("status", connection.isValid(2) ? "UP" : "DOWN");
            db.put("product", connection.getMetaData().getDatabaseProductName());
        } catch (Exception e) {
            db.put("status", "DOWN");
            db.put("error", e.getMessage());
        }
        return db;
    }

    private Map<String, Object> memoryStats() {
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        long total = runtime.totalMemory();
        long free = runtime.freeMemory();
        long used = total - free;
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("usedMb", used / (1024 * 1024));
        memory.put("maxMb", max / (1024 * 1024));
        memory.put("freeMb", free / (1024 * 1024));
        return memory;
    }

    private long safeUnresolvedFailures() {
        try {
            return failedRequestRepository.countByResolvedFalse();
        } catch (Exception e) {
            return -1;
        }
    }
}
