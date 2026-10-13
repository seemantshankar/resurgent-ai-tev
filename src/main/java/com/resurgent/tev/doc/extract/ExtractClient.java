package com.resurgent.tev.doc.extract;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * SPI for schema-guided document field extraction (Architecture §7.6).
 * Domain code never sees vendor types; adapters own their wire format.
 *
 * <p>{@code extractOpen} / {@code extractLayout} are reserved for M3/M5 and intentionally
 * omitted so M2 compiles without stubs.
 */
public interface ExtractClient {

    /** Stable adapter id stored on {@code fact.extractor_id}, e.g. {@code llama-extract-2.5-agentic}. */
    String id();

    /**
     * Extracts fields described by a compiled pack schema.
     *
     * @param req document and page scope
     * @param schema JSON Schema fragment from {@code DocTypePack.toExtractSchema()}
     * @return mapped candidates, citations, and stats
     * @throws ExtractException on timeout, HTTP failure, or malformed payload
     */
    ExtractResult extractFieldSchema(ExtractRequest req, JsonNode schema) throws ExtractException;
}
