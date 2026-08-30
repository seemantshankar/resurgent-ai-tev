package com.resurgent.tev.parser.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmEnrichmentConfigLoaderTest {

    @Test
    void loadsModelCredentialsAlongsideParserConfiguration() throws Exception {
        String json = """
                {
                  "maxSheetCount": 25,
                  "llmApiKey": "secret",
                  "llmModelId": "test-model",
                  "llmEndpoint": "https://example.invalid/chat"
                }
                """;

        LlmEnrichmentConfig config = LlmEnrichmentConfigLoader.load(json);

        assertThat(config.apiKey()).isEqualTo("secret");
        assertThat(config.modelId()).isEqualTo("test-model");
        assertThat(config.endpoint()).hasToString("https://example.invalid/chat");
        assertThat(com.resurgent.tev.parser.config.ConfigLoader.load(json).maxSheetCount())
                .isEqualTo(25);
    }
}
