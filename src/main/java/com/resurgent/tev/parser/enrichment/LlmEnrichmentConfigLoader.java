package com.resurgent.tev.parser.enrichment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.parser.config.ConfigValidationException;
import com.resurgent.tev.parser.config.DotEnv;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Loads enrichment model settings from optional config JSON and a {@code .env} file. */
public final class LlmEnrichmentConfigLoader {

    public static final URI DEFAULT_OPENROUTER_ENDPOINT =
            URI.create("https://openrouter.ai/api/v1/chat/completions");
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 32_768;
    private static final String DEFAULT_APP_TITLE = "tev-parse";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmEnrichmentConfigLoader() {}

    public static LlmEnrichmentConfig load(Path configPath) throws IOException {
        return load(configPath, Path.of(".env"));
    }

    public static LlmEnrichmentConfig load(Path configPath, Path envPath) throws IOException {
        Map<String, Object> config = Map.of();
        if (configPath != null) {
            config = MAPPER.readValue(Files.readString(configPath), new TypeReference<>() {});
        }
        return load(config, DotEnv.load(envPath));
    }

    public static LlmEnrichmentConfig load(String json) throws IOException {
        Map<String, Object> config = json == null || json.isBlank()
                ? Map.of()
                : MAPPER.readValue(json, new TypeReference<>() {});
        return load(config, Map.of());
    }

    public static LlmEnrichmentConfig load(Map<String, Object> config, Map<String, String> dotenv)
            throws IOException {
        try {
            String apiKey = firstNonBlank(
                    text(config, "llmApiKey"),
                    System.getenv("OPENROUTER_API_KEY"),
                    System.getenv("LLM_API_KEY"),
                    dotenv.get("OPENROUTER_API_KEY"),
                    dotenv.get("LLM_API_KEY"));
            String modelId = firstNonBlank(
                    System.getenv("Excel_Enrichment_Model_id"),
                    System.getenv("OPENROUTER_MODEL_ID"),
                    System.getenv("LLM_MODEL_ID"),
                    dotenv.get("Excel_Enrichment_Model_id"),
                    dotenv.get("OPENROUTER_MODEL_ID"),
                    dotenv.get("LLM_MODEL_ID"));
            String endpoint = firstNonBlank(
                    text(config, "llmEndpoint"),
                    System.getenv("OPENROUTER_ENDPOINT"),
                    System.getenv("LLM_ENDPOINT"),
                    dotenv.get("OPENROUTER_ENDPOINT"),
                    dotenv.get("LLM_ENDPOINT"),
                    DEFAULT_OPENROUTER_ENDPOINT.toString());
            String httpReferer = firstNonBlank(
                    text(config, "llmHttpReferer"),
                    System.getenv("OPENROUTER_HTTP_REFERER"),
                    dotenv.get("OPENROUTER_HTTP_REFERER"));
            String appTitle = firstNonBlank(
                    text(config, "llmAppTitle"),
                    System.getenv("OPENROUTER_APP_TITLE"),
                    dotenv.get("OPENROUTER_APP_TITLE"),
                    DEFAULT_APP_TITLE);
            int maxOutputTokens = parseMaxOutputTokens(config, dotenv);
            if (apiKey == null || apiKey.isBlank()) {
                throw new ConfigValidationException(
                        "LLM credentials required: set OPENROUTER_API_KEY in .env or llmApiKey in --config");
            }
            if (modelId == null || modelId.isBlank()) {
                throw new ConfigValidationException(
                        "LLM model required: set Excel_Enrichment_Model_id in .env");
            }
            return new LlmEnrichmentConfig(
                    apiKey, modelId, URI.create(endpoint), httpReferer, appTitle, maxOutputTokens);
        } catch (ClassCastException | IllegalArgumentException e) {
            throw new ConfigValidationException("invalid LLM enrichment config: " + e.getMessage());
        }
    }

    private static int parseMaxOutputTokens(Map<String, Object> config, Map<String, String> dotenv) {
        Object configValue = config.get("llmMaxOutputTokens");
        if (configValue instanceof Number number) {
            return number.intValue();
        }
        String raw = firstNonBlank(
                configValue == null ? null : configValue.toString(),
                System.getenv("OPENROUTER_MAX_OUTPUT_TOKENS"),
                dotenv.get("OPENROUTER_MAX_OUTPUT_TOKENS"));
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MAX_OUTPUT_TOKENS;
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "OPENROUTER_MAX_OUTPUT_TOKENS must be an integer: " + raw);
        }
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : (String) value;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
