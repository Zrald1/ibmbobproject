package com.argos.argos_backend.config;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code argos.ai.*} configuration tree.
 *
 * <p>Every provider key/base-url defaults to blank so the backend boots and runs
 * in offline simulation mode out-of-the-box; supplying a key switches that
 * provider to live upstream calls with automatic cascade fallback.</p>
 */
@Component
@ConfigurationProperties(prefix = "argos.ai")
public class AiProperties {

    /** Wire id of the model used when a request does not name one. */
    private String defaultModel = "gpt-6-astra";

    /** Per-call connect/read timeout for upstream model requests, in millis. */
    private long requestTimeoutMs = 8000;

    /** Provider credentials keyed by model id (e.g. {@code gpt-6-astra}). */
    private Map<String, Provider> providers = new HashMap<>();

    /** Speech-to-text configuration. */
    private Stt stt = new Stt();

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        this.providers = providers;
    }

    public Stt getStt() {
        return stt;
    }

    public void setStt(Stt stt) {
        this.stt = stt;
    }

    /** Resolve provider config for a model id, or an empty (offline) provider. */
    public Provider providerFor(String modelId) {
        Provider p = providers.get(modelId);
        return p == null ? new Provider() : p;
    }

    public static class Provider {
        private String apiKey = "";
        private String apiBase = "";
        private String model = "";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getApiBase() {
            return apiBase;
        }

        public void setApiBase(String apiBase) {
            this.apiBase = apiBase;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public boolean hasKey() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public static class Stt {
        private String apiKey = "";
        private String apiBase = "";
        private String model = "";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getApiBase() {
            return apiBase;
        }

        public void setApiBase(String apiBase) {
            this.apiBase = apiBase;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public boolean hasKey() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
