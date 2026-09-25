package com.argos.argos_backend.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

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

    /**
     * Transcribe an uploaded audio clip. Accepts the multipart part under either
     * {@code file} or {@code audio}.
     */
    @PostMapping("/transcribe")
    public ResponseEntity<TranscribeResponse> transcribe(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "audio", required = false) MultipartFile audio) {
        MultipartFile part = file != null ? file : audio;
        if (part == null || part.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No audio data received");
        }
        return ResponseEntity.ok(voiceService.transcribe(part));
    }

    /**
     * Full voice pipeline: transcribe the audio, then run a chat turn. Mirrors
     * the Python {@code /api/voice} multipart contract and also accepts the
     * Java-fallback field names.
     */
    @PostMapping("/voice")
    public ResponseEntity<ChatResponse> voice(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "audio", required = false) MultipartFile audio,
            @RequestParam(value = "history", required = false) String history,
            @RequestParam(value = "screen_context", required = false) String screenContextSnake,
            @RequestParam(value = "screenContext", required = false) String screenContextCamel,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "deviceId", required = false) String deviceIdCamel,
            @RequestParam(value = "device_id", required = false) String deviceIdSnake,
            @RequestHeader(value = "X-Argos-Model", required = false) String modelHeader) {

        MultipartFile part = file != null ? file : audio;
        if (part == null || part.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No audio data received");
        }
        String screenContext = screenContextSnake != null ? screenContextSnake : screenContextCamel;
        String deviceId = deviceIdCamel != null ? deviceIdCamel : deviceIdSnake;
        return ResponseEntity.ok(
                voiceService.processVoice(part, history, screenContext, model, deviceId, modelHeader));
    }
}
