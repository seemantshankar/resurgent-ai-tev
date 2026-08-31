package com.resurgent.tev.parser.enrichment;

import java.net.URI;

/** Credentials and model selection for the external enrichment model. */
public record LlmEnrichmentConfig(
        String apiKey,
        String modelId,
        URI endpoint,
        String httpReferer,
        String appTitle,
        int maxOutputTokens) {

    public LlmEnrichmentConfig {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("llmApiKey is required");
        }
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("Excel_Enrichment_Model_id is required");
        }
        if (endpoint == null) {
            throw new IllegalArgumentException("llmEndpoint is required");
        }
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
    }
}
