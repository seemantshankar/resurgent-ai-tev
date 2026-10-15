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
 * @param sites the identified sites with their spelling aliases and mentioning documents
 *     (M3-04; appended)
 */
public record MandateQa(
        long mandateId,
        List<DocumentDateRow> documents,
        List<AssessmentRunRow> stages,
        List<SiteRow> sites) {

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
     * One identified mandate site (GLOSSARY "Site"): the canonical name, the spelling
     * mentions recorded per document and the documents the site was seen in.
     *
     * @param siteId site id
     * @param siteName canonical site name (the first mention that created the site)
     * @param aliases one row per spelling mention, each with why it matched
     * @param documents distinct documents that mention the site
     */
    public record SiteRow(
            long siteId,
            String siteName,
            List<SiteAliasRow> aliases,
            List<SiteDocumentRow> documents) {}

    /**
     * One spelling of a site as seen in one document (M3-04).
     *
     * @param documentVersionId document the spelling was seen in
     * @param logicalName document logical name
     * @param fileName stored file name
     * @param aliasText the spelling exactly as seen
     * @param matchScore loose site score when the mention was merged, 1.0 for the first
     *     mention that created the site
     * @param matchReason why the mention matched (never blank — "every match records why")
     */
    public record SiteAliasRow(
            long documentVersionId,
            String logicalName,
            String fileName,
            String aliasText,
            Double matchScore,
            String matchReason) {}

    /**
     * A document that mentions a site (M3-04).
     *
     * @param documentVersionId version id
     * @param logicalName document logical name
     * @param fileName stored file name
     */
    public record SiteDocumentRow(
            long documentVersionId,
            String logicalName,
            String fileName) {}
}
