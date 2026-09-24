package com.argos.argos_backend.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.argos.argos_backend.ai.AiModelRouter;
import com.argos.argos_backend.ai.PromptConstants;
import com.argos.argos_backend.dto.ChatRequest;
import com.argos.argos_backend.dto.ChatResponse;
import com.argos.argos_backend.entity.CachedResponse;
import com.argos.argos_backend.entity.ChatMemory;
import com.argos.argos_backend.entity.FailedRequest;
import com.argos.argos_backend.repository.CachedResponseRepository;
import com.argos.argos_backend.repository.ChatMemoryRepository;
import com.argos.argos_backend.repository.FailedRequestRepository;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final AiModelRouter aiModelRouter;
    private final FallbackService fallbackService;
    private final ChatMemoryRepository chatMemoryRepository;
    private final CachedResponseRepository cachedResponseRepository;
    private final FailedRequestRepository failedRequestRepository;

    public ChatService(
        AiModelRouter aiModelRouter,
        FallbackService fallbackService,
        ChatMemoryRepository chatMemoryRepository,
        CachedResponseRepository cachedResponseRepository,
        FailedRequestRepository failedRequestRepository
    ) {
        this.aiModelRouter = aiModelRouter;
        this.fallbackService = fallbackService;
        this.chatMemoryRepository = chatMemoryRepository;
        this.cachedResponseRepository = cachedResponseRepository;
        this.failedRequestRepository = failedRequestRepository;
    }

    public ChatResponse processChat(ChatRequest request) {
        String msg = request.message() == null ? "" : request.message().trim();
        String deviceId = (request.deviceId() == null || request.deviceId().isBlank()) ? "default-device" : request.deviceId().trim();
        String modelKey = aiModelRouter.resolveModel(request.model()).getId();

        // 1. Check Query Cache
        String cacheKey = hashKey(modelKey + ":" + msg);
        try {
            Optional<CachedResponse> cachedOpt = cachedResponseRepository.findByQueryHash(cacheKey);
            if (cachedOpt.isPresent() && !cachedOpt.get().isExpired()) {
                CachedResponse c = cachedOpt.get();
                return new ChatResponse(
                    true,
                    "cache",
                    c.getResponseText(),
                    c.getExpression(),
                    c.getHandGesture(),
                    modelKey,
                    null,
                    false
                );
            }
        } catch (Exception e) {
            log.warn("Cache lookup non-fatal error: {}", e.getMessage());
        }

        // 2. Route Chat through AI Model Pipeline
        try {
            String rawReply = aiModelRouter.routeChat(
                msg,
                request.history(),
                request.screenContext(),
                request.model()
            );

            String expression = PromptConstants.extractExpression(rawReply, "HAPPY");
            String handGesture = PromptConstants.extractGesture(rawReply, "REST");

            // Persist Memory
            saveMemorySafely(deviceId, "user", msg, modelKey, null);
            saveMemorySafely(deviceId, "assistant", rawReply, modelKey, expression);

            // Persist Cache
            saveCacheSafely(cacheKey, rawReply, expression, handGesture);

            return new ChatResponse(
                true,
                "ai-router",
                rawReply,
                expression,
                handGesture,
                modelKey,
                null,
                false
            );

        } catch (Exception ex) {
            log.error("AI pipeline encountered unexpected error: {}. Activating resilient fallback.", ex.getMessage());
            logFailedRequestSafely(deviceId, "/api/chat", ex.getMessage(), modelKey);
            return fallbackService.createFallbackResponse(msg, request.screenContext());
        }
    }

    private void saveMemorySafely(String deviceId, String role, String content, String model, String expr) {
        try {
            ChatMemory mem = new ChatMemory(deviceId, role, content, model, expr);
            chatMemoryRepository.save(mem);
        } catch (Exception e) {
            log.warn("Failed to persist chat memory: {}", e.getMessage());
        }
    }

    private void saveCacheSafely(String hash, String reply, String expr, String gesture) {
        try {
            CachedResponse c = new CachedResponse(hash, reply, expr, gesture, 30);
            cachedResponseRepository.save(c);
        } catch (Exception e) {
            log.debug("Cache store skipped or collision: {}", e.getMessage());
        }
    }

    private void logFailedRequestSafely(String deviceId, String endpoint, String error, String model) {
        try {
            FailedRequest fr = new FailedRequest(deviceId, endpoint, error, model);
            failedRequestRepository.save(fr);
        } catch (Exception e) {
            log.warn("Failed to log failed request telemetry: {}", e.getMessage());
        }
    }

    private String hashKey(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
