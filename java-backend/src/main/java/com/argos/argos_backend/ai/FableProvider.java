package com.argos.argos_backend.ai;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class FableProvider implements AiModelProvider {

    private static final Logger log = LoggerFactory.getLogger(FableProvider.class);

    private final RestClient restClient;

    @Value("${argos.ai.models.fable-5.1.api-base:https://api.fable.ai/v1}")
    private String apiBase;

    @Value("${argos.ai.models.fable-5.1.api-key:}")
    private String apiKey;

    @Value("${argos.ai.models.fable-5.1.model-name:fable-5.1}")
    private String modelName;

    public FableProvider(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public AiModel getModel() {
        return AiModel.FABLE_5_1;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String generateChatReply(String message, List<Map<String, String>> history, String screenContext) {
        if (apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("mock")) {
            try {
                return callExternalFableApi(message, history, screenContext);
            } catch (Exception e) {
                log.warn("Fable 5.1 external API call failed, switching to local Fable persona engine: {}", e.getMessage());
            }
        }
        return simulateFableIntelligence(message, history, screenContext);
    }

    @Override
    public String generateThought(String contextOrPrompt) {
        if (apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("mock")) {
            try {
                return callExternalFableApi("Generate a 1-sentence expressive companion thought for: " + contextOrPrompt, List.of(), null);
            } catch (Exception e) {
                log.warn("Fable 5.1 thought call failed: {}", e.getMessage());
            }
        }
        return simulateFableThought(contextOrPrompt);
    }

    @SuppressWarnings("unchecked")
    private String callExternalFableApi(String message, List<Map<String, String>> history, String screenContext) {
        String url = apiBase.replaceAll("/+$", "") + "/messages";

        Map<String, Object> payload = Map.of(
            "model", modelName,
            "system", PromptConstants.SYSTEM_PROMPT,
            "messages", List.of(Map.of("role", "user", "content", message)),
            "max_tokens", 350,
            "temperature", 0.85
        );

        Map<String, Object> resp = restClient.post()
            .uri(url)
            .header("x-api-key", apiKey)
            .header("Content-Type", "application/json")
            .body(payload)
            .retrieve()
            .body(Map.class);

        if (resp != null && resp.containsKey("content")) {
            Object content = resp.get("content");
            if (content instanceof List<?> list && !list.isEmpty()) {
                Object item = list.get(0);
                if (item instanceof Map<?, ?> map && map.containsKey("text")) {
                    return (String) map.get("text");
                }
            } else if (content instanceof String s) {
                return s;
            }
        }
        throw new RuntimeException("Unexpected response format from Fable API");
    }

    private String simulateFableIntelligence(String message, List<Map<String, String>> history, String screenContext) {
        String msg = message == null ? "" : message.trim();
        String lower = msg.toLowerCase(Locale.ROOT);

        if (lower.contains("hello") || lower.contains("hi") || lower.contains("hey")) {
            return "Oh hello, wonderful friend! Fable 5.1 is delighted to float beside you today! [TOOL:EXPRSEQ:[{\"expr\":\"SURPRISED\",\"duration\":1.0},{\"expr\":\"HAPPY\",\"duration\":2.0}]] [TOOL:HAND:HEART] What adventures do we have planned?";
        }
        if (lower.contains("happy") || lower.contains("win") || lower.contains("good") || lower.contains("great") || lower.contains("yay")) {
            return "That is absolutely fantastic news! [TOOL:EXPR:LAUGHING] [TOOL:HAND:CLAP] Celebrating this awesome moment with you!";
        }
        if (lower.contains("sad") || lower.contains("tired") || lower.contains("bad")) {
            return "I am right here with you. Take a gentle breath. [TOOL:EXPR:LOVE] [TOOL:HAND:OPEN] We will navigate everything together, one step at a time.";
        }
        if (lower.contains("write") || lower.contains("story") || lower.contains("note")) {
            return "Crafting our memories together! [TOOL:EXPR:LOVE] [TOOL:WRITE_FILE:notes/fable_journal.txt|A story woven with Argos and Fable 5.1] [TOOL:HAND:HEART] Your entry has been gently saved to your notes.";
        }
        if (lower.startsWith("open ") || lower.contains("app")) {
            String app = (lower.contains("music") || lower.contains("spotify")) ? "com.spotify.music" : "com.example.app";
            return "Opening this up for you with joy! [TOOL:OPEN:" + app + "] [TOOL:EXPR:EXCITED] [TOOL:HAND:RAISED]";
        }
        if (lower.contains("remind") || lower.contains("alarm")) {
            return "I've carefully kept note of your reminder! [TOOL:SCHEDULE:10:00:Cherished Task] [TOOL:EXPR:HAPPY] [TOOL:HAND:THUMBS_UP]";
        }
        if (lower.contains("confused") || lower.contains("what") || lower.contains("how")) {
            return "Let's explore this mystery together! [TOOL:EXPR:CONFUSED] [TOOL:HAND:SCRATCH] Tell me a little more so I can understand completely.";
        }

        return "Fable 5.1 listens closely with full empathy. [TOOL:EXPR:WINK] [TOOL:HAND:WAVE] Whatever you need, Argos is right by your side!";
    }

    private String simulateFableThought(String context) {
        return "It's so wonderful spending time creating together! [TOOL:EXPR:LOVE]";
    }
}
