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
 * @param passages the chapter passages assessed from the stored parse output, with the
 *     facts linked to each chapter (M3-06; appended)
 * @param industries the mandate's industries, several allowed, each with its reason and
 *     page provenance; an industry the packs have not seen is marked new (M3-05; appended)
 * @param country the mandate's country with its reason and page provenance, or null when
 *     none was detected (M3-05; appended)
 * @param checklist every checklist result of the last assessment — item, scope, status,
 *     reason, page evidence and ask-the-client wording (M3-10; appended)
 * @param checklistSummary the persisted counts by status, overall and per entity
 *     (M3-10; appended)
 * @param conflicts every conflict of the mandate's last assessment — the same data
 *     point disagreed on across documents within the same entity, site and period,
 *     with both sources and values and how it was settled (M3-12; appended)
 * @param versionsDiffer conflicts that are really a revision: the same document type
 *     on two different document dates, newest first, settlement left to M4 (M3-12;
 *     appended)
 */
public record MandateQa(
        long mandateId,
        List<DocumentDateRow> documents,
        List<AssessmentRunRow> stages,
        List<EntityRow> entities,
        List<ClauseRow> clauses,
        List<SiteRow> sites,
        List<PassageRow> passages,
        List<IndustryRow> industries,
        CountryRow country,
        List<ChecklistResultRow> checklist,
        List<ChecklistSummaryRow> checklistSummary,
        List<ConflictRow> conflicts,
        List<ConflictRow> versionsDiffer,
        List<ConflictSkipRow> conflictSkips) {

    /** A document/model pair not compared; ambiguity is visible, never a conflict. */
    public record ConflictSkipRow(
            long skipId,
            String kind,
            String dataPoint,
            String ontologyPath,
            long documentVersionId,
            Integer pageIndex,
            String fmSource,
            String reason) {}

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
     * @param status {@code running} | {@code completed} | {@code partial}
     *     | {@code failed}
     * @param llmFailures document-LLM calls that failed during this run; non-zero is
     *     why a stage is not {@code completed} (#151)
     * @param startedAt run start timestamp
     * @param finishedAt run finish timestamp, or null while running
     * @param parseRunId parse_run carrying this stage's usage stats, or null
     */
    public record AssessmentRunRow(
            String stage,
            String status,
            long llmFailures,
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

    /**
     * One checklist result (M3-10, GLOSSARY "Checklist status"): where one expected
     * document or data point stands for the mandate, with its basis, its recorded
     * reason and — for Provided — its page evidence.
     *
     * @param resultId result row id
     * @param itemKey the item's stable key
     * @param checklistVersion definition version the item was assessed from
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
     * @param note the item's own note when it carries one (e.g. why it became
     *     optional), else the definition's label when it carries one (e.g.
     *     {@code new industry: first mandate}), or null
     */
    public record ChecklistResultRow(
            long resultId,
            String itemKey,
            String checklistVersion,
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
            String clientRequest,
            String note) {}

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

    /**
     * One conflict over the same data point (M3-12/M3-13, ADR 0026): between two
     * documents ({@code doc-doc}) or between two sections of one document
     * ({@code intra-doc}, each side citing its passage and page). Both sides kept in
     * full, plus the settlement. Never overwritten — the losing value stays on this
     * row and in the fact table.
     *
     * @param conflictId conflict row id
     * @param kind {@code doc-doc} | {@code intra-doc} | {@code doc-fm} |
     *     {@code versions-differ}
     * @param dataPoint the data point disagreed on (pack field key, else ontology path)
     * @param fieldKey pack field key, or null when the data point is path-identified
     * @param ontologyPath bound ontology path, or null when unbound
     * @param entityId entity the comparison was made within, or null
     * @param entityName that entity's name, or null
     * @param siteId site the comparison was made within, or null
     * @param siteName that site's name, or null
     * @param period the stated period (as-on date, financial year, …), or null
     * @param verdict the comparison verdict that formed the row (e.g. {@code disagree})
     * @param left left side: source, page, value and quote
     * @param right right side: source, page, value and quote
     * @param settlement {@code rule} | {@code ai} | {@code open}, or null for
     *     {@code versions-differ} (M4 chooses the winner)
     * @param chosenSide {@code left} | {@code right} — the side whose value is used,
     *     or null when open
     * @param chosenValue the value used, or null when open
     * @param rejectedValue the value rejected (still kept), or null when open
     * @param ruleId id of the precedence rule that settled it, or null
     * @param rulesVersion version of the precedence rule set, or null
     * @param reasoning why it was settled this way (rule reason, AI reasoning, or
     *     why it is still open); never blank
     */
    public record ConflictRow(
            long conflictId,
            String kind,
            String dataPoint,
            String fieldKey,
            String ontologyPath,
            Long entityId,
            String entityName,
            Long siteId,
            String siteName,
            String period,
            String verdict,
            ConflictSideRow left,
            ConflictSideRow right,
            String settlement,
            String chosenSide,
            String chosenValue,
            String rejectedValue,
            String ruleId,
            String rulesVersion,
            String reasoning) {}

    /**
     * One side of a conflict: the document version it was read from, the citing page
     * and quote when anchored, the section (chapter passage) it was read in when the
     * side is a passage-linked fact (an intra-document conflict cites two sections of
     * one document), and the value as stated.
     *
     * @param documentVersionId the version the value came from, or null when unknown
     * @param logicalName that version's logical document name, or null
     * @param fileName that version's stored file name, or null
     * @param pageIndex the cited page, or null when unanchored
     * @param value the value as stated (never blank)
     * @param quote the verbatim the value was read from, or null when unanchored
     * @param passageHeading the chapter passage heading this side was read in, or
     *     null for document-level sides
     */
    public record ConflictSideRow(
            Long documentVersionId,
            String logicalName,
            String fileName,
            Integer pageIndex,
            String value,
            String quote,
            String passageHeading) {}
}
