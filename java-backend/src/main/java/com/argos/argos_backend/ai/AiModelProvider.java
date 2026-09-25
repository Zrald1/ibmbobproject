package com.argos.argos_backend.ai;

import java.util.List;

import com.argos.argos_backend.dto.ChatMessage;

/**
 * Common contract for the pluggable Argos model providers.
 *
 * <p>Implementations may call a live upstream API when credentials are present,
 * or fall back to a deterministic offline simulation that still emits valid
 * Argos tool tags. A provider that cannot produce a reply must throw so the
 * {@link AiModelRouter} can cascade to the next model.</p>
 */
public interface AiModelProvider {

    /** The model this provider serves. */
    AiModel getModel();

    /** {@code true} when a live upstream API key is configured. */
    boolean isAvailable();

    /**
     * Generate a conversational reply, including inline tool tags.
     *
     * @param message       the user's message
     * @param history       prior conversation turns (may be {@code null})
     * @param screenContext optional screen context (may be {@code null})
     * @return the reply text with tool tags
     */
    String generateChatReply(String message, List<ChatMessage> history, String screenContext);

    /**
     * Generate a short proactive "thought bubble" comment.
     *
     * @param prompt       context prompt describing what the user is doing
     * @param screenContext optional screen context
     * @param currentApp    the foreground app, if known
     * @return the thought text with an expression tag
     */
    String generateThought(String prompt, String screenContext, String currentApp);
}
