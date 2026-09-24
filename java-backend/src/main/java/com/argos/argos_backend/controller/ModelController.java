package com.argos.argos_backend.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.argos.argos_backend.ai.AiModelRouter;
import com.argos.argos_backend.dto.ModelInfoDto;

@RestController
@RequestMapping("/api")
public class ModelController {

    private final AiModelRouter aiModelRouter;

    public ModelController(AiModelRouter aiModelRouter) {
        this.aiModelRouter = aiModelRouter;
    }

    @GetMapping("/models")
    public ResponseEntity<List<ModelInfoDto>> getModels() {
        return ResponseEntity.ok(aiModelRouter.getAvailableModels());
    }
}
