package com.resurgent.tev.parser.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resurgent.tev.parser.config.ConfigValidationException;
import com.resurgent.tev.parser.config.DotEnv;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LlmEnrichmentConfigLoaderTest {

    private static final String FIXTURE_MODEL_ID = "fixture-enrichment-model";

    @TempDir
    Path tempDir;

    @Test
    void loadsApiKeyFromConfigAndModelFromDotEnv() throws Exception {
        LlmEnrichmentConfig config = LlmEnrichmentConfigLoader.load(
                Map.of(
                        "llmApiKey", "secret",
                        "llmEndpoint", "https://example.invalid/chat"),
                Map.of("Excel_Enrichment_Model_id", FIXTURE_MODEL_ID));

        assertThat(config.apiKey()).isEqualTo("secret");
        assertThat(config.modelId()).isEqualTo(FIXTURE_MODEL_ID);
        assertThat(config.endpoint()).hasToString("https://example.invalid/chat");
        assertThat(config.appTitle()).isEqualTo("tev-parse");
        assertThat(config.maxOutputTokens())
                .isEqualTo(LlmEnrichmentConfigLoader.DEFAULT_MAX_OUTPUT_TOKENS);
    }

    @Test
    void loadsMaxOutputTokensFromDotEnv() throws Exception {
        LlmEnrichmentConfig config = LlmEnrichmentConfigLoader.load(
                Map.of("llmApiKey", "secret"),
                Map.of(
                        "Excel_Enrichment_Model_id", FIXTURE_MODEL_ID,
                        "OPENROUTER_MAX_OUTPUT_TOKENS", "16384"));

        assertThat(config.maxOutputTokens()).isEqualTo(16_384);
    }

    @Test
    void loadsOpenrouterCredentialsFromDotEnv() throws Exception {
        Path env = tempDir.resolve(".env");
        Files.writeString(env, """
                OPENROUTER_API_KEY=openrouter-secret
                Excel_Enrichment_Model_id=%s
                """.formatted(FIXTURE_MODEL_ID));

        LlmEnrichmentConfig config = LlmEnrichmentConfigLoader.load(Map.of(), DotEnv.load(env));

        assertThat(config.apiKey()).isEqualTo("openrouter-secret");
        assertThat(config.modelId()).isEqualTo(FIXTURE_MODEL_ID);
        assertThat(config.endpoint()).isEqualTo(LlmEnrichmentConfigLoader.DEFAULT_OPENROUTER_ENDPOINT);
        assertThat(config.appTitle()).isEqualTo("tev-parse");
    }

    @Test
    void configJsonOverridesApiKeyButModelComesFromDotEnv() throws Exception {
        LlmEnrichmentConfig config = LlmEnrichmentConfigLoader.load(
                Map.of(
                        "llmApiKey", "config-key",
                        "llmEndpoint", "https://example.invalid/chat"),
                Map.of(
                        "OPENROUTER_API_KEY", "env-key",
                        "Excel_Enrichment_Model_id", FIXTURE_MODEL_ID));

        assertThat(config.apiKey()).isEqualTo("config-key");
        assertThat(config.modelId()).isEqualTo(FIXTURE_MODEL_ID);
        assertThat(config.endpoint()).hasToString("https://example.invalid/chat");
    }

    @Test
    void missingCredentialsFailWithActionableMessage() {
        assertThatThrownBy(() -> LlmEnrichmentConfigLoader.load(Map.of(), Map.of()))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("OPENROUTER_API_KEY");
    }

    @Test
    void missingModelFailsWithDotEnvMessage() {
        assertThatThrownBy(() -> LlmEnrichmentConfigLoader.load(
                        Map.of("llmApiKey", "secret"), Map.of()))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("Excel_Enrichment_Model_id");
    }
}
