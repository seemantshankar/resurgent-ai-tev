package com.resurgent.tev.parser.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class OpenRouterEnrichmentClientTest {

    @Test
    void derivesSdkBaseUrlFromChatCompletionsEndpoint() {
        assertThat(OpenRouterEnrichmentClient.baseUrl(
                        URI.create("https://openrouter.ai/api/v1/chat/completions")))
                .isEqualTo("https://openrouter.ai/api/v1");
        assertThat(OpenRouterEnrichmentClient.baseUrl(
                        URI.create("https://example.invalid/v1/chat/completions")))
                .isEqualTo("https://example.invalid/v1");
    }

    @Test
    void fallsBackToDefaultBaseUrlForUnknownEndpointShape() {
        assertThat(OpenRouterEnrichmentClient.baseUrl(URI.create("https://openrouter.ai/api/v1")))
                .isEqualTo("https://openrouter.ai/api/v1");
    }
}
