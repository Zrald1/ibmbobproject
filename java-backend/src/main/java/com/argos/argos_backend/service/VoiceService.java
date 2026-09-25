package com.argos.argos_backend.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import com.argos.argos_backend.config.AiProperties;
import com.argos.argos_backend.dto.ChatMessage;
import com.argos.argos_backend.dto.ChatRequest;
import com.argos.argos_backend.dto.ChatResponse;
import com.argos.argos_backend.dto.TranscribeResponse;

/**
 * Voice pipeline: audio transcription followed by a full chat turn.
 *
 * <p>When an STT key is configured, transcription calls an OpenAI-compatible
 * {@code /audio/transcriptions} endpoint; otherwise it degrades gracefully to an
 * empty transcript so the pipeline still returns a friendly, valid response
 * instead of failing.</p>
 */
@Service
public class VoiceService {

    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);

    private static final Pattern TEXT_FIELD = Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern ROLE_FIELD = Pattern.compile("\"role\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern CONTENT_FIELD = Pattern.compile("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final AiProperties properties;
    private final ChatService chatService;
    private final RestClient restClient;

    public VoiceService(AiProperties properties, ChatService chatService, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.chatService = chatService;
        this.restClient = restClientBuilder.clone().build();
    }

    /**
     * Transcribe an uploaded audio file.
     *
     * @param file the multipart audio part (may be {@code null} or empty)
     * @return the transcription result; empty text with {@code success=false}
     *         when no live STT provider is configured
     */
    public TranscribeResponse transcribe(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return TranscribeResponse.of("", 0.0, "simulation");
        }

        AiProperties.Stt stt = properties.getStt();
        if (!stt.hasKey()) {
            // No STT provider configured: honest empty transcript, still HTTP 200.
            log.debug("No STT key configured; returning empty transcript");
            return TranscribeResponse.of("", 0.0, "simulation");
        }

        try {
            byte[] bytes = file.getBytes();
            String filename = file.getOriginalFilename() == null ? "audio.wav" : file.getOriginalFilename();

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new NamedByteArrayResource(bytes, filename));
            body.add("model", (stt.getModel() == null || stt.getModel().isBlank()) ? "whisper-1" : stt.getModel());

            String base = (stt.getApiBase() == null || stt.getApiBase().isBlank())
                    ? "https://api.openai.com/v1" : stt.getApiBase().replaceAll("/+$", "");

            String raw = restClient.post()
                    .uri(base + "/audio/transcriptions")
                    .header("Authorization", "Bearer " + stt.getApiKey())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            String text = extractText(raw);
            return TranscribeResponse.of(text, text.isBlank() ? 0.0 : 0.9, "stt");
        } catch (IOException e) {
            log.warn("Could not read uploaded audio: {}", e.getMessage());
            return TranscribeResponse.of("", 0.0, "simulation");
        } catch (Exception e) {
            log.warn("STT provider failed: {}", e.getMessage());
            return TranscribeResponse.of("", 0.0, "simulation");
        }
    }

    /**
     * Full voice pipeline: transcribe, then run a chat turn on the transcript.
     */
    public ChatResponse processVoice(MultipartFile audio, String history, String screenContext,
                                     String model, String deviceId, String headerModel) {
        TranscribeResponse transcription = transcribe(audio);
        String text = transcription.text();

        String effectiveModel = (model != null && !model.isBlank()) ? model : headerModel;

        if (text == null || text.isBlank()) {
            // Nothing usable was transcribed - respond politely rather than erroring.
            return ChatResponse.ok(
                    "Sorry, I didn't quite catch that. Could you say it again? [TOOL:EXPR:CONFUSED] [TOOL:HAND:SHRUG]",
                    "CONFUSED", "SHRUG",
                    effectiveModel == null ? "local-planner" : effectiveModel,
                    null, "voice", true);
        }

        ChatRequest chatRequest = new ChatRequest(
                deviceId, text, screenContext, parseHistory(history), effectiveModel);
        ChatResponse response = chatService.processChat(chatRequest, headerModel);
        response.setSource("voice");
        return response;
    }

    /** Best-effort parse of a JSON history array into {@link ChatMessage}s. */
    List<ChatMessage> parseHistory(String historyJson) {
        List<ChatMessage> messages = new ArrayList<>();
        if (historyJson == null || historyJson.isBlank()) {
            return messages;
        }
        Matcher obj = JSON_OBJECT.matcher(historyJson);
        while (obj.find()) {
            String chunk = obj.group();
            String role = firstGroup(ROLE_FIELD, chunk);
            String content = firstGroup(CONTENT_FIELD, chunk);
            if (role != null && content != null) {
                messages.add(new ChatMessage(unescape(role), unescape(content)));
            }
        }
        return messages;
    }

    private static String firstGroup(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(1) : null;
    }

    private static String extractText(String raw) {
        if (raw == null) {
            return "";
        }
        String value = firstGroup(TEXT_FIELD, raw);
        return value == null ? "" : unescape(value);
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\\", "\\");
    }

    /** {@link ByteArrayResource} that also reports a filename for multipart uploads. */
    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        NamedByteArrayResource(byte[] byteArray, String filename) {
            super(byteArray);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
