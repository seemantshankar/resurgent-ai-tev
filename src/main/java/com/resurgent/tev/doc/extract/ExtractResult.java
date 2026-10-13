package com.resurgent.tev.doc.extract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/**
 * Adapter output for one schema-guided extract call. Raw payload is retained for audit.
 */
public record ExtractResult(
        String extractorId,
        JsonNode payload,
        List<FactCandidate> facts,
        List<Citation> citations,
        ExtractStats stats) {

    public ExtractResult {
        Objects.requireNonNull(extractorId, "extractorId");
        facts = facts == null ? List.of() : List.copyOf(facts);
        citations = citations == null ? List.of() : List.copyOf(citations);
        stats = stats == null ? ExtractStats.empty() : stats;
    }
}
