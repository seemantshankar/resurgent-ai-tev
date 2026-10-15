package com.resurgent.tev.doc.query;

import java.util.List;

/**
 * A mandate's persisted M3 assessment results, sectioned so later tickets can add their
 * sections (entities, sites, passages, clauses, conflicts, checklists) without reshaping
 * what exists. Read through {@code MandateQaQueryRunner}; never from PDFs or prompts.
 *
 * @param mandateId mandate scope
 * @param documents one row per document version with its document date, precision, source
 *     and upload date
 * @param stages the latest assessment run per stage
 * @param passages the chapter passages assessed from the stored parse output, with the
 *     facts linked to each chapter
 */
public record MandateQa(
        long mandateId,
        List<DocumentDateRow> documents,
        List<AssessmentRunRow> stages,
        List<PassageRow> passages) {

    /**
     * One document version's date assessment.
     *
     * @param documentId logical document id
     * @param documentVersionId version id
     * @param logicalName document logical name
     * @param versionLabel version label ({@code v1}, {@code Amendment-1}, …)
     * @param fileName stored file name
     * @param dateKind pack-declared date kind ({@code execution}/…), or null
     * @param documentDate canonical date (YYYY-MM-DD, YYYY-MM or YYYY by precision)
     * @param datePrecision {@code day} | {@code month} | {@code year}, or null
     * @param dateSource {@code stated} | {@code file_metadata} | {@code none}, or null when not assessed
     * @param statedText the verbatim the date was read from, when stated
     * @param pageIndex provenance page for the stated date, when anchored
     * @param uploadDate ingest timestamp of the version (the upload date)
     */
    public record DocumentDateRow(
            long documentId,
            long documentVersionId,
            String logicalName,
            String versionLabel,
            String fileName,
            String dateKind,
            String documentDate,
            String datePrecision,
            String dateSource,
            String statedText,
            Integer pageIndex,
            String uploadDate) {}

    /**
     * Latest run of one assessment stage.
     *
     * @param stage M3 stage name ({@code assess_profile}/…)
     * @param status {@code running} | {@code completed} | {@code failed}
     * @param startedAt run start timestamp
     * @param finishedAt run finish timestamp, or null while running
     * @param parseRunId parse_run carrying this stage's usage stats, or null
     */
    public record AssessmentRunRow(
            String stage,
            String status,
            String startedAt,
            String finishedAt,
            Long parseRunId) {}

    /**
     * One chapter passage of a mandate document version, with the facts assessed from
     * that chapter.
     *
     * @param passageId passage id
     * @param documentVersionId version the passage was split from
     * @param passageIndex 1-based chapter order within the version
     * @param heading the chapter heading as stored by the parser
     * @param topic pack passage topic matched on the heading, or null when none matches
     * @param pageFrom first page of the passage
     * @param pageTo last page carrying the passage's text
     * @param pageIndex provenance page of the heading (falls back to {@code pageFrom})
     * @param verbatim the passage's stored text (provenance), or null when unanchored
     * @param summary one-line chapter summary
     * @param facts facts linked to this passage; row facts keep their line key
     */
    public record PassageRow(
            long passageId,
            long documentVersionId,
            int passageIndex,
            String heading,
            String topic,
            int pageFrom,
            int pageTo,
            int pageIndex,
            String verbatim,
            String summary,
            List<PassageFactRow> facts) {}

    /**
     * One fact linked to a passage — a chapter data point (land area, capacity, …) or a
     * single row of a listed table such as the plant and machinery list (M2
     * quotation-row style: one fact per row, line key carries the row description).
     *
     * @param factId fact id
     * @param fieldKey pack field (or table) key the fact belongs to
     * @param ontologyPath canonical spine path of the fact
     * @param lineKey row/line identifier for row facts, else null
     * @param valueText text value, when the fact is textual
     * @param valueNumber numeric value, when numeric
     * @param valueDate date value, when a date
     * @param unit unit string (row facts may carry {@code qty:N}), or null
     * @param currency currency code, or null
     * @param confidence extraction confidence, 0..1
     * @param extractionMethod how the fact was extracted
     * @param evidenceQuotes the verbatim quotes backing the fact, in evidence order
     * @param evidencePages the pages of those quotes, aligned by index (nulls allowed)
     */
    public record PassageFactRow(
            long factId,
            String fieldKey,
            String ontologyPath,
            String lineKey,
            String valueText,
            Double valueNumber,
            String valueDate,
            String unit,
            String currency,
            Double confidence,
            String extractionMethod,
            List<String> evidenceQuotes,
            List<Integer> evidencePages) {}
}
