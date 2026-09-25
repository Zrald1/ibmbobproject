package com.argos.argos_backend.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.argos.argos_backend.ai.AiModel;
import com.argos.argos_backend.ai.AiModelRouter;
import com.argos.argos_backend.dto.ModelInfoDto;

/**
 * Model discovery endpoint listing the three top-tier Argos models, their
 * capabilities, operational status and per-model request telemetry.
 */
@RestController
@RequestMapping("/api")
public class ModelController {

    private final AiModelRouter router;

    public ModelController(AiModelRouter router) {
        this.router = router;
    }

    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> models() {
        Map<String, String> availability = router.availability();
        Map<String, Object> metrics = router.metrics();
        Map<?, ?> perModel = metrics.get("perModel") instanceof Map<?, ?> m ? m : Map.of();
        AiModel defaultModel = router.defaultModel();

        List<ModelInfoDto> models = new ArrayList<>();
        for (AiModel model : AiModel.values()) {
            long requests = perModel.get(model.getId()) instanceof Number n ? n.longValue() : 0L;
            models.add(new ModelInfoDto(
                    model.getId(),
                    model.getDisplayName(),
                    model.getVersion(),
                    model.getDescription(),
                    availability.getOrDefault(model.getId(), "simulation"),
                    model.getFeatures(),
                    requests,
                    model == defaultModel));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("models", models);
        body.put("default_model", defaultModel.getId());
        body.put("count", models.size());
        body.put("metrics", metrics);
        return ResponseEntity.ok(body);
    }
}
