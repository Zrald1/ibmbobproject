package com.argos.argos_backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.argos.argos_backend.dto.ThoughtRequest;
import com.argos.argos_backend.dto.ThoughtResponse;
import com.argos.argos_backend.service.ThoughtService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class ThoughtController {

    private final ThoughtService thoughtService;

    public ThoughtController(ThoughtService thoughtService) {
        this.thoughtService = thoughtService;
    }

    /**
     * Proactive "thought bubble" generation from a prompt and/or screen context.
     */
    @PostMapping("/thought")
    public ResponseEntity<ThoughtResponse> thought(
            @Valid @RequestBody ThoughtRequest request,
            @RequestHeader(value = "X-Argos-Model", required = false) String modelHeader) {
        ThoughtRequest effective = (request.model() == null && modelHeader != null)
                ? new ThoughtRequest(request.prompt(), request.screenContext(), request.currentApp(), modelHeader)
                : request;
        return ResponseEntity.ok(thoughtService.generateThought(effective));
    }
}
