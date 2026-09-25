package com.argos.argos_backend.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.argos.argos_backend.ai.AiModelRouter;
import com.argos.argos_backend.ai.AiReply;
import com.argos.argos_backend.dto.ActionDto;
import com.argos.argos_backend.dto.ChatMessage;
import com.argos.argos_backend.dto.ChatRequest;
import com.argos.argos_backend.dto.ChatResponse;
import com.argos.argos_backend.entity.CachedResponse;
import com.argos.argos_backend.entity.ChatMemory;
import com.argos.argos_backend.entity.FailedRequest;
import com.argos.argos_backend.repository.CachedResponseRepository;
import com.argos.argos_backend.repository.ChatMemoryRepository;
import com.argos.argos_backend.repository.FailedRequestRepository;
import com.argos.argos_backend.service.CommandPlannerService.Plan;

/**
 * Orchestrates a chat turn: model routing with cascade fallback, response
 * caching, per-device conversation memory, failure telemetry, and a guaranteed
 * offline reply. This method never throws - it always returns a usable
 * {@link ChatResponse}.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final int MEMORY_TURNS = 10;

    private final AiModelRouter router;
    private final CommandPlannerService planner;
    private final FallbackService fallbackService;
    private final ChatMemoryRepository chatMemoryRepository;
    private final CachedResponseRepository cachedResponseRepository;
    private final FailedRequestRepository failedRequestRepository;

    public ChatService(AiModelRouter router,
                       CommandPlannerService planner,
                       FallbackService fallbackService,
                       ChatMemoryRepository chatMemoryRepository,
                       CachedResponseRepository cachedResponseRepository,
                       FailedRequestRepository failedRequestRepository) {
        this.router = router;
        this.planner = planner;
        this.fallbackService = fallbackService;
        this.chatMemoryRepository = chatMemoryRepository;
        this.cachedResponseRepository = cachedResponseRepository;
        this.failedRequestRepository = failedRequestRepository;
    }

    public ChatResponse processChat(ChatRequest request, String headerModel) {
        String message = request.message() == null ? "" : request.message().trim();
        String screenContext = request.screenContext();
        String deviceId = request.effectiveDeviceId();
        String requestedModel = (request.model() != null && !request.model().isBlank())
                ? request.model() : headerModel;

        try {
            String queryHash = hash(deviceId, message, screenContext, requestedModel);

            ChatResponse cached = readCache(queryHash);
            if (cached != null) {
                return cached;
            }

            persistMemory(deviceId, "user", message, requestedModel);
            List<ChatMessage> history = effectiveHistory(request, deviceId);

            AiReply reply = router.generateChat(message, history, screenContext, requestedModel);

            if (reply.degraded() && reply.failureReason() != null) {
                recordFailure(deviceId, "/api/chat", reply.model(), message, reply.failureReason());
            }

            String text = reply.text();
            String expression = planner.extractExpression(text);
            String gesture = planner.extractGesture(text);
            ActionDto action = safeAction(message, screenContext);

            ChatResponse response = ChatResponse.ok(
                    text, expression, gesture, reply.model(), action, reply.source(), reply.degraded());

            persistMemory(deviceId, "assistant", text, reply.model());
            writeCache(queryHash, response);
            return response;

        } catch (Exception e) {
            log.error("Chat processing failed, using offline fallback", e);
            recordFailure(deviceId, "/api/chat", requestedModel, message,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return fallbackService.createFallbackResponse(message, screenContext);
        }
    }

    private ActionDto safeAction(String message, String screenContext) {
        try {
            Plan plan = planner.plan(message, screenContext);
            return plan.action();
        } catch (Exception e) {
            return null;
        }
    }

    private List<ChatMessage> effectiveHistory(ChatRequest request, String deviceId) {
        if (!request.safeHistory().isEmpty()) {
            return request.safeHistory();
        }
        try {
            List<ChatMemory> recent = chatMemoryRepository.findTop40ByDeviceIdOrderByCreatedAtDesc(deviceId);
            if (recent.isEmpty()) {
                return List.of();
            }
            List<ChatMemory> chronological = new ArrayList<>(recent);
            Collections.reverse(chronological);
            int from = Math.max(0, chronological.size() - MEMORY_TURNS);
            List<ChatMessage> history = new ArrayList<>();
            for (ChatMemory m : chronological.subList(from, chronological.size())) {
                history.add(new ChatMessage(m.getRole(), m.getContent()));
            }
            return history;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void persistMemory(String deviceId, String role, String content, String model) {
        try {
            chatMemoryRepository.save(new ChatMemory(deviceId, role, content, model));
        } catch (Exception e) {
            log.warn("Could not persist chat memory ({}): {}", role, e.getMessage());
        }
    }

    private ChatResponse readCache(String queryHash) {
        try {
            return cachedResponseRepository.findByQueryHash(queryHash)
                    .filter(c -> !c.isExpired())
                    .map(c -> ChatResponse.ok(c.getReply(), c.getExpression(), c.getHandGesture(),
                            c.getModel(), null, "cache", false))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeCache(String queryHash, ChatResponse response) {
        try {
            CachedResponse entry = new CachedResponse(
                    queryHash,
                    response.getReply(),
                    response.getExpression(),
                    response.getHandGesture(),
                    response.getModel(),
                    Instant.now().plus(CACHE_TTL));
            cachedResponseRepository.save(entry);
        } catch (Exception e) {
            log.warn("Could not write response cache: {}", e.getMessage());
        }
    }

    private void recordFailure(String deviceId, String endpoint, String model,
                               String payload, String error) {
        try {
            failedRequestRepository.save(new FailedRequest(deviceId, endpoint, model, payload, error));
        } catch (Exception e) {
            log.warn("Could not record failed request: {}", e.getMessage());
        }
    }

    private static String hash(String deviceId, String message, String screenContext, String model) {
        String raw = String.join("|",
                nullSafe(deviceId),
                nullSafe(message).toLowerCase(java.util.Locale.ROOT).trim(),
                nullSafe(screenContext),
                nullSafe(model));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available on the JVM; fall back to a stable string hash.
            return Integer.toHexString(raw.hashCode());
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
