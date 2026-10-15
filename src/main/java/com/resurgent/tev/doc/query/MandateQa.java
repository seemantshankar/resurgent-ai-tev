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
 * @param clauses every clause read for the mandate's clause-carrying documents (M3-08)
 * @param sites the identified sites with their spelling aliases and mentioning documents
 *     (M3-04; appended)
 * @param checklist every checklist result of the last assessment — item, scope, status,
 *     reason, page evidence and ask-the-client wording (M3-10; appended)
 * @param checklistSummary the persisted counts by status, overall and per entity
 *     (M3-10; appended)
 */
public record MandateQa(
        long mandateId,
        List<DocumentDateRow> documents,
        List<AssessmentRunRow> stages,
        List<EntityRow> entities,
        List<ClauseRow> clauses,
        List<SiteRow> sites,
        List<ChecklistResultRow> checklist,
        List<ChecklistSummaryRow> checklistSummary) {

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

    /**
     * One checklist result (M3-10, GLOSSARY "Checklist status"): where one expected
     * document or data point stands for the mandate, with its basis, its recorded
     * reason and — for Provided — its page evidence.
     *
     * @param resultId result row id
     * @param itemKey the item's stable key
     * @param label human label
     * @param level {@code document} | {@code data_point}
     * @param granularity {@code mandate} | {@code entity} | {@code site}
     * @param mandatory whether the definition marks the item mandatory
     * @param basisKind {@code regulation} | {@code practice} | {@code observed}
     * @param basisText the named regulation or practice that requires the item
     * @param origin {@code drafted} | {@code observed} | {@code promoted}
     * @param documentType pack code of the expected document
     * @param fieldKey data-point pack field key, or null
     * @param ontologyPath data-point ontology path, or null
     * @param entityId entity the result is for (entity-granularity items), or null
     * @param entityName that entity's name, or null
     * @param siteId site the result is for (site-granularity items), or null
     * @param siteName that site's name, or null
     * @param status {@code provided} | {@code unclear} | {@code not_provided} |
     *     {@code conflicting} | {@code not_applicable}
     * @param reason why the status was set (never blank)
     * @param documentVersionId evidence document version, or null
     * @param logicalName evidence document logical name, or null
     * @param fileName evidence file name, or null
     * @param pageIndex cited page; always present for {@code provided}
     * @param confidence evidence confidence, or null
     * @param clientRequest ask-the-client wording, set when {@code not_provided}
     */
    public record ChecklistResultRow(
            long resultId,
            String itemKey,
            String label,
            String level,
            String granularity,
            boolean mandatory,
            String basisKind,
            String basisText,
            String origin,
            String documentType,
            String fieldKey,
            String ontologyPath,
            Long entityId,
            String entityName,
            Long siteId,
            String siteName,
            String status,
            String reason,
            Long documentVersionId,
            String logicalName,
            String fileName,
            Integer pageIndex,
            Double confidence,
            String clientRequest) {}

    /**
     * Persisted checklist counts by status, overall or per entity (M3-10).
     *
     * @param scopeKind {@code overall} | {@code entity} | {@code site}
     * @param entityId entity the counts are for, or null for overall
     * @param entityName that entity's name, or null
     * @param siteId site the counts are for, or null
     * @param siteName that site's name, or null
     * @param status checklist status the count is for
     * @param itemCount number of result rows with that status in that scope
     */
    public record ChecklistSummaryRow(
            String scopeKind,
            Long entityId,
            String entityName,
            Long siteId,
            String siteName,
            String status,
            int itemCount) {}

    /**
     * One analyst override of the checklist for a mandate (M3-10): an optional
     * add/remove with a recorded reason; it never blocks an assessment.
     *
     * @param overrideId override row id
     * @param itemKey the item added or removed
     * @param action {@code add} | {@code remove}
     * @param reason why the analyst adjusted the checklist (never blank)
     * @param createdAt when the override was recorded
     */
    public record ChecklistOverrideRow(
            long overrideId,
            String itemKey,
            String action,
            String reason,
            String createdAt) {}
}
