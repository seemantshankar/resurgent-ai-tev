package com.resurgent.tev.doc.induce;

import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.residency.DataResidency;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Chooses the pack miner. Web search runs when {@code TEV_WEB_SEARCH_URL} is
 * set and residency allows it. Unset {@code TEV_DATA_RESIDENCY} is not a lock:
 * the default is {@code cloud_allowed}. {@code local_only} on the process floor
 * or on {@code mandate_policy} keeps mining on the offline client.
 */
public final class PackMiningClients {

    /** Optional search endpoint. Absent means no web search. */
    public static final String WEB_SEARCH_URL = "TEV_WEB_SEARCH_URL";

    private PackMiningClients() {}

    /**
     * True when this environment may send a sanitized query off-box.
     *
     * @param env process or test environment; null is treated as empty
     */
    public static boolean webSearchAllowed(Map<String, String> env) {
        return webSearchAllowed(env, null);
    }

    /**
     * @param mandateResidency {@code mandate_policy.data_residency}, or null when unset
     */
    public static boolean webSearchAllowed(Map<String, String> env, String mandateResidency) {
        Map<String, String> values = env == null ? Map.of() : env;
        String url = values.get(WEB_SEARCH_URL);
        if (url == null || url.isBlank()) {
            return false;
        }
        return !DataResidency.isLocalOnly(effective(values, mandateResidency));
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
        return select(env, llm, web, usageSink, null);
    }

    /**
     * @param mandateResidency {@code mandate_policy.data_residency}, or null when unset
     */
    public static PackMiningClient select(
            Map<String, String> env,
            DocLlmClient llm,
            WebSearchClient web,
            Consumer<DocLlmClient.Usage> usageSink,
            String mandateResidency) {
        Map<String, String> values = env == null ? Map.of() : env;
        if (llm != null && !DataResidency.isLocalOnly(effective(values, mandateResidency))) {
            WebSearchClient outbound = webSearchAllowed(values, mandateResidency) ? web : null;
            return new LlmPackMiningClient(llm, usageSink, outbound, values);
        }
        return new OfflinePackMiningClient();
    }

    private static String effective(Map<String, String> env, String mandateResidency) {
        return DataResidency.effective(env.get(DataResidency.ENV), mandateResidency);
    }
}
