package com.resurgent.tev.doc.induce;

import java.util.Locale;
import java.util.Set;

/**
 * Closed {@code induction_decision.reason_code} values. Older names written by
 * the generalizer and the widener map onto this set; the table is not replaced.
 */
public final class InductionReasonCodes {

    public static final Set<String> SPEC = Set.of(
            "web_domain_standard",
            "spine_match",
            "single_doc_validated",
            "entity_token_stripped",
            "entity_token_rejected",
            "low_coverage",
            "cluster_promoted",
            "below_threshold");

    private InductionReasonCodes() {}

    /**
     * Maps a stored or in-memory reason onto the spec set. An unknown name
     * becomes {@code below_threshold} so a later CHECK cannot reject the row.
     */
    public static String canonical(String reason) {
        if (reason == null || reason.isBlank()) {
            return "below_threshold";
        }
        String key = reason.trim();
        if (SPEC.contains(key)) {
            return key;
        }
        return switch (key) {
            case "entity_token" -> "entity_token_rejected";
            case "label_not_value" -> "entity_token_stripped";
            case "below_consensus", "not_evidenced", "unknown_root", "empty_key" -> "below_threshold";
            case "single_document_industry_leaf", "evidenced" -> "single_doc_validated";
            case "spine_leaf", "spine_promoted" -> "spine_match";
            case "domain_standard" -> "web_domain_standard";
            case "widen_table", "widen_clause", "widen_passage", "widen_optional",
                    "widen_new_path", "observed_in_mandates" -> "cluster_promoted";
            case "no_columns", "no_field", "coverage_demoted" -> "low_coverage";
            default -> fallback(key);
        };
    }

    private static String fallback(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.contains("entity")) {
            return "entity_token_rejected";
        }
        if (lower.contains("coverage") || lower.contains("quality") || lower.contains("column")) {
            return "low_coverage";
        }
        if (lower.contains("widen") || lower.contains("cluster")) {
            return "cluster_promoted";
        }
        if (lower.contains("spine")) {
            return "spine_match";
        }
        if (lower.contains("domain")) {
            return "web_domain_standard";
        }
        return "below_threshold";
    }
}
