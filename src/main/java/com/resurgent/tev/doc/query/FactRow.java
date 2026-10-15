package com.resurgent.tev.doc.query;

import java.util.List;
import java.util.Objects;

/**
 * Retrieval row returned by {@code FactQuery} / {@code tev-doc facts}
 * (Architecture S2.13). Evidence is joined — no PDF re-parse on read.
 *
 * <p>{@code siteId} is the M3-04 tag naming the mandate site the fact is about
 * (null for mandate-wide facts); {@code entityId} is the M3-03 tag naming the
 * mandate entity the fact is about (null when it could not be determined);
 * {@code entityReason} records why — the tag or the reason it was left untagged.
 */
public record FactRow(
        long factId,
        String ontologyPath,
        String pathRoot,
        String fieldKey,
        String valueKind,
        String valueText,
        Double valueNumber,
        String valueDate,
        String unit,
        String currency,
        String scale,
        String absoluteAmount,
        String subjectEntity,
        String lineKey,
        long documentVersionId,
        String fileName,
        String extractionMethod,
        Double confidence,
        String statedOrDerived,
        List<EvidenceRow> evidence,
        Long siteId,
        String siteName,
        Long entityId,
        String entityReason) {

    public FactRow {
        Objects.requireNonNull(evidence, "evidence");
        evidence = List.copyOf(evidence);
    }

    /**
     * One citable evidence line for a fact.
     *
     * @param role evidence role (verbatim_quote, table_cell, derived_from, ...)
     * @param verbatim exact source quote
     * @param pageIndex 1-based page when known
     * @param bboxX0 optional bbox
     * @param bboxY0 optional bbox
     * @param bboxX1 optional bbox
     * @param bboxY1 optional bbox
     * @param provenanceId provenance_doc id when resolved
     * @param resolution resolved | missing | ambiguous
     */
    public record EvidenceRow(
            String role,
            String verbatim,
            Integer pageIndex,
            Double bboxX0,
            Double bboxY0,
            Double bboxX1,
            Double bboxY1,
            Long provenanceId,
            String resolution) {
    }
}
