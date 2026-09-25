package com.argos.argos_backend.ai;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The three top-tier Argos AI models exposed by the fallback backend.
 *
 * <p>Each constant carries the wire identifier used by clients (request body
 * {@code model} field or the {@code X-Argos-Model} header), a human readable
 * name, a short description and the headline capabilities advertised by
 * {@code GET /api/models}.</p>
 */
public enum AiModel {

    GPT_6_ASTRA(
            "gpt-6-astra",
            "GPT-6 Astra",
            "6.0",
            "High-reasoning OpenAI-compatible engine with deep tool chaining, "
                    + "file-tree awareness and executive multi-step planning.",
            List.of("deep-reasoning", "tool-chaining", "file-operations", "executive-planning")),

    GEMINI_3_8_FLASH(
            "gemini-3.8-flash",
            "Gemini 3.8 Flash",
            "3.8",
            "Google GenAI-compatible low-latency engine with live screen "
                    + "comprehension and reactive expression tagging.",
            List.of("low-latency", "screen-comprehension", "reactive-tags", "dialogue")),

    FABLE_5_1(
            "fable-5.1",
            "Fable 5.1",
            "5.1",
            "Fable/Anthropic-style creative intelligence with expressive "
                    + "emotional sequencing and empathetic persona dialogue.",
            List.of("creative", "emotional-sequences", "empathetic-persona", "exprseq"));

    private final String id;
    private final String displayName;
    private final String version;
    private final String description;
    private final List<String> features;

    AiModel(String id, String displayName, String version, String description, List<String> features) {
        this.id = id;
        this.displayName = displayName;
        this.version = version;
        this.description = description;
        this.features = features;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getVersion() {
        return version;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getFeatures() {
        return features;
    }

    /**
     * Resolve a model from a client supplied identifier. Matching is
     * case-insensitive and tolerates both the wire id ({@code gpt-6-astra}) and
     * the enum/display name ({@code GPT_6_ASTRA} / {@code GPT-6 Astra}).
     *
     * @param raw the raw identifier, may be {@code null} or blank
     * @return the matching model, or empty when nothing matches
     */
    public static Optional<AiModel> fromId(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '-').replace('_', '-');
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        for (AiModel model : values()) {
            String candidate = model.id.toLowerCase(Locale.ROOT);
            if (candidate.equals(normalized)
                    || candidate.replace(".", "-").equals(normalized.replace(".", "-"))
                    || model.name().toLowerCase(Locale.ROOT).replace('_', '-').equals(normalized)) {
                return Optional.of(model);
            }
        }
        // Loose contains match as a last resort (e.g. "astra", "gemini", "fable").
        for (AiModel model : values()) {
            if (normalized.contains(model.id.split("-")[0])
                    || model.displayName.toLowerCase(Locale.ROOT).contains(normalized)) {
                return Optional.of(model);
            }
        }
        return Optional.empty();
    }
}
