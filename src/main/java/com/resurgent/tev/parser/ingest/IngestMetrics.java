package com.resurgent.tev.parser.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

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
            int formulaCellsUnavailable, RegionQaStats regionQa, QaGateResult qa,
            List<WorksheetRoleScorer.Score> worksheetRoles, List<CostHeadTrust> costHeads) {
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
        metrics.put("regionsTotal", regionQa.regionsTotal());
        metrics.put("cellsWithoutRegion", regionQa.cellsWithoutRegion());
        metrics.put("regionsClassified", regionQa.regionsClassified());
        metrics.put("regionsQueuedForReview", regionQa.regionsQueuedForReview());
        metrics.put("regionsUnaccounted", regionQa.regionsUnaccounted());
        metrics.put("qaStatus", qa.status());
        ArrayNode reasons = metrics.putArray("qaFailureReasons");
        qa.reasons().forEach(reasons::add);
        ArrayNode worksheets = metrics.putArray("worksheets");
        for (WorksheetRoleScorer.Score score : worksheetRoles) {
            ObjectNode sheet = worksheets.addObject();
            sheet.put("sheetName", score.sheetName());
            sheet.put("role", score.role());
            sheet.put("roleConf", score.confidence());
            ArrayNode sheetReasons = sheet.putArray("reasons");
            for (WorksheetRoleScorer.RoleReason reason : score.reasons()) {
                ObjectNode reasonNode = sheetReasons.addObject();
                reasonNode.put("code", reason.code().name());
                reasonNode.put("weight", reason.weight());
                ObjectNode params = reasonNode.putObject("params");
                reason.params().forEach(params::put);
            }
        }
        ArrayNode costHeadNodes = metrics.putArray("costHeads");
        for (CostHeadTrust head : costHeads) {
            ObjectNode node = costHeadNodes.addObject();
            node.put("code", head.code());
            node.put("state", head.state());
            if (head.source() == null) {
                node.putNull("source");
            } else {
                node.put("source", head.source());
            }
            if (head.amount() == null) {
                node.putNull("amount");
            } else {
                node.put("amount", head.amount().doubleValue());
            }
            node.put("unit", head.unit());
            node.put("currency", head.currency());
            node.put("confidence", head.confidence());
            ArrayNode headReasons = node.putArray("reasons");
            head.reasons().forEach(headReasons::add);
            node.put("reviewStatus", head.reviewStatus());
            node.put("fingerprint", head.fingerprint());
            ObjectNode gates = node.putObject("gates");
            for (TrustEvaluator.Gate gate : head.gates()) {
                gates.put(gate.name(), gate.passed());
            }
        }
        return metrics.toString();
    }
}
