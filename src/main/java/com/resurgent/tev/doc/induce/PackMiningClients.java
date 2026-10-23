package com.resurgent.tev.doc.induce;

import com.resurgent.tev.doc.embed.EmbeddingClients;
import com.resurgent.tev.doc.llm.DocLlmClient;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Chooses the pack miner. Web search runs only when {@code TEV_DATA_RESIDENCY}
 * is {@code cloud_allowed} and {@code TEV_WEB_SEARCH_URL} is set. {@code local_only}
 * or a missing endpoint stays on the deterministic offline miner. There is no
 * {@code mandate_policy} table on this branch.
 */
public final class PackMiningClients {

    /** Optional search endpoint. Absent means the offline miner. */
    public static final String WEB_SEARCH_URL = "TEV_WEB_SEARCH_URL";

    private PackMiningClients() {}

    /**
     * True when this environment may send a sanitized query off-box.
     *
     * @param env process or test environment; null is treated as empty
     */
    public static boolean webSearchAllowed(Map<String, String> env) {
        if (env == null || localOnly(env)) {
            return false;
        }
        String residency = env.get(EmbeddingClients.RESIDENCY);
        if (residency == null || !residency.trim().equalsIgnoreCase("cloud_allowed")) {
            return false;
        }
        String url = env.get(WEB_SEARCH_URL);
        return url != null && !url.isBlank();
    }

    /**
     * Resolves a miner. {@code local_only} never calls the cloud model or the web.
     *
     * @param env process or test environment; null is treated as empty
     * @param llm live model, or null when none is configured
     * @param web search adapter; ignored unless {@link #webSearchAllowed} is true
     * @param usageSink receives LLM usage (may be null)
     */
    public static PackMiningClient select(
            Map<String, String> env,
            DocLlmClient llm,
            WebSearchClient web,
            Consumer<DocLlmClient.Usage> usageSink) {
        Map<String, String> values = env == null ? Map.of() : env;
        if (llm != null && !localOnly(values)) {
            WebSearchClient outbound = webSearchAllowed(values) ? web : null;
            return new LlmPackMiningClient(llm, usageSink, outbound, values);
        }
        return new FixturePackMiningClient();
    }

    private static boolean localOnly(Map<String, String> env) {
        String residency = env.get(EmbeddingClients.RESIDENCY);
        return residency != null && residency.trim().equalsIgnoreCase("local_only");
    }
}
