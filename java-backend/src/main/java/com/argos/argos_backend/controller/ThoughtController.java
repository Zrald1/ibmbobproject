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

@RestController
@RequestMapping("/api")
public class ThoughtController {

    private final ThoughtService thoughtService;

    public ThoughtController(ThoughtService thoughtService) {
        this.thoughtService = thoughtService;
    }

    @PostMapping("/thought")
    public ResponseEntity<ThoughtResponse> thought(
        @RequestBody(required = false) ThoughtRequest request,
        @RequestHeader(value = "X-Argos-Model", required = false) String headerModel
    ) {
        ThoughtRequest req = request != null ? request : new ThoughtRequest(null, null, null, headerModel, null);
        if (req.model() == null && headerModel != null) {
            req = new ThoughtRequest(req.prompt(), req.screenContext(), req.currentApp(), headerModel, req.deviceId());
        }
        return ResponseEntity.ok(thoughtService.generateThought(req));
    }
}
