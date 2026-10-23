package com.resurgent.tev.doc.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Page-backed extractor. It does not call a model and does not upload a file.
 * {@link ExtractService} fills fields, schedules, clauses, and passages from the
 * assembled per-page markdown and {@code table_grid} rows.
 */
public final class DocLlmExtractClient implements ExtractClient {

    /** Adapter id stored on {@code fact.extractor_id}. */
    public static final String ID = "doc-llm-extract";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ExtractResult extractFieldSchema(ExtractRequest req, JsonNode schema) {
        return new ExtractResult(ID, MAPPER.createObjectNode(), null, null, null);
    }
}
