package com.resurgent.tev.parser.enrichment;

import de.entwicklertraining.api.base.ApiClientSettings;
import de.entwicklertraining.api.base.ApiHttpConfiguration;
import de.entwicklertraining.openrouter4j.chat.completion.OpenRouterChatCompletionResponse;
import java.io.IOException;
import java.net.URI;

/**
 * Live enrichment transport via the OpenRouter Java SDK
 * ({@code openrouter.chat().completion().model(...).addMessage(...).execute()}).
 */
public final class OpenRouterEnrichmentClient implements EnrichmentModelClient {

    @Override
    public String generate(EnrichmentModelRequest request) throws IOException {
        ApiHttpConfiguration.Builder httpConfig = ApiHttpConfiguration.builder()
                .header("Authorization", "Bearer " + request.apiKey());
        if (request.httpReferer() != null && !request.httpReferer().isBlank()) {
            httpConfig.header("HTTP-Referer", request.httpReferer());
        }
        if (request.appTitle() != null && !request.appTitle().isBlank()) {
            httpConfig.header("X-OpenRouter-Title", request.appTitle());
        }
        de.entwicklertraining.openrouter4j.OpenRouterClient sdk =
                new de.entwicklertraining.openrouter4j.OpenRouterClient(
                        ApiClientSettings.builder().build(),
                        httpConfig.build(),
                        baseUrl(request.endpoint()));
        try {
            OpenRouterChatCompletionResponse response = sdk.chat().completion()
                    .model(request.modelId())
                    .maxOutputTokens(request.maxOutputTokens())
                    .addMessage("user", request.prompt())
                    .responseMimeType("application/json")
                    .execute();
            String content = response.assistantMessage();
            if (content == null || content.isBlank()) {
                throw new IOException("OpenRouter response did not contain JSON content");
            }
            return content;
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException(
                    "OpenRouter chat completion failed: " + exception.getMessage(), exception);
        }
    }

    static String baseUrl(URI endpoint) {
        String path = endpoint.getPath();
        String suffix = "/chat/completions";
        if (path != null && path.endsWith(suffix)) {
            String basePath = path.substring(0, path.length() - suffix.length());
            String port = endpoint.getPort() > 0 ? ":" + endpoint.getPort() : "";
            return endpoint.getScheme() + "://" + endpoint.getHost() + port + basePath;
        }
        return "https://openrouter.ai/api/v1";
    }
}
