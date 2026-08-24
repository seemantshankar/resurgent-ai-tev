package com.resurgent.tev.parser.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Semantic QA: pending accounted work does not fail a parse. Unaccounted
 * mappings, contribution evidence/arithmetic, candidates, duplicates,
 * scratch/support reasons, worksheet roles, invalid automatic trust, and analyst
 * trust without an exact accepted fingerprint do.
 */
final class SemanticQa {

    private SemanticQa() {}

    static List<String> reasons(
            SemanticQaStats stats,
            List<CostHeadTrust> reports,
            Set<String> acceptedFingerprints) {
        List<String> reasons = new ArrayList<>();
        if (stats.mappingsUnaccounted() > 0) {
            reasons.add("mapping_unaccounted: " + stats.mappingsUnaccounted());
        }
        if (stats.contributionsUnaccounted() > 0) {
            reasons.add("contribution_unaccounted: " + stats.contributionsUnaccounted());
        }
        if (stats.contributionArithmeticMismatches() > 0) {
            reasons.add("contribution_arithmetic_mismatch: "
                    + stats.contributionArithmeticMismatches());
        }
        if (stats.candidatesUnaccounted() > 0) {
            reasons.add("candidate_unaccounted: " + stats.candidatesUnaccounted());
        }
        if (stats.duplicatesUnaccounted() > 0) {
            reasons.add("duplicate_unaccounted: " + stats.duplicatesUnaccounted());
        }
        if (stats.scratchUnaccounted() > 0) {
            reasons.add("scratch_unaccounted: " + stats.scratchUnaccounted());
        }
        if (stats.worksheetRolesUnaccounted() > 0) {
            reasons.add("worksheet_role_unaccounted: " + stats.worksheetRolesUnaccounted());
        }
        for (CostHeadTrust report : reports) {
            if ("analyst".equals(report.source())
                    && "trusted".equals(report.state())
                    && (report.fingerprint() == null
                            || !acceptedFingerprints.contains(report.fingerprint()))) {
                reasons.add("analyst_trust_without_decision");
            }
        }
        return List.copyOf(reasons);
    }
}
