package com.resurgent.tev.parser.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Deterministic {@code parse_run.metrics} payload for ingest runs. */
final class IngestMetrics {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IngestMetrics() {
    }

    static String toJson(String fileName, String fileHash, String worksheetName, int rowCount,
            int cellsIn, int cellsWritten, int cellsCoerced, int cellsError, QaGateResult qa) {
        ObjectNode metrics = MAPPER.createObjectNode();
        metrics.put("fileName", fileName);
        metrics.put("fileHash", fileHash);
        metrics.put("worksheetName", worksheetName);
        metrics.put("rows", rowCount);
        metrics.put("cellsIn", cellsIn);
        metrics.put("cellsWritten", cellsWritten);
        metrics.put("cellsRejected", qa.cellsRejected());
        metrics.put("cellsCoerced", cellsCoerced);
        metrics.put("cellsError", cellsError);
        metrics.put("qaStatus", qa.status());
        ArrayNode reasons = metrics.putArray("qaFailureReasons");
        qa.reasons().forEach(reasons::add);
        return metrics.toString();
    }
}
