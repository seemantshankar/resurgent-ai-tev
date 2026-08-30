package com.resurgent.tev.parser.enrichment;

import java.net.URI;

/** Credentials and model selection for the external enrichment model. */
public record LlmEnrichmentConfig(String apiKey, String modelId, URI endpoint) {

    public LlmEnrichmentConfig {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("llmApiKey is required");
        }
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("llmModelId is required");
        }
        if (endpoint == null) {
            throw new IllegalArgumentException("llmEndpoint is required");
        }
    }
}
