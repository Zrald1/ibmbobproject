package com.argos.argos_backend.ai;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.argos.argos_backend.dto.ModelInfoDto;

@Service
public class AiModelRouter {

    private static final Logger log = LoggerFactory.getLogger(AiModelRouter.class);

    private final Map<AiModel, AiModelProvider> providerMap = new EnumMap<>(AiModel.class);
    private final Map<String, Long> requestCounters = new ConcurrentHashMap<>();

    @Value("${argos.ai.default-model:gemini-3.8-flash}")
    private String defaultModelConfig;

    public AiModelRouter(List<AiModelProvider> providers) {
        for (AiModelProvider provider : providers) {
            providerMap.put(provider.getModel(), provider);
        }
    }

    public AiModel resolveModel(String requestedModel) {
        if (requestedModel != null && !requestedModel.isBlank()) {
            return AiModel.fromString(requestedModel);
        }
        return AiModel.fromString(defaultModelConfig);
    }

    public String routeChat(String message, List<Map<String, String>> history, String screenContext, String requestedModel) {
        AiModel targetModel = resolveModel(requestedModel);
        requestCounters.merge(targetModel.getId(), 1L, Long::sum);

        AiModelProvider primary = providerMap.get(targetModel);
        if (primary != null) {
            try {
                return primary.generateChatReply(message, history, screenContext);
            } catch (Exception e) {
                log.error("Primary model {} failed for chat: {}. Attempting fallback cascade.", targetModel, e.getMessage());
            }
        }

        // Fallback cascade to another available provider
        for (Map.Entry<AiModel, AiModelProvider> entry : providerMap.entrySet()) {
            if (entry.getKey() != targetModel && entry.getValue() != null) {
                try {
                    log.info("Cascading to secondary model {} for resilient response", entry.getKey());
                    return entry.getValue().generateChatReply(message, history, screenContext);
                } catch (Exception ex) {
                    log.warn("Secondary model {} also failed: {}", entry.getKey(), ex.getMessage());
                }
            }
        }

        // Ultimate fallback
        return "I am with you in offline fallback mode! [TOOL:EXPR:HAPPY] [TOOL:HAND:WAVE] Let's keep moving forward.";
    }

    public String routeThought(String contextOrPrompt, String requestedModel) {
        AiModel targetModel = resolveModel(requestedModel);
        AiModelProvider primary = providerMap.get(targetModel);
        if (primary != null) {
            try {
                return primary.generateThought(contextOrPrompt);
            } catch (Exception e) {
                log.warn("Thought generation on {} failed: {}. Trying fallback.", targetModel, e.getMessage());
            }
        }

        for (Map.Entry<AiModel, AiModelProvider> entry : providerMap.entrySet()) {
            if (entry.getKey() != targetModel && entry.getValue() != null) {
                try {
                    return entry.getValue().generateThought(contextOrPrompt);
                } catch (Exception ignored) {}
            }
        }

        return "Argos monitoring your workspace seamlessly. [TOOL:EXPR:HAPPY]";
    }

    public List<ModelInfoDto> getAvailableModels() {
        return List.of(
            new ModelInfoDto(
                AiModel.GPT_6_ASTRA.getId(),
                AiModel.GPT_6_ASTRA.getDisplayName(),
                AiModel.GPT_6_ASTRA.getDescription(),
                true,
                "Deep reasoning, multi-tool orchestration, file tree automation",
                requestCounters.getOrDefault(AiModel.GPT_6_ASTRA.getId(), 0L)
            ),
            new ModelInfoDto(
                AiModel.GEMINI_3_8_FLASH.getId(),
                AiModel.GEMINI_3_8_FLASH.getDisplayName(),
                AiModel.GEMINI_3_8_FLASH.getDescription(),
                true,
                "Sub-50ms latency, real-time screen awareness, instant actions",
                requestCounters.getOrDefault(AiModel.GEMINI_3_8_FLASH.getId(), 0L)
            ),
            new ModelInfoDto(
                AiModel.FABLE_5_1.getId(),
                AiModel.FABLE_5_1.getDisplayName(),
                AiModel.FABLE_5_1.getDescription(),
                true,
                "Expressive emotional sequences (EXPRSEQ), empathetic companion",
                requestCounters.getOrDefault(AiModel.FABLE_5_1.getId(), 0L)
            )
        );
    }
}
