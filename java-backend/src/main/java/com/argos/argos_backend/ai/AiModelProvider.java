package com.argos.argos_backend.ai;

import java.util.List;
import java.util.Map;

public interface AiModelProvider {
    AiModel getModel();
    String generateChatReply(String message, List<Map<String, String>> history, String screenContext);
    String generateThought(String contextOrPrompt);
    boolean isAvailable();
}
