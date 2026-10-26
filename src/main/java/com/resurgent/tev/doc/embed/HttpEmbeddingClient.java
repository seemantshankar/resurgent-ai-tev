package com.resurgent.tev.doc.embed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.Objects;

/**
 * OpenAI-compatible {@code POST {base}/v1/embeddings} adapter. The wire format
 * stays in this class. Callers only see {@link EmbeddingClient}.
 */
public final class HttpEmbeddingClient implements EmbeddingClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String model;
    private final String apiKey;
    private final URI uri;
    private final long timeoutMs;
    private final EmbeddingTransport transport;
    private int lastDims;

    /**
     * @param baseUrl origin or full {@code /v1/embeddings} URL (required)
     * @param model model id sent in the request (required)
     * @param apiKey bearer token; blank when the endpoint does not require one
     * @param timeoutMs per-call deadline
     * @param transport HTTP boundary (required)
     */
    public HttpEmbeddingClient(String baseUrl, String model, String apiKey, long timeoutMs,
            EmbeddingTransport transport) {
        com.resurgent.tev.doc.platform.CloudClientConstruction.constructed("HttpEmbeddingClient");
        this.model = Objects.requireNonNull(model, "model");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.uri = embeddingsUri(baseUrl);
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 60_000L;
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /** Resolves {@code {base}/v1/embeddings}, leaving a URL that already ends there alone. */
    static URI embeddingsUri(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("embeddings endpoint is required");
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/v1/embeddings")) {
            return URI.create(trimmed);
        }
        if (trimmed.endsWith("/v1")) {
            return URI.create(trimmed + "/embeddings");
        }
        return URI.create(trimmed + "/v1/embeddings");
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public int dims() {
        return lastDims;
    }

    @Override
    public float[] embed(String text) {
        String body = requestBody(text);
        String response = transport.post(uri, apiKey, body, timeoutMs);
        float[] vector = parse(response);
        EmbeddingVectors.l2(vector);
        lastDims = vector.length;
        return vector;
    }

    private String requestBody(String text) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("input", text == null ? "" : text);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new EmbeddingException("cannot serialize embeddings request: " + e.getMessage(), e);
        }
    }

    private float[] parse(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new EmbeddingException("embeddings reply was not JSON", e);
        }
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new EmbeddingException("embeddings reply had no data");
        }
        JsonNode embedding = data.get(0).path("embedding");
        if (!embedding.isArray() || embedding.isEmpty()) {
            throw new EmbeddingException("embeddings reply had no embedding array");
        }
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = (float) embedding.get(i).asDouble();
        }
        return vector;
    }
}
