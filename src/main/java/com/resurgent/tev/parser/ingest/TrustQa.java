package com.resurgent.tev.parser.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * QA checks for trust projections: automatic eligibility cannot coexist with a
 * failed gate, and analyst trust requires an exact accepted fingerprint.
 */
final class TrustQa {

    private TrustQa() {}

    static List<String> reasons(List<TrustEvaluator.Verdict> verdicts) {
        List<String> reasons = new ArrayList<>();
        for (TrustEvaluator.Verdict verdict : verdicts) {
            if (verdict.automatic() && verdict.gates().stream().anyMatch(gate -> !gate.passed())) {
                reasons.add("automatic_trust_failed_gate");
            }
        }
        return List.copyOf(reasons);
    }

    static List<String> reportReasons(List<CostHeadTrust> reports) {
        List<String> reasons = new ArrayList<>();
        for (CostHeadTrust report : reports) {
            if ("automatic".equals(report.source())
                    && report.gates().stream().anyMatch(gate -> !gate.passed())) {
                reasons.add("automatic_trust_failed_gate");
            }
        }
        return List.copyOf(reasons);
    }
}
