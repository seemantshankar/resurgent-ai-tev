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

        // M3-04 (#137): sites with their spelling variants and the documents that
        // mention them — appended at the end so #134's sections are untouched.
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

        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("mandate qa json export failed", e);
        }
    }
}
