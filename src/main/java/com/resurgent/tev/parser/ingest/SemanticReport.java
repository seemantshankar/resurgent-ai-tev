package com.resurgent.tev.parser.ingest;

import java.util.List;
import java.util.Map;

/**
 * Cost-head coverage report carried in {@code parse_run.metrics}. Distinguishes
 * observed/unobserved vocabulary, mapping states, candidate/trusted/stale
 * totals, bases, blockers, unit/currency unknowns, scratch promotions, and
 * duplicates. It is not a truth threshold and carries no unknown-region ratio.
 */
record SemanticReport(
        List<String> observedCodes,
        List<String> unobservedCodes,
        int mappingsExact,
        int mappingsPending,
        int mappingsCarried,
        Map<String, Integer> totalStates,
        Map<String, Integer> bases,
        List<String> blockers,
        int unitCurrencyUnknowns,
        int scratch,
        int support,
        int orphan,
        int promotions,
        int duplicatesProposed,
        int duplicatesDuplicate,
        int duplicatesDistinct) {
}
