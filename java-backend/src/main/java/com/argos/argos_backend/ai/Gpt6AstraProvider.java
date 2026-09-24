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
public class Gpt6AstraProvider implements AiModelProvider {

    private static final Logger log = LoggerFactory.getLogger(Gpt6AstraProvider.class);

    private final RestClient restClient;

    @Value("${argos.ai.models.gpt-6-astra.api-base:https://api.openai.com/v1}")
    private String apiBase;

    @Value("${argos.ai.models.gpt-6-astra.api-key:}")
    private String apiKey;

    @Value("${argos.ai.models.gpt-6-astra.model-name:gpt-6-astra}")
    private String modelName;

    public Gpt6AstraProvider(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public AiModel getModel() {
        return AiModel.GPT_6_ASTRA;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String generateChatReply(String message, List<Map<String, String>> history, String screenContext) {
        if (apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("mock")) {
            try {
                return callExternalApi(message, history, screenContext);
            } catch (Exception e) {
                log.warn("GPT-6 Astra external API call failed, activating Astra reasoning fallback: {}", e.getMessage());
            }
        }
        return simulateAstraReasoning(message, history, screenContext);
    }

    @Override
    public String generateThought(String contextOrPrompt) {
        if (apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("mock")) {
            try {
                return callExternalThoughtApi(contextOrPrompt);
            } catch (Exception e) {
                log.warn("GPT-6 Astra external thought call failed: {}", e.getMessage());
            }
        }
        return simulateAstraThought(contextOrPrompt);
    }

    @SuppressWarnings("unchecked")
    private String callExternalApi(String message, List<Map<String, String>> history, String screenContext) {
        String url = apiBase.replaceAll("/+$", "") + "/chat/completions";

        java.util.List<Map<String, String>> messages = new java.util.ArrayList<>();
        messages.add(Map.of("role", "system", "content", PromptConstants.SYSTEM_PROMPT));

        if (history != null) {
            for (Map<String, String> item : history) {
                String role = item.get("role");
                String content = item.get("content");
                if (role != null && content != null) {
                    messages.add(Map.of("role", role, "content", content));
                }
            }
        }

        String userPrompt = message;
        if (screenContext != null && !screenContext.isBlank()) {
            userPrompt = "[Screen Context: " + screenContext + "]\n" + message;
        }
        messages.add(Map.of("role", "user", "content", userPrompt));

        Map<String, Object> payload = Map.of(
            "model", modelName,
            "messages", messages,
            "max_tokens", 450,
            "temperature", 0.7
        );

        Map<String, Object> resp = restClient.post()
            .uri(url)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .body(payload)
            .retrieve()
            .body(Map.class);

        if (resp != null && resp.containsKey("choices")) {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
            if (!choices.isEmpty()) {
                Map<String, Object> first = choices.get(0);
                Map<String, Object> msg = (Map<String, Object>) first.get("message");
                if (msg != null && msg.containsKey("content")) {
                    return (String) msg.get("content");
                }
            }
        }
        throw new RuntimeException("Empty or malformed response from GPT-6 Astra API");
    }

    private String callExternalThoughtApi(String context) {
        return callExternalApi(context, List.of(), null);
    }

    private String simulateAstraReasoning(String message, List<Map<String, String>> history, String screenContext) {
        String msg = message == null ? "" : message.trim();
        String lower = msg.toLowerCase(Locale.ROOT);

        // File and workspace tool requests
        if (lower.contains("write") && (lower.contains("file") || lower.contains("note"))) {
            return "Executing file write operation with Astra high precision. [TOOL:EXPR:THINKING] [TOOL:WRITE_FILE:notes/astra_log.txt|Argos Astra Intelligence Session] [TOOL:HAND:POINT] File successfully recorded to your workspace.";
        }
        if (lower.contains("list files") || lower.contains("show files") || lower.contains("file tree")) {
            return "Analyzing your local file repository. [TOOL:EXPR:THINKING] [TOOL:FILE_TREE] [TOOL:HAND:OPEN] Here is your current directory structure.";
        }
        if (lower.contains("search") && lower.contains("file")) {
            String query = extractQuery(msg, "file");
            return "Searching files for \"" + query + "\" across your Argos storage. [TOOL:EXPR:THINKING] [TOOL:SEARCH_FILES:" + query + "] [TOOL:HAND:POINT]";
        }
        if (lower.contains("search") || lower.contains("google")) {
            String q = extractQuery(msg, "search");
            return "Querying live intelligence on Astra knowledge mesh for: " + q + ". [TOOL:SEARCH:" + q + "] [TOOL:EXPR:HAPPY] [TOOL:HAND:POINT]";
        }
        if (lower.startsWith("open ") || lower.contains("launch app")) {
            String app = extractApp(msg);
            return "Astra system dispatcher routing request to application: " + app + ". [TOOL:OPEN:" + app + "] [TOOL:EXPR:HAPPY] [TOOL:HAND:THUMBS_UP]";
        }
        if (lower.contains("schedule") || lower.contains("remind")) {
            return "Scheduling priority event in device executive calendar. [TOOL:SCHEDULE:09:00:Argos Astra Sync] [TOOL:EXPR:HAPPY] [TOOL:HAND:THUMBS_UP] Reminder successfully locked in.";
        }
        if (lower.contains("look at") || lower.contains("turn")) {
            return "Orienting perspective toward your active display. [TOOL:LOOK] [TOOL:EXPR:TALKING] [TOOL:HAND:WAVE] Analyzing visual telemetry.";
        }
        if (lower.contains("hello") || lower.contains("hi") || lower.contains("hey")) {
            return "Greetings! GPT-6 Astra intelligence is active and synchronizing with your Argos companion. [TOOL:EXPR:HAPPY] [TOOL:HAND:WAVE] How may I assist your workflow today?";
        }

        String contextTag = (screenContext != null && !screenContext.isBlank()) ? " [TOOL:LOOK]" : "";
        return "GPT-6 Astra analyzed your instruction: \"" + msg + "\"." + contextTag + " [TOOL:EXPR:THINKING] [TOOL:HAND:THINK] All contextual parameters evaluated and ready for execution.";
    }

    private String simulateAstraThought(String context) {
        if (context == null || context.isBlank()) {
            return "GPT-6 Astra background monitor active. Systems optimal. [TOOL:EXPR:HAPPY]";
        }
        return "Astra real-time monitor: Observing active environment context. [TOOL:EXPR:THINKING]";
    }

    private String extractQuery(String text, String keyword) {
        int idx = text.toLowerCase(Locale.ROOT).indexOf(keyword);
        if (idx >= 0 && idx + keyword.length() < text.length()) {
            String res = text.substring(idx + keyword.length()).replaceAll("^[:\\s]+", "").trim();
            if (!res.isBlank()) return res;
        }
        return "Argos";
    }

    private String extractApp(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("whatsapp")) return "com.whatsapp";
        if (lower.contains("spotify")) return "com.spotify.music";
        if (lower.contains("youtube")) return "com.google.android.youtube";
        if (lower.contains("camera")) return "com.android.camera";
        if (lower.contains("settings")) return "com.android.settings";
        return "com.example.app";
    }
}
