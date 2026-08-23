package com.resurgent.tev.parser.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds the deterministic {@code parse_run.metrics} payload: for identical
 * input bytes and config, the same fields in the same order, byte-identical
 * across runs. Carries no wall-clock timing, which would break that guarantee.
 */
final class IngestMetrics {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IngestMetrics() {
    }

    static String toJson(String fileName, String fileHash, String worksheetName, int rowCount,
            int cellsIn, int cellsWritten, int cellsCoerced, int cellsError,
            int referencesTotal, int referencesResolved, int referencesUnresolved,
            int formulaCellsTotal, int formulaCellsTokenized, int formulaCellsParseError,
            int formulaCellsUnavailable, QaGateResult qa) {
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
        metrics.put("referencesTotal", referencesTotal);
        metrics.put("referencesResolved", referencesResolved);
        metrics.put("referencesUnresolved", referencesUnresolved);
        metrics.put("formulaCellsTotal", formulaCellsTotal);
        metrics.put("formulaCellsTokenized", formulaCellsTokenized);
        metrics.put("formulaCellsParseError", formulaCellsParseError);
        metrics.put("formulaCellsUnavailable", formulaCellsUnavailable);
        metrics.put("qaStatus", qa.status());
        ArrayNode reasons = metrics.putArray("qaFailureReasons");
        qa.reasons().forEach(reasons::add);
        return metrics.toString();
    }
}
