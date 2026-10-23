package com.resurgent.tev.doc.embed;

import java.util.Map;

/**
 * Chooses the embedding client. The local vectoriser is the default. The HTTP
 * adapter is used only when {@code DOC_EMBEDDING_BASE_URL} is set and the caller
 * is not {@code local_only} ({@code TEV_DATA_RESIDENCY}). There is no
 * {@code mandate_policy} table here; that gate belongs to a later ticket.
 */
public final class EmbeddingClients {

    /** Optional OpenAI-compatible embeddings origin. */
    public static final String ENDPOINT = "DOC_EMBEDDING_BASE_URL";

    /** Optional model id for the HTTP adapter. */
    public static final String MODEL = "DOC_EMBEDDING_MODEL";

    /** Optional bearer token. Falls back to {@code OPENROUTER_API_KEY}. */
    public static final String API_KEY = "DOC_EMBEDDING_API_KEY";

    /** Process-wide residency floor. {@code local_only} forces the local vectoriser. */
    public static final String RESIDENCY = "TEV_DATA_RESIDENCY";

    private static final String FALLBACK_KEY = "OPENROUTER_API_KEY";
    private static final String DEFAULT_HTTP_MODEL = "text-embedding-3-small";

    private EmbeddingClients() {}

    /**
     * Client for assessment and similarity. Reads process environment. Unset
     * endpoint, or {@code local_only}, stays on the local vectoriser.
     */
    public static EmbeddingClient forAssessment() {
        return select(System.getenv(), null);
    }

    /**
     * Resolves a client from an explicit environment map.
     *
     * @param env process or test environment; null is treated as empty
     * @param transport HTTP stub, or null for the JDK client when HTTP is selected
     * @return local vectoriser unless an endpoint is configured and residency allows it
     */
    public static EmbeddingClient select(Map<String, String> env, EmbeddingTransport transport) {
        Map<String, String> values = env == null ? Map.of() : env;
        String endpoint = blankToNull(values.get(ENDPOINT));
        if (endpoint == null || localOnly(values)) {
            return new LocalHashEmbeddingClient();
        }
        String model = blankToNull(values.get(MODEL));
        if (model == null) {
            model = DEFAULT_HTTP_MODEL;
        }
        String key = blankToNull(values.get(API_KEY));
        if (key == null) {
            key = blankToNull(values.get(FALLBACK_KEY));
        }
        EmbeddingTransport http = transport == null ? new JdkEmbeddingTransport() : transport;
        return new HttpEmbeddingClient(endpoint, model, key == null ? "" : key, 60_000L, http);
    }

    private static boolean localOnly(Map<String, String> env) {
        String residency = env.get(RESIDENCY);
        return residency != null && residency.trim().equalsIgnoreCase("local_only");
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
