package com.argos.argos_backend.ai;

public enum AiModel {
    GPT_6_ASTRA("gpt-6-astra", "GPT-6 Astra", "Advanced reasoning and deep tool orchestration engine"),
    GEMINI_3_8_FLASH("gemini-3.8-flash", "Gemini 3.8 Flash", "Ultra low-latency multimodal dialogue and real-time screen reactivity"),
    FABLE_5_1("fable-5.1", "Fable 5.1", "Expressive character intelligence with rich emotional sequencing");

    private final String id;
    private final String displayName;
    private final String description;

    AiModel(String id, String displayName, String description) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public static AiModel fromString(String text) {
        if (text == null || text.isBlank()) {
            return GEMINI_3_8_FLASH;
        }
        String clean = text.trim().toLowerCase();
        for (AiModel model : values()) {
            if (model.id.equalsIgnoreCase(clean) || model.name().equalsIgnoreCase(clean)) {
                return model;
            }
        }
        if (clean.contains("astra") || clean.contains("gpt")) {
            return GPT_6_ASTRA;
        }
        if (clean.contains("gemini") || clean.contains("flash")) {
            return GEMINI_3_8_FLASH;
        }
        if (clean.contains("fable")) {
            return FABLE_5_1;
        }
        return GEMINI_3_8_FLASH;
    }
}
