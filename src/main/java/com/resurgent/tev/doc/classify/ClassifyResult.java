package com.resurgent.tev.doc.classify;

import java.util.List;

/**
 * Outcome of document classification (S2.12). Never invents a pack: when nothing
 * matches, {@code docTypeCode} is {@code unknown} and {@code packCode} is null.
 *
 * @param docTypeCode pack code, or {@code unknown}
 * @param packCode chosen pack code, or null when unknown
 * @param packVersion chosen pack version, or null when unknown
 * @param confidence 0..1 confidence of the decision
 * @param reasons human-readable scoring trail (signals used, scores, filters)
 * @param status classified | unknown
 * @param classifier which path decided: deterministic | tie-break | existing
 */
public record ClassifyResult(
        String docTypeCode,
        String packCode,
        String packVersion,
        double confidence,
        List<String> reasons,
        Status status,
        String classifier) {

    public ClassifyResult {
        if (docTypeCode == null || docTypeCode.isBlank()) {
            throw new IllegalArgumentException("docTypeCode is required");
        }
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public enum Status {
        CLASSIFIED,
        UNKNOWN;

        /** Wire name stored in {@code doc_classification} / CLI summaries. */
        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Unknown-type result with the supplied reasons. */
    public static ClassifyResult unknown(List<String> reasons, String classifier) {
        return new ClassifyResult("unknown", null, null, 0.0, reasons,
                Status.UNKNOWN, classifier);
    }
}
