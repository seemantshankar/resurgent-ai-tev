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

        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("mandate qa json export failed", e);
        }
    }
}
