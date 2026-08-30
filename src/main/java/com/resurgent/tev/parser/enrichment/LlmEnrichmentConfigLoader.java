package com.resurgent.tev.parser.enrichment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.parser.config.ConfigValidationException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Loads enrichment model settings from the command's shared config JSON. */
public final class LlmEnrichmentConfigLoader {

    private static final URI DEFAULT_ENDPOINT =
            URI.create("https://api.openai.com/v1/chat/completions");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmEnrichmentConfigLoader() {}

    public static LlmEnrichmentConfig load(Path path) throws IOException {
        if (path == null) {
            throw new ConfigValidationException(
                    "--config with llmApiKey and llmModelId is required for enrichment");
        }
        return load(Files.readString(path));
    }

    public static LlmEnrichmentConfig load(String json) throws IOException {
        Map<String, Object> values = MAPPER.readValue(json, new TypeReference<>() {});
        try {
            String apiKey = text(values, "llmApiKey", null);
            String modelId = text(values, "llmModelId", null);
            String endpoint = text(values, "llmEndpoint", DEFAULT_ENDPOINT.toString());
            return new LlmEnrichmentConfig(apiKey, modelId, URI.create(endpoint));
        } catch (ClassCastException | IllegalArgumentException e) {
            throw new ConfigValidationException("invalid LLM enrichment config: " + e.getMessage());
        }
    }

    private static String text(Map<String, Object> values, String key, String defaultValue) {
        Object value = values.get(key);
        return value == null ? defaultValue : (String) value;
    }
}
