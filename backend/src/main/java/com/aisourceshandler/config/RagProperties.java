package com.aisourceshandler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
        boolean enabled,
        Embedding embedding,
        Chroma chroma,
        int chunkChars,
        int chunkOverlap,
        int defaultTopK
) {
    public record Embedding(String apiKey, String baseUrl, String model, int dimensions) {
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank();
        }
    }

    public record Chroma(String baseUrl, String tenant, String database, String collection) {}
}
