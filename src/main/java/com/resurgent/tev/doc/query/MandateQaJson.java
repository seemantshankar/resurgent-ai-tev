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

        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("mandate qa json export failed", e);
        }
    }
}
