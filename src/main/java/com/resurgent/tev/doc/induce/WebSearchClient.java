package com.resurgent.tev.doc.induce;

/**
 * Outbound web search used to ground pack induction. The query is already
 * sanitized; implementations must send it unchanged.
 */
public interface WebSearchClient {

    /** Stable adapter id. */
    default String id() {
        return "web-search";
    }

    /**
     * Runs one search for a sanitized query.
     *
     * @param sanitizedQuery generic document-type and industry terms (required)
     * @return snippets the miner may read; empty when the search returns nothing
     */
    String search(String sanitizedQuery);
}
