package com.resurgent.tev.parser.redact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Result of a successful {@code tev-parse redact} run. */
public record RedactSummary(
        String fileName,
        String sheetName,
        Path outputPath,
        int cellsRedacted,
        int sheetsProcessed,
        List<RedactedCell> redactions) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public RedactSummary(String fileName, String sheetName, Path outputPath, int cellsRedacted) {
        this(fileName, sheetName, outputPath, cellsRedacted, 1, List.of());
    }

    public RedactSummary(String fileName, String sheetName, Path outputPath, int cellsRedacted,
            int sheetsProcessed) {
        this(fileName, sheetName, outputPath, cellsRedacted, sheetsProcessed, List.of());
    }

    public boolean allSheets() {
        return sheetsProcessed > 1 || sheetName == null;
    }

    public Path defaultReportPath() {
        String outputName = outputPath.getFileName().toString();
        String reportName = outputName.replaceFirst("(?i)-redacted\\.(xlsx|xls)$", "-redact-report.json");
        return outputPath.getParent().resolve(reportName);
    }

    public String toReportJson() {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("fileName", fileName);
            root.put("outputPath", outputPath.toString());
            root.put("cellsRedacted", cellsRedacted);
            root.put("sheetsProcessed", sheetsProcessed);
            if (sheetName != null) {
                root.put("sheetName", sheetName);
            }
            ArrayNode entries = root.putArray("redactions");
            for (RedactedCell cell : redactions) {
                ObjectNode entry = entries.addObject();
                entry.put("sheet", cell.sheetName());
                entry.put("coord", cell.coord());
                entry.put("original", cell.original());
                entry.put("redacted", cell.redacted());
                entry.put("kind", cell.kind());
            }
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (IOException e) {
            throw new UncheckedIOException("failed building redact report", e);
        }
    }

    public void writeReport(Path report) {
        try {
            Files.writeString(report, toReportJson() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed writing redact report to " + report, e);
        }
    }
}
