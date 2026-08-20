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
        int rowCount, int cellCount, long sourceFileId, long parseRunId, Path dbPath) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    public void writeReport(Path report) {
        try {
            Files.writeString(report, metricsJson() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed writing parse report to " + report, e);
        }
    }
}
