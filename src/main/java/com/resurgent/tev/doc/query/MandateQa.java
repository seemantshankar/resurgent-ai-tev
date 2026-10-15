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
 * @param clauses every clause read for the mandate's clause-carrying documents (M3-08)
 */
public record MandateQa(
        long mandateId,
        List<DocumentDateRow> documents,
        List<AssessmentRunRow> stages,
        List<ClauseRow> clauses) {

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
     * One persisted clause (M3-08).
     *
     * @param clauseId clause row id
     * @param documentId logical document id
     * @param documentVersionId version the clause was read from
     * @param logicalName document logical name
     * @param fileName stored file name
     * @param heading clause heading as read (e.g. {@code 12. TERMINATION})
     * @param verbatim clause text exactly as stored in the parse/layout text
     * @param pageStart first page of the clause, or null when unanchored
     * @param pageEnd last page of the clause, or null when unanchored
     * @param typeCode matched or proposed clause type key, or null when untyped
     * @param typeLabel human label of the type
     * @param parentFamily parent clause family of the type
     * @param status {@code bound} (matched a pack type) | {@code open} (kept for new types)
     * @param novelty {@code known} | {@code new}
     * @param summary one-line English summary, or null when not read by the model
     * @param importance lender-risk importance {@code high} | {@code medium} | {@code low}, or null
     * @param extractionMethod {@code alias} | {@code llm_open}, or null when untyped offline
     * @param slots normalised key numbers (notice period, penalty, tenure, …)
     */
    public record ClauseRow(
            long clauseId,
            long documentId,
            long documentVersionId,
            String logicalName,
            String fileName,
            String heading,
            String verbatim,
            Integer pageStart,
            Integer pageEnd,
            String typeCode,
            String typeLabel,
            String parentFamily,
            String status,
            String novelty,
            String summary,
            String importance,
            String extractionMethod,
            List<ClauseSlotRow> slots) {}

    /**
     * One normalised key number of a clause.
     *
     * @param slotKey {@code notice_period} | {@code penalty} | {@code tenure} | …
     * @param valueText the value as stated in the clause
     * @param valueNumber normalised magnitude, or null when unparsed
     * @param unit duration unit (days/months/years), or null
     * @param currency ISO-like currency code when stated, or null
     * @param scale named money scale when stated, or null
     */
    public record ClauseSlotRow(
            String slotKey,
            String valueText,
            Double valueNumber,
            String unit,
            String currency,
            String scale) {}
}
