package com.resurgent.tev.parser.ingest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class SemanticReporter {

    private SemanticReporter() {}

    static SemanticReport build(SemanticFacts facts, List<CostHeadTrust> costHeads) {
        Set<String> observed = new TreeSet<>(facts.observedCodes());
        List<String> unobserved = new ArrayList<>();
        for (String code : CostHeadVocabulary.codes()) {
            if (!observed.contains(code)) {
                unobserved.add(code);
            }
        }
        Map<String, Integer> totals = new LinkedHashMap<>();
        totals.put("candidate", 0);
        totals.put("trusted", 0);
        totals.put("stale", 0);
        for (CostHeadTrust head : costHeads) {
            String state = head.state() == null ? "candidate" : head.state();
            totals.merge(state, 1, Integer::sum);
        }
        Set<String> blockers = new TreeSet<>();
        for (CostHeadTrust head : costHeads) {
            for (TrustEvaluator.Gate gate : head.gates()) {
                if (!gate.passed()) {
                    blockers.add(gate.name());
                }
            }
            for (String reason : head.reasons()) {
                if (reason.contains("OVERLAP")
                        || reason.contains("UNRESOLVED_DUPLICATE")
                        || reason.contains("UNKNOWN")
                        || reason.contains("PERIOD_NON_ADDITIVE")
                        || reason.endsWith("_FAIL")) {
                    blockers.add(reason);
                }
            }
        }
        return new SemanticReport(
                List.copyOf(observed),
                List.copyOf(unobserved),
                facts.mappingsExact(),
                facts.mappingsPending(),
                facts.mappingsCarried(),
                Map.copyOf(totals),
                facts.bases(),
                List.copyOf(blockers),
                facts.unitCurrencyUnknowns(),
                facts.scratch(),
                facts.support(),
                facts.orphan(),
                facts.promotions(),
                facts.duplicatesProposed(),
                facts.duplicatesDuplicate(),
                facts.duplicatesDistinct());
    }
}
