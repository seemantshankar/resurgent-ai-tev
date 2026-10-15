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
 * @param entities the mandate's entities (borrower, promoters, group companies) with
 *     their roles, aliases and source pages
 */
public record MandateQa(
        long mandateId,
        List<DocumentDateRow> documents,
        List<AssessmentRunRow> stages,
        List<EntityRow> entities) {

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
     * One mandate entity (M3-03): a company or person the documents are about.
     *
     * @param entityId entity id
     * @param role {@code borrower} | {@code promoter} | {@code group_company}
     * @param name the normalised (first identified) name
     * @param reason the recorded reason this entity has this role, may be null
     * @param aliases every spelling of the name seen, with its source page
     * @param pageIndexes the distinct source pages of the entity's identifications
     */
    public record EntityRow(
            long entityId,
            String role,
            String name,
            String reason,
            List<AliasRow> aliases,
            List<Integer> pageIndexes) {}

    /**
     * One spelling variant of an entity's name.
     *
     * @param alias the spelling as stated in the document
     * @param pageIndex page the spelling was first read from, or null when unanchored
     */
    public record AliasRow(String alias, Integer pageIndex) {}
}
