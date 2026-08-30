package com.resurgent.tev.parser.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Optional live transport for OpenAI-compatible chat-completions endpoints. */
public final class OpenAiChatCompletionsClient implements EnrichmentModelClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient httpClient;

    public OpenAiChatCompletionsClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build());
    }

    OpenAiChatCompletionsClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String generate(EnrichmentModelRequest request)
            throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", request.modelId());
        ObjectNode message = body.putArray("messages").addObject();
        message.put("role", "user");
        message.put("content", request.prompt());
        body.putObject("response_format").put("type", "json_object");

        HttpRequest httpRequest = HttpRequest.newBuilder(request.endpoint())
                .timeout(Duration.ofMinutes(5))
                .header("Authorization", "Bearer " + request.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = httpClient.send(
                httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(
                    "model endpoint returned HTTP " + response.statusCode());
        }
        JsonNode content = MAPPER.readTree(response.body())
                .path("choices")
                .path(0)
                .path("message")
                .path("content");
        if (!content.isTextual() || content.textValue().isBlank()) {
            throw new IOException("model response did not contain JSON content");
        }
        return content.textValue();
    }
}
