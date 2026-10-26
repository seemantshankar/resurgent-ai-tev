package com.resurgent.tev.doc.embed;

import com.resurgent.tev.doc.db.MandatePolicyRepository;
import com.resurgent.tev.doc.residency.DataResidency;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/**
 * Chooses the embedding client. The local vectoriser is the default. The HTTP
 * adapter is used only when {@code DOC_EMBEDDING_BASE_URL} is set and neither
 * the process floor ({@code TEV_DATA_RESIDENCY}) nor the mandate's
 * {@code mandate_policy.data_residency} is {@code local_only}.
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
     * Same as {@link #forAssessment()} but a mandate stored as {@code local_only}
     * forces the local vectoriser even when the process floor is unset.
     */
    public static EmbeddingClient forAssessment(Connection connection, long mandateId)
            throws SQLException {
        return forAssessment(connection, mandateId, null);
    }

    /**
     * Same as {@link #forAssessment(Connection, long)}. {@code platform} is the
     * registry the command was given, so an unset mandate ceiling inherits the
     * platform default. An embedding call has no page price, so the estimate
     * is zero and a ceiling already reached still skips the remote client.
     */
    public static EmbeddingClient forAssessment(Connection connection, long mandateId,
            java.nio.file.Path platform) throws SQLException {
        String row = new MandatePolicyRepository(connection).dataResidency(mandateId);
        if (com.resurgent.tev.doc.platform.SpendCeiling.blocks(connection, mandateId, platform, 0)) {
            return new LocalHashEmbeddingClient();
        }
        return select(System.getenv(), null, row);
    }

    /**
     * Resolves a client from an explicit environment map.
     *
     * @param env process or test environment; null is treated as empty
     * @param transport HTTP stub, or null for the JDK client when HTTP is selected
     * @return local vectoriser unless an endpoint is configured and residency allows it
     */
    public static EmbeddingClient select(Map<String, String> env, EmbeddingTransport transport) {
        return select(env, transport, null);
    }

    /**
     * @param mandateResidency {@code mandate_policy.data_residency}, or null when unset
     */
    public static EmbeddingClient select(Map<String, String> env, EmbeddingTransport transport,
            String mandateResidency) {
        Map<String, String> values = env == null ? Map.of() : env;
        String endpoint = blankToNull(values.get(ENDPOINT));
        if (endpoint == null || DataResidency.isLocalOnly(
                DataResidency.effective(values.get(RESIDENCY), mandateResidency))) {
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

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
