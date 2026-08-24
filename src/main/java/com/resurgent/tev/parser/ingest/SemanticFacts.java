package com.resurgent.tev.parser.ingest;

import java.util.List;
import java.util.Map;

/** Persisted semantic counts used by QA and the ingest coverage report. */
public record SemanticFacts(
        SemanticQaStats qa,
        List<String> observedCodes,
        int mappingsExact,
        int mappingsPending,
        int mappingsCarried,
        Map<String, Integer> bases,
        int unitCurrencyUnknowns,
        int scratch,
        int support,
        int orphan,
        int promotions,
        int duplicatesProposed,
        int duplicatesDuplicate,
        int duplicatesDistinct) {
}
