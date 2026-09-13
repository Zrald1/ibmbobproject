package com.argos.argos_backend.service;

import java.util.Locale;

import org.springframework.stereotype.Service;

import com.argos.argos_backend.dto.ActionDto;
import com.argos.argos_backend.dto.ChatResponse;

@Service
public class FallbackService {

    public ChatResponse createFallbackResponse(
            String message,
            String screenContext
    ) {
        String originalMessage = message == null ? "" : message.trim();
        String normalizedMessage = originalMessage.toLowerCase(Locale.ROOT);

        if (normalizedMessage.startsWith("open ")) {
            String appName = extractAfter(originalMessage, "open ");

            if (appName.isBlank()) {
                return offlineReply("Please tell me which app you want to open. For example: open Spotify.");
            }

            return success(
                    "I am in offline assistance mode, but I can prepare to open "
                            + appName + ". Please confirm the action.",
                    new ActionDto("OPEN_APP", appName, true)
            );
        }

        if (normalizedMessage.startsWith("call ")) {
            String contactName = extractAfter(originalMessage, "call ");

            if (contactName.isBlank()) {
                return offlineReply("Please tell me whom you want to call. For example: call Mom.");
            }

            return success(
                    "I cannot reach the primary AI service, but I can prepare a call to "
                            + contactName + ". Please confirm before calling.",
                    new ActionDto("CALL_CONTACT", contactName, true)
            );
        }

        if (normalizedMessage.startsWith("message ")
                || normalizedMessage.startsWith("send sms ")
                || normalizedMessage.startsWith("send message ")) {

            return success(
                    "I am in offline assistance mode. I can help draft this message, "
                            + "but you must confirm the recipient and message before sending.",
                    new ActionDto("DRAFT_SMS", originalMessage, true)
            );
        }

        if (normalizedMessage.contains("alarm")
                || normalizedMessage.contains("remind me")
                || normalizedMessage.contains("reminder")) {

            return success(
                    "I can prepare an alarm or reminder from your instruction. "
                            + "Please confirm the time before it is created.",
                    new ActionDto("CREATE_ALARM", originalMessage, true)
            );
        }

        if (normalizedMessage.contains("settings")) {
            return success(
                    "I can prepare to open Android settings. Please confirm the action.",
                    new ActionDto("OPEN_SETTINGS", "SETTINGS", true)
            );
        }

        String contextNote = (screenContext == null || screenContext.isBlank())
                ? ""
                : " I received the screen context and can use it again when the AI service reconnects.";

        return new ChatResponse(
                true,
                "fallback",
                "The primary AI service is currently unavailable. "
                        + "ARGOS is still running in offline assistance mode. "
                        + "Try commands such as: open Spotify, call Mom, "
                        + "send a message, or set an alarm."
                        + contextNote,
                null,
                true
        );
    }

    private ChatResponse success(String reply, ActionDto action) {
        return new ChatResponse(
                true,
                "fallback",
                reply,
                action,
                true
        );
    }

    private ChatResponse offlineReply(String reply) {
        return new ChatResponse(
                true,
                "fallback",
                reply,
                null,
                true
        );
    }

    private String extractAfter(String message, String prefix) {
        if (message.length() <= prefix.length()) {
            return "";
        }

        return message.substring(prefix.length()).trim();
    }
}