package com.argos.argos_backend.controller;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.argos.argos_backend.ai.AiModelRouter;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final AiModelRouter aiModelRouter;
    private final Instant startTime = Instant.now();

    public HealthController(AiModelRouter aiModelRouter) {
        this.aiModelRouter = aiModelRouter;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "ok",
            "version", "1.0.0",
            "service", "ARGOS Java fallback backend",
            "timestamp", Instant.now().toString(),
            "uptimeSeconds", java.time.Duration.between(startTime, Instant.now()).getSeconds(),
            "fallbackAvailable", true,
            "models", aiModelRouter.getAvailableModels().stream().map(m -> m.name()).toList()
        ));
    }
}
