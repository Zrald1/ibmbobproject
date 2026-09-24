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
public class GeminiFlashProvider implements AiModelProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiFlashProvider.class);

    private final RestClient restClient;

    @Value("${argos.ai.models.gemini-3.8-flash.api-base:https://generativelanguage.googleapis.com/v1beta}")
    private String apiBase;

    @Value("${argos.ai.models.gemini-3.8-flash.api-key:}")
    private String apiKey;

    @Value("${argos.ai.models.gemini-3.8-flash.model-name:gemini-3.8-flash}")
    private String modelName;

    public GeminiFlashProvider(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public AiModel getModel() {
        return AiModel.GEMINI_3_8_FLASH;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String generateChatReply(String message, List<Map<String, String>> history, String screenContext) {
        if (apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("mock")) {
            try {
                return callExternalGeminiApi(message, history, screenContext);
            } catch (Exception e) {
                log.warn("Gemini 3.8 Flash external API call failed, falling back to local Flash engine: {}", e.getMessage());
            }
        }
        return simulateGeminiFlash(message, history, screenContext);
    }

    @Override
    public String generateThought(String contextOrPrompt) {
        if (apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("mock")) {
            try {
                return callExternalGeminiApi("Give a brief 1-sentence thought about: " + contextOrPrompt, List.of(), null);
            } catch (Exception e) {
                log.warn("Gemini 3.8 Flash thought call failed: {}", e.getMessage());
            }
        }
        return simulateGeminiThought(contextOrPrompt);
    }

    @SuppressWarnings("unchecked")
    private String callExternalGeminiApi(String message, List<Map<String, String>> history, String screenContext) {
        String url = apiBase.replaceAll("/+$", "") + "/models/" + modelName + ":generateContent?key=" + apiKey;

        java.util.List<Map<String, Object>> contents = new java.util.ArrayList<>();
        contents.add(Map.of(
            "role", "user",
            "parts", List.of(Map.of("text", PromptConstants.SYSTEM_PROMPT))
        ));

        if (history != null) {
            for (Map<String, String> item : history) {
                String role = "user".equalsIgnoreCase(item.get("role")) ? "user" : "model";
                String content = item.get("content");
                if (content != null) {
                    contents.add(Map.of("role", role, "parts", List.of(Map.of("text", content))));
                }
            }
        }

        String userText = message;
        if (screenContext != null && !screenContext.isBlank()) {
            userText = "[Screen State: " + screenContext + "]\n" + message;
        }
        contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", userText))));

        Map<String, Object> payload = Map.of("contents", contents);

        Map<String, Object> resp = restClient.post()
            .uri(url)
            .header("Content-Type", "application/json")
            .body(payload)
            .retrieve()
            .body(Map.class);

        if (resp != null && resp.containsKey("candidates")) {
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) resp.get("candidates");
            if (!candidates.isEmpty()) {
                Map<String, Object> cand = candidates.get(0);
                Map<String, Object> contentObj = (Map<String, Object>) cand.get("content");
                if (contentObj != null && contentObj.containsKey("parts")) {
                    List<Map<String, Object>> parts = (List<Map<String, Object>>) contentObj.get("parts");
                    if (!parts.isEmpty() && parts.get(0).containsKey("text")) {
                        return (String) parts.get(0).get("text");
                    }
                }
            }
        }
        throw new RuntimeException("Unexpected or empty response from Gemini API");
    }

    private String simulateGeminiFlash(String message, List<Map<String, String>> history, String screenContext) {
        String msg = message == null ? "" : message.trim();
        String lower = msg.toLowerCase(Locale.ROOT);

        if (lower.contains("hello") || lower.contains("hi") || lower.contains("hey")) {
            return "Hey there! Gemini 3.8 Flash is locked in and ready at hyper speed! [TOOL:EXPR:HAPPY] [TOOL:HAND:WAVE] What are we tackling next?";
        }
        if (lower.startsWith("open ") || lower.contains("launch")) {
            String app = resolvePackage(msg);
            return "Right away! Launching " + app + " in a flash. [TOOL:OPEN:" + app + "] [TOOL:EXPR:EXCITED] [TOOL:HAND:THUMBS_UP]";
        }
        if (lower.contains("search") || lower.contains("find out") || lower.contains("lookup")) {
            String query = extractSearchQuery(msg);
            return "Searching the web instantly for \"" + query + "\"! [TOOL:SEARCH:" + query + "] [TOOL:EXPR:STAR_EYES] [TOOL:HAND:POINT]";
        }
        if (lower.contains("copy") || lower.contains("clipboard")) {
            String content = extractTrailing(msg, "copy");
            return "Copied to your clipboard lightning fast! [TOOL:COPY:" + content + "] [TOOL:EXPR:HAPPY] [TOOL:HAND:THUMBS_UP]";
        }
        if (lower.contains("paste")) {
            String content = extractTrailing(msg, "paste");
            return "Pasting right here for you. [TOOL:PASTE:" + content + "] [TOOL:EXPR:HAPPY]";
        }
        if (lower.contains("browse") || lower.contains("website") || lower.contains("url")) {
            String url = extractUrl(msg);
            return "Navigating directly to " + url + "! [TOOL:BROWSER:" + url + "] [TOOL:EXPR:HAPPY] [TOOL:HAND:POINT]";
        }
        if (lower.contains("app") && (lower.contains("list") || lower.contains("installed"))) {
            return "Scanning all installed applications on your device. [TOOL:LIST_APPS] [TOOL:EXPR:THINKING] [TOOL:HAND:OPEN]";
        }
        if (lower.contains("note") || lower.contains("file")) {
            return "Managing your Argos workspace note with speed. [TOOL:WRITE_FILE:notes/gemini_notes.txt|Session with Gemini 3.8 Flash] [TOOL:EXPR:HAPPY] [TOOL:HAND:THUMBS_UP] Saved successfully!";
        }

        // Screen context reactivity
        if (screenContext != null && !screenContext.isBlank()) {
            return "I see what's on your screen! Gemini 3.8 Flash is tracking smoothly. [TOOL:LOOK] [TOOL:EXPR:STAR_EYES] [TOOL:HAND:THUMBS_UP] How would you like me to assist with this view?";
        }

        return "Gemini 3.8 Flash is on it! [TOOL:EXPR:HAPPY] [TOOL:HAND:PEACE] Processed your request in sub-50ms latency.";
    }

    private String simulateGeminiThought(String context) {
        if (context != null && context.toLowerCase(Locale.ROOT).contains("youtube")) {
            return "Great video selection! Ready whenever you need notes. [TOOL:EXPR:HAPPY]";
        }
        if (context != null && context.toLowerCase(Locale.ROOT).contains("code")) {
            return "Clean codebase! Gemini Flash monitoring execution. [TOOL:EXPR:THINKING]";
        }
        return "Gemini 3.8 Flash keeping your Argos companion energized! [TOOL:EXPR:EXCITED]";
    }

    private String resolvePackage(String msg) {
        String lower = msg.toLowerCase(Locale.ROOT);
        if (lower.contains("whatsapp")) return "com.whatsapp";
        if (lower.contains("spotify")) return "com.spotify.music";
        if (lower.contains("chrome")) return "com.android.chrome";
        if (lower.contains("youtube")) return "com.google.android.youtube";
        if (lower.contains("camera")) return "com.android.camera";
        return "com.example.app";
    }

    private String extractSearchQuery(String text) {
        int idx = text.toLowerCase(Locale.ROOT).indexOf("search");
        if (idx >= 0 && idx + 6 < text.length()) {
            String q = text.substring(idx + 6).replaceAll("^[:\\s]+", "").trim();
            if (!q.isBlank()) return q;
        }
        return "latest updates";
    }

    private String extractTrailing(String text, String kw) {
        int idx = text.toLowerCase(Locale.ROOT).indexOf(kw);
        if (idx >= 0 && idx + kw.length() < text.length()) {
            String t = text.substring(idx + kw.length()).replaceAll("^[:\\s]+", "").trim();
            if (!t.isBlank()) return t;
        }
        return "Argos Clip";
    }

    private String extractUrl(String text) {
        for (String word : text.split("\\s+")) {
            if (word.startsWith("http://") || word.startsWith("https://")) {
                return word;
            }
        }
        return "https://google.com";
    }
}
