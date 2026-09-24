package com.argos.argos_backend.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.argos.argos_backend.dto.ChatResponse;
import com.argos.argos_backend.dto.TranscribeResponse;
import com.argos.argos_backend.service.VoiceService;

@RestController
@RequestMapping("/api")
public class VoiceController {

    private final VoiceService voiceService;

    public VoiceController(VoiceService voiceService) {
        this.voiceService = voiceService;
    }

    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TranscribeResponse> transcribe(
        @RequestParam(value = "audio", required = false) MultipartFile audio,
        @RequestParam(value = "file", required = false) MultipartFile file
    ) {
        MultipartFile target = (audio != null) ? audio : file;
        return ResponseEntity.ok(voiceService.transcribe(target));
    }

    @PostMapping(value = "/voice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ChatResponse> voice(
        @RequestParam(value = "audio", required = false) MultipartFile audio,
        @RequestParam(value = "file", required = false) MultipartFile file,
        @RequestParam(value = "screen_context", required = false) String screenContext,
        @RequestParam(value = "history", required = false) String history,
        @RequestParam(value = "model", required = false) String model,
        @RequestHeader(value = "X-Argos-Model", required = false) String headerModel
    ) {
        MultipartFile target = (audio != null) ? audio : file;
        String effectiveModel = (model != null && !model.isBlank()) ? model : headerModel;
        return ResponseEntity.ok(voiceService.processVoice(target, screenContext, history, effectiveModel));
    }
}
