package com.resurgent.tev.doc.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.resurgent.tev.doc.residency.DataResidency;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * OpenAI-compatible chat-completions adapter for document LLM calls (OpenRouter by
 * default). The wire format stays inside this class; domain code only sees
 * {@link DocLlmClient.Completion}. Tests fake the {@link DocLlmClient} port, never this.
 */
public final class HttpDocLlmClient implements DocLlmClient {

    /** OpenRouter's chat completions endpoint; overridable with {@code DOC_LLM_BASE_URL}. */
    public static final String DEFAULT_URL = "https://openrouter.ai/api/v1/chat/completions";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiKey;
    private final String model;
    private final URI uri;
    private final long timeoutMs;
    private final HttpClient http;

    /**
     * @param apiKey  bearer token (required)
     * @param model   model id (required)
     * @param baseUrl endpoint, or null/blank for {@link #DEFAULT_URL}
     * @param timeoutMs per-call deadline in milliseconds
     */
    public HttpDocLlmClient(String apiKey, String model, String baseUrl, long timeoutMs) {
        com.resurgent.tev.doc.platform.CloudClientConstruction.constructed("HttpDocLlmClient");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.model = Objects.requireNonNull(model, "model");
        this.uri = URI.create(baseUrl == null || baseUrl.isBlank() ? DEFAULT_URL : baseUrl.trim());
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 120_000L;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    /** The model id this client calls. */
    public String model() {
        return model;
    }

    @Override
    public String id() {
        return "openrouter";
    }

    @Override
    public Completion complete(String system, String user, int maxTokens) throws DocLlmException {
        if (DataResidency.locked()) {
            throw new DocLlmException(
                    "HttpDocLlmClient blocked: data residency is local_only");
        }
        String body = requestBody(system, user, maxTokens);
        long start = System.nanoTime();
        HttpResponse<String> response = send(body);
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new DocLlmException("LLM HTTP " + response.statusCode() + ": "
                    + shorten(response.body()));
        }
        return parse(response.body(), latencyMs);
    }

    private HttpResponse<String> send(String body) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("HTTP-Referer", "https://github.com/seemantshankar/resurgent-ai-tev")
                .header("X-OpenRouter-Title", "TEV Document Intelligence")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        CompletableFuture<HttpResponse<String>> pending =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return pending.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new DocLlmException("LLM call exceeded " + timeoutMs + "ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new DocLlmException("LLM request failed: "
                    + (cause == null ? e.getMessage() : cause.getMessage()), cause);
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new DocLlmException("LLM call interrupted", e);
        }
    }

    private String requestBody(String system, String user, int maxTokens) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("temperature", 0.0);
        root.put("max_tokens", maxTokens > 0 ? maxTokens : 2048);
        ArrayNode messages = root.putArray("messages");
        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", system == null ? "" : system);
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", user == null ? "" : user);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new DocLlmException("cannot serialize LLM request: " + e.getMessage(), e);
        }
    }

    private Completion parse(String body, long latencyMs) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new DocLlmException("LLM reply was not JSON: " + shorten(body), e);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            JsonNode error = root.path("error");
            String detail = error.isMissingNode() ? shorten(body) : error.toString();
            throw new DocLlmException("LLM reply had no choices: " + detail);
        }
        JsonNode message = choices.get(0).path("message");
        String content = message.path("content").asText("");
        if (content == null || content.isBlank()) {
            throw new DocLlmException("LLM reply was empty");
        }
        JsonNode usage = root.path("usage");
        Integer promptTokens = usage.hasNonNull("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null;
        Integer completionTokens = usage.hasNonNull("completion_tokens")
                ? usage.get("completion_tokens").asInt() : null;
        Double cost = null;
        if (usage.hasNonNull("cost")) {
            cost = usage.get("cost").asDouble();
        } else if (usage.hasNonNull("total_cost")) {
            cost = usage.get("total_cost").asDouble();
        }
        String modelId = root.path("model").asText(model);
        return new Completion(content, modelId == null || modelId.isBlank() ? model : modelId,
                promptTokens, completionTokens, cost, latencyMs);
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= 300 ? compact : compact.substring(0, 300) + "…";
    }
}
