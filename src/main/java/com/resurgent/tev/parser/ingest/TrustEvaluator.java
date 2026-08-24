package com.resurgent.tev.parser.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Evaluates §8.4 automatic-trust gates and projects candidate/trusted/stale.
 */
final class TrustEvaluator {

    record Gate(String name, boolean passed) {}

    record MappingFact(String method, String reasonsJson) {}

    record Verdict(
            boolean automatic,
            String state,
            String source,
            List<Gate> gates,
            List<String> evidence) {}

    private TrustEvaluator() {}

    static Verdict evaluate(
            ExplicitAnchorDetector.Candidate candidate,
            Map<Long, MappingFact> mappings,
            boolean pendingManual,
            String latestAcceptedFingerprint) {
        boolean mapping = mappingPass(candidate, mappings);
        boolean basis = basisPass(candidate);
        boolean unitCurrency = unitCurrencyPass(candidate);
        boolean coverage = coveragePass(candidate);
        boolean cacheNumeric = cacheNumericPass(candidate);
        boolean blockers = blockersPass(candidate, pendingManual);
        List<Gate> gates = List.of(
                new Gate("mapping", mapping),
                new Gate("basis", basis),
                new Gate("unit_currency", unitCurrency),
                new Gate("coverage", coverage),
                new Gate("cache_numeric", cacheNumeric),
                new Gate("blockers", blockers));
        List<String> evidence = new ArrayList<>();
        for (Gate gate : gates) {
            evidence.add(gate.passed()
                    ? "GATE_" + gate.name().toUpperCase() + "_PASS"
                    : "GATE_" + gate.name().toUpperCase() + "_FAIL");
        }
        boolean automatic = mapping && basis && unitCurrency && coverage && cacheNumeric && blockers;
        String state;
        String source;
        if (automatic) {
            state = "trusted";
            source = "automatic";
        } else if (latestAcceptedFingerprint != null
                && latestAcceptedFingerprint.equals(candidate.fingerprint())) {
            state = "trusted";
            source = "analyst";
        } else if (latestAcceptedFingerprint != null) {
            state = "stale";
            source = "analyst";
        } else {
            state = "candidate";
            source = null;
        }
        return new Verdict(automatic, state, source, gates, List.copyOf(evidence));
    }

    private static boolean mappingPass(
            ExplicitAnchorDetector.Candidate candidate, Map<Long, MappingFact> mappings) {
        for (ExplicitAnchorDetector.Contribution contribution : candidate.contributions()) {
            if ("manual".equals(contribution.basis())) {
                continue;
            }
            MappingFact fact = mappings.get(contribution.regionId());
            if (fact == null || !trustedMapping(fact)) {
                return false;
            }
        }
        return !candidate.contributions().isEmpty();
    }

    private static boolean trustedMapping(MappingFact fact) {
        if (CostHeadMapper.CARRIED.equals(fact.method())) {
            return true;
        }
        return CostHeadMapper.EXACT_ALIAS.equals(fact.method())
                && (fact.reasonsJson() == null || !fact.reasonsJson().contains("AMBIGUOUS_EXACT_ALIAS"));
    }

    private static boolean basisPass(ExplicitAnchorDetector.Candidate candidate) {
        if (candidate.contributions().isEmpty()) {
            return false;
        }
        for (ExplicitAnchorDetector.Contribution contribution : candidate.contributions()) {
            if (!ExplicitAnchorDetector.EXPLICIT.equals(contribution.basis())
                    && !ExplicitAnchorDetector.STRUCTURAL.equals(contribution.basis())) {
                return false;
            }
            if (contribution.reasons().contains("STRUCTURAL_AMBIGUOUS")
                    || contribution.reasons().contains("STRUCTURAL_AMOUNT_MISMATCH")) {
                return false;
            }
        }
        return true;
    }

    private static boolean unitCurrencyPass(ExplicitAnchorDetector.Candidate candidate) {
        if (!known(candidate.unit()) || !known(candidate.currency())) {
            return false;
        }
        for (ExplicitAnchorDetector.Contribution contribution : candidate.contributions()) {
            if ("manual".equals(contribution.basis())) {
                continue;
            }
            if (!known(contribution.sourceUnit()) || !known(contribution.sourceCurrency())) {
                return false;
            }
            if (contribution.reasons().contains("UNIT")
                    || contribution.reasons().contains("CURRENCY")
                    || contribution.reasons().contains("STRUCTURAL_UNKNOWN_UNIT")) {
                return false;
            }
        }
        return true;
    }

    private static boolean coveragePass(ExplicitAnchorDetector.Candidate candidate) {
        return !contains(candidate, "PARTIAL_OVERLAP");
    }

    private static boolean cacheNumericPass(ExplicitAnchorDetector.Candidate candidate) {
        return !contains(candidate, "STRUCTURAL_STALE_CACHE")
                && !contains(candidate, "STRUCTURAL_MISSING_CACHE")
                && !contains(candidate, "STRUCTURAL_AMOUNT_MISMATCH");
    }

    private static boolean blockersPass(
            ExplicitAnchorDetector.Candidate candidate, boolean pendingManual) {
        if (pendingManual) {
            return false;
        }
        if (contains(candidate, "UNRESOLVED_DUPLICATE")
                || contains(candidate, "PERIOD_NON_ADDITIVE")
                || contains(candidate, "STRUCTURAL_ERROR")
                || contains(candidate, "STRUCTURAL_SCRATCH")) {
            return false;
        }
        if (contains(candidate, "PERIODIZED") && !contains(candidate, "PERIOD_PARTITION")) {
            return false;
        }
        for (ExplicitAnchorDetector.Contribution contribution : candidate.contributions()) {
            for (ExplicitAnchorDetector.CellParticipation cell : contribution.cells()) {
                if ("included".equals(cell.participation())
                        && ("ERROR".equals(cell.reason()) || "SCRATCH".equals(cell.reason()))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean contains(ExplicitAnchorDetector.Candidate candidate, String code) {
        if (candidate.reasons().contains(code)) {
            return true;
        }
        for (ExplicitAnchorDetector.Contribution contribution : candidate.contributions()) {
            if (contribution.reasons().contains(code)) {
                return true;
            }
        }
        return false;
    }

    private static boolean known(String value) {
        return value != null && !value.isBlank()
                && !RegionSchemaInferencer.UNIT_UNKNOWN.equals(value)
                && !RegionSchemaInferencer.CURRENCY_UNKNOWN.equals(value);
    }
}
