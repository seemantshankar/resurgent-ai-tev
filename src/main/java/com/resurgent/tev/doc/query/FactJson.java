package com.resurgent.tev.doc.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** JSON export for {@code tev-doc facts --json}. */
public final class FactJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FactJson() {}

    /**
     * Serializes fact rows to a JSON array.
     *
     * @param rows fact rows with evidence (required)
     * @return pretty JSON text
     */
    public static String write(List<FactRow> rows) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (FactRow row : rows) {
            ObjectNode n = arr.addObject();
            n.put("fact_id", row.factId());
            n.put("ontology_path", row.ontologyPath());
            n.put("path_root", row.pathRoot());
            n.put("nomenclature_status", row.nomenclatureStatus());
            n.put("nomenclature_reason", row.nomenclatureReason());
            n.put("unbound_reason", row.unboundReason());
            n.put("field_key", row.fieldKey());
            n.put("value_text", row.valueText());
            if (row.valueNumber() != null) {
                n.put("value_number", row.valueNumber());
            }
            n.put("value_date", row.valueDate());
            n.put("unit", row.unit());
            n.put("currency", row.currency());
            n.put("scale", row.scale());
            n.put("absolute_amount", row.absoluteAmount());
            n.put("subject_entity", row.subjectEntity());
            n.put("line_key", row.lineKey());
            n.put("document_version_id", row.documentVersionId());
            n.put("file_name", row.fileName());
            n.put("extraction_method", row.extractionMethod());
            if (row.confidence() != null) {
                n.put("confidence", row.confidence());
            }
            n.put("stated_or_derived", row.statedOrDerived());
            if (row.entityId() != null) {
                n.put("entity_id", row.entityId());
            }
            n.put("entity_reason", row.entityReason());
            ArrayNode ev = n.putArray("evidence");
            for (FactRow.EvidenceRow e : row.evidence()) {
                ObjectNode en = ev.addObject();
                en.put("role", e.role());
                en.put("verbatim", e.verbatim());
                if (e.pageIndex() != null) {
                    en.put("page_index", e.pageIndex());
                }
                if (e.bboxX0() != null) {
                    en.put("bbox_x0", e.bboxX0());
                    en.put("bbox_y0", e.bboxY0());
                    en.put("bbox_x1", e.bboxX1());
                    en.put("bbox_y1", e.bboxY1());
                }
                if (e.provenanceId() != null) {
                    en.put("provenance_id", e.provenanceId());
                }
                en.put("resolution", e.resolution());
            }
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(arr);
        } catch (Exception e) {
            throw new IllegalStateException("fact json export failed", e);
        }
    }
}
