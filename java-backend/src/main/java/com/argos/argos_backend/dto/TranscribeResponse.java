package com.argos.argos_backend.dto;

/**
 * Speech-to-text result for {@code POST /api/transcribe}.
 *
 * @param text       the transcribed text (empty when no audio/STT is available)
 * @param confidence transcription confidence in the range 0.0 - 1.0
 * @param success    whether transcription produced usable text
 * @param source     {@code stt} for a live provider, {@code simulation} offline
 */
public record TranscribeResponse(
        String text,
        double confidence,
        boolean success,
        String source
) {

    public static TranscribeResponse of(String text, double confidence, String source) {
        boolean ok = text != null && !text.isBlank();
        return new TranscribeResponse(text == null ? "" : text, confidence, ok, source);
    }
}
