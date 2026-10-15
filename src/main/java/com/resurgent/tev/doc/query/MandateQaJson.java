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

        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("mandate qa json export failed", e);
        }
    }
}
