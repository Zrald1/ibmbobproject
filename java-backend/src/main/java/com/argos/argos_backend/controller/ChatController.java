package com.argos.argos_backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.argos.argos_backend.dto.ChatRequest;
import com.argos.argos_backend.dto.ChatResponse;
import com.argos.argos_backend.service.ChatService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * Conversational endpoint. The model can be selected via the request body
     * ({@code model}) or the {@code X-Argos-Model} header; the body wins.
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(
            @Valid @RequestBody ChatRequest request,
            @RequestHeader(value = "X-Argos-Model", required = false) String modelHeader) {
        return ResponseEntity.ok(chatService.processChat(request, modelHeader));
    }
}
