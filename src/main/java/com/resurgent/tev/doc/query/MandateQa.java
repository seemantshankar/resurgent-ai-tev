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
 * @param industries the mandate's industries, several allowed, each with its reason and
 *     page provenance; an industry the packs have not seen is marked new
 * @param country the mandate's country with its reason and page provenance, or null when
 *     none was detected
 */
public record MandateQa(
        long mandateId,
        List<DocumentDateRow> documents,
        List<AssessmentRunRow> stages,
        List<IndustryRow> industries,
        CountryRow country) {

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
     * One industry recorded for the mandate, with its reason and page provenance.
     *
     * @param industryRecordId the stored record id
     * @param industry industry name as recorded
     * @param isNewIndustry true when the packs have not seen this industry before
     * @param reason why the industry was recorded (human-readable)
     * @param verbatim the verbatim text the industry was read from, when anchored
     * @param pageIndex provenance page for the verbatim, when anchored
     * @param documentVersionId the version the industry was read from, may be null
     */
    public record IndustryRow(
            long industryRecordId,
            String industry,
            boolean isNewIndustry,
            String reason,
            String verbatim,
            Integer pageIndex,
            Long documentVersionId) {}

    /**
     * The mandate's single country with its reason and page provenance, or absent
     * (null {@code MandateQa.country}) when none was detected.
     *
     * @param countryRecordId the stored record id
     * @param country country name as recorded
     * @param reason why the country was recorded (human-readable)
     * @param verbatim the verbatim text the country was read from, when anchored
     * @param pageIndex provenance page for the verbatim, when anchored
     * @param documentVersionId the version the country was read from, may be null
     */
    public record CountryRow(
            long countryRecordId,
            String country,
            String reason,
            String verbatim,
            Integer pageIndex,
            Long documentVersionId) {}
}
