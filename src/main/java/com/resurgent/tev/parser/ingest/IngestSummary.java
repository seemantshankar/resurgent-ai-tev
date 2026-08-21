package com.resurgent.tev.parser.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Result of a successful ingest: what the CLI summarizes and the report serializes. */
public record IngestSummary(String fileName, String fileHash, String worksheetName,
        int rowCount, int cellCount, long sourceFileId, long parseRunId, Path dbPath,
        boolean existingRun) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Convenience constructor for a fresh parse run (existingRun=false). */
    public IngestSummary(String fileName, String fileHash, String worksheetName,
            int rowCount, int cellCount, long sourceFileId, long parseRunId, Path dbPath) {
        this(fileName, fileHash, worksheetName, rowCount, cellCount,
                sourceFileId, parseRunId, dbPath, false);
    }

    /** Deterministic metrics payload, also stored on the parse_run row. */
    String metricsJson() {
        return metricsJson(fileName, fileHash, worksheetName, rowCount, cellCount);
    }

    static String metricsJson(String fileName, String fileHash, String worksheetName,
            int rowCount, int cellCount) {
        ObjectNode metrics = MAPPER.createObjectNode();
        metrics.put("fileName", fileName);
        metrics.put("fileHash", fileHash);
        metrics.put("worksheetName", worksheetName);
        metrics.put("rows", rowCount);
        metrics.put("cellsIn", cellCount);
        metrics.put("cellsWritten", cellCount);
        return metrics.toString();
    }

    /**
     * Reconstructs a summary from an existing parse_run metrics JSON so the no-op
     * idempotency path can report the prior result without re-parsing.
     */
    public static IngestSummary fromExistingRun(String fileName, String fileHash,
            long sourceFileId, long parseRunId, Path dbPath, String metricsJson) {
        try {
            ObjectNode metrics = (ObjectNode) MAPPER.readTree(metricsJson);
            String worksheetName = metrics.path("worksheetName").asText("");
            int rowCount = metrics.path("rows").asInt(0);
            int cellCount = metrics.path("cellsIn").asInt(0);
            return new IngestSummary(fileName, fileHash, worksheetName, rowCount, cellCount,
                    sourceFileId, parseRunId, dbPath, true);
        } catch (IOException e) {
            throw new UncheckedIOException("failed reading existing parse_run metrics", e);
        }
    }

    public void writeReport(Path report) {
        try {
            Files.writeString(report, metricsJson() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed writing parse report to " + report, e);
        }
    }
}
