package com.resurgent.tev.doc.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JSON export for {@code tev-doc qa --json}, sectioned like {@link MandateQa}. */
public final class MandateQaJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MandateQaJson() {}

    /**
     * Serializes the mandate QA result to a JSON object with a section per result kind.
     *
     * @param qa mandate QA result (required)
     * @return pretty JSON text
     */
    public static String write(MandateQa qa) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("mandate_id", qa.mandateId());

        ArrayNode documents = root.putArray("documents");
        for (MandateQa.DocumentDateRow row : qa.documents()) {
            ObjectNode n = documents.addObject();
            n.put("document_id", row.documentId());
            n.put("document_version_id", row.documentVersionId());
            n.put("logical_name", row.logicalName());
            n.put("version_label", row.versionLabel());
            n.put("file_name", row.fileName());
            n.put("date_kind", row.dateKind());
            n.put("document_date", row.documentDate());
            n.put("date_precision", row.datePrecision());
            n.put("date_source", row.dateSource());
            n.put("stated_text", row.statedText());
            if (row.pageIndex() != null) {
                n.put("page_index", row.pageIndex());
            }
            n.put("upload_date", row.uploadDate());
        }

        ArrayNode stages = root.putArray("stages");
        for (MandateQa.AssessmentRunRow row : qa.stages()) {
            ObjectNode n = stages.addObject();
            n.put("stage", row.stage());
            n.put("status", row.status());
            n.put("started_at", row.startedAt());
            n.put("finished_at", row.finishedAt());
            if (row.parseRunId() != null) {
                n.put("parse_run_id", row.parseRunId());
            }
        }

        // M3-03 (#136): entities with their roles, aliases and source pages.
        ArrayNode entities = root.putArray("entities");
        for (MandateQa.EntityRow row : qa.entities()) {
            ObjectNode n = entities.addObject();
            n.put("entity_id", row.entityId());
            n.put("role", row.role());
            n.put("name", row.name());
            n.put("reason", row.reason());
            ArrayNode aliases = n.putArray("aliases");
            for (MandateQa.AliasRow alias : row.aliases()) {
                ObjectNode a = aliases.addObject();
                a.put("alias", alias.alias());
                if (alias.pageIndex() != null) {
                    a.put("page_index", alias.pageIndex());
                }
            }
            ArrayNode pages = n.putArray("page_indexes");
            for (Integer pageIndex : row.pageIndexes()) {
                pages.add(pageIndex);
            }
        }

        // M3-08 (#141): clauses with their type, slots and provenance.
        ArrayNode clauses = root.putArray("clauses");
        for (MandateQa.ClauseRow row : qa.clauses()) {
            ObjectNode n = clauses.addObject();
            n.put("clause_id", row.clauseId());
            n.put("document_id", row.documentId());
            n.put("document_version_id", row.documentVersionId());
            n.put("logical_name", row.logicalName());
            n.put("file_name", row.fileName());
            n.put("heading", row.heading());
            n.put("verbatim", row.verbatim());
            if (row.pageStart() != null) {
                n.put("page_start", row.pageStart());
            }
            if (row.pageEnd() != null) {
                n.put("page_end", row.pageEnd());
            }
            n.put("type_code", row.typeCode());
            n.put("type_label", row.typeLabel());
            n.put("parent_family", row.parentFamily());
            n.put("status", row.status());
            n.put("novelty", row.novelty());
            n.put("summary", row.summary());
            n.put("importance", row.importance());
            n.put("extraction_method", row.extractionMethod());
            ArrayNode slots = n.putArray("slots");
            for (MandateQa.ClauseSlotRow slot : row.slots()) {
                ObjectNode s = slots.addObject();
                s.put("slot_key", slot.slotKey());
                s.put("value_text", slot.valueText());
                if (slot.valueNumber() != null) {
                    s.put("value_number", slot.valueNumber());
                }
                s.put("unit", slot.unit());
                s.put("currency", slot.currency());
                s.put("scale", slot.scale());
            }
        }

        // M3-04 (#137): sites with their spelling variants and the documents that
        // mention them — appended after #134's sections.
        ArrayNode sites = root.putArray("sites");
        for (MandateQa.SiteRow row : qa.sites()) {
            ObjectNode n = sites.addObject();
            n.put("site_id", row.siteId());
            n.put("site_name", row.siteName());
            ArrayNode aliases = n.putArray("aliases");
            for (MandateQa.SiteAliasRow alias : row.aliases()) {
                ObjectNode a = aliases.addObject();
                a.put("alias_text", alias.aliasText());
                a.put("document_version_id", alias.documentVersionId());
                if (alias.fileName() != null) {
                    a.put("file_name", alias.fileName());
                }
                if (alias.matchScore() != null) {
                    a.put("match_score", alias.matchScore());
                }
                a.put("match_reason", alias.matchReason());
            }
            ArrayNode mentioning = n.putArray("documents");
            for (MandateQa.SiteDocumentRow doc : row.documents()) {
                ObjectNode d = mentioning.addObject();
                d.put("document_version_id", doc.documentVersionId());
                d.put("logical_name", doc.logicalName());
                d.put("file_name", doc.fileName());
            }
        }

        // M3-06 (#139): chapter passages from the stored parse output with their
        // per-chapter facts — appended at the end so the earlier sections stand.
        ArrayNode passages = root.putArray("passages");
        for (MandateQa.PassageRow row : qa.passages()) {
            ObjectNode n = passages.addObject();
            n.put("passage_id", row.passageId());
            n.put("document_version_id", row.documentVersionId());
            n.put("passage_index", row.passageIndex());
            n.put("heading", row.heading());
            if (row.topic() != null) {
                n.put("topic", row.topic());
            }
            n.put("page_from", row.pageFrom());
            n.put("page_to", row.pageTo());
            n.put("page_index", row.pageIndex());
            if (row.verbatim() != null) {
                n.put("verbatim", row.verbatim());
            }
            if (row.summary() != null) {
                n.put("summary", row.summary());
            }
            ArrayNode facts = n.putArray("facts");
            for (MandateQa.PassageFactRow fact : row.facts()) {
                ObjectNode f = facts.addObject();
                f.put("fact_id", fact.factId());
                f.put("field_key", fact.fieldKey());
                f.put("ontology_path", fact.ontologyPath());
                if (fact.lineKey() != null) {
                    f.put("line_key", fact.lineKey());
                }
                if (fact.valueText() != null) {
                    f.put("value_text", fact.valueText());
                }
                if (fact.valueNumber() != null) {
                    f.put("value_number", fact.valueNumber());
                }
                if (fact.valueDate() != null) {
                    f.put("value_date", fact.valueDate());
                }
                if (fact.unit() != null) {
                    f.put("unit", fact.unit());
                }
                if (fact.currency() != null) {
                    f.put("currency", fact.currency());
                }
                if (fact.confidence() != null) {
                    f.put("confidence", fact.confidence());
                }
                if (fact.extractionMethod() != null) {
                    f.put("extraction_method", fact.extractionMethod());
                }
                ArrayNode quotes = f.putArray("evidence_quotes");
                fact.evidenceQuotes().forEach(quotes::add);
                ArrayNode pages = f.putArray("evidence_pages");
                for (Integer page : fact.evidencePages()) {
                    if (page == null) {
                        pages.addNull();
                    } else {
                        pages.add(page);
                    }
                }
            }
        }

        // M3-05 (#138): the mandate's industries and country — appended after the sites
        // section so earlier tickets' sections are untouched.
        ArrayNode industries = root.putArray("industries");
        for (MandateQa.IndustryRow row : qa.industries()) {
            ObjectNode n = industries.addObject();
            n.put("industry", row.industry());
            n.put("is_new_industry", row.isNewIndustry());
            n.put("reason", row.reason());
            if (row.verbatim() != null) {
                n.put("verbatim", row.verbatim());
            }
            if (row.pageIndex() != null) {
                n.put("page_index", row.pageIndex());
            }
            if (row.documentVersionId() != null) {
                n.put("document_version_id", row.documentVersionId());
            }
        }

        if (qa.country() != null) {
            ObjectNode n = root.putObject("country");
            n.put("country", qa.country().country());
            n.put("reason", qa.country().reason());
            if (qa.country().verbatim() != null) {
                n.put("verbatim", qa.country().verbatim());
            }
            if (qa.country().pageIndex() != null) {
                n.put("page_index", qa.country().pageIndex());
            }
            if (qa.country().documentVersionId() != null) {
                n.put("document_version_id", qa.country().documentVersionId());
            }
        } else {
            root.putNull("country");
        }

        // M3-10 (#143): checklist results, each with its scope, status, reason, page
        // evidence and ask-the-client wording, and the persisted counts by status —
        // appended at the end so the earlier sections are untouched.
        ArrayNode checklist = root.putArray("checklist");
        for (MandateQa.ChecklistResultRow row : qa.checklist()) {
            ObjectNode n = checklist.addObject();
            n.put("result_id", row.resultId());
            n.put("item_key", row.itemKey());
            n.put("label", row.label());
            n.put("level", row.level());
            n.put("granularity", row.granularity());
            n.put("mandatory", row.mandatory());
            n.put("basis_kind", row.basisKind());
            n.put("basis_text", row.basisText());
            n.put("origin", row.origin());
            n.put("document_type", row.documentType());
            n.put("field_key", row.fieldKey());
            n.put("ontology_path", row.ontologyPath());
            if (row.entityId() != null) {
                n.put("entity_id", row.entityId());
            }
            n.put("entity_name", row.entityName());
            if (row.siteId() != null) {
                n.put("site_id", row.siteId());
            }
            n.put("site_name", row.siteName());
            n.put("status", row.status());
            n.put("reason", row.reason());
            if (row.documentVersionId() != null) {
                n.put("document_version_id", row.documentVersionId());
            }
            n.put("logical_name", row.logicalName());
            n.put("file_name", row.fileName());
            if (row.pageIndex() != null) {
                n.put("page_index", row.pageIndex());
            }
            if (row.confidence() != null) {
                n.put("confidence", row.confidence());
            }
            n.put("client_request", row.clientRequest());
            n.put("note", row.note());
        }

        ArrayNode checklistSummary = root.putArray("checklist_summary");
        for (MandateQa.ChecklistSummaryRow row : qa.checklistSummary()) {
            ObjectNode n = checklistSummary.addObject();
            n.put("scope_kind", row.scopeKind());
            if (row.entityId() != null) {
                n.put("entity_id", row.entityId());
            }
            n.put("entity_name", row.entityName());
            if (row.siteId() != null) {
                n.put("site_id", row.siteId());
            }
            n.put("site_name", row.siteName());
            n.put("status", row.status());
            n.put("item_count", row.itemCount());
        }

        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("mandate qa json export failed", e);
        }
    }
}
