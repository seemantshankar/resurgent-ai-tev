package com.resurgent.tev.parser.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RedactSummaryTest {

    @TempDir
    Path tempDir;

    @Test
    void writeReport_listsEveryRedactedCell() throws Exception {
        Path output = tempDir.resolve("Project-FM-redacted.xlsx");
        Files.createFile(output);

        RedactSummary summary = new RedactSummary(
                "Project-FM.xlsx",
                "P  L ",
                output,
                2,
                1,
                List.of(
                        new RedactedCell("P  L ", "D18", "0.4", "0.19", "numeric"),
                        new RedactedCell("P  L ", "B33", "0.4", "0.23", "numeric")));

        Path report = summary.defaultReportPath();
        assertThat(report.getFileName().toString()).isEqualTo("Project-FM-redact-report.json");

        summary.writeReport(report);
        String json = Files.readString(report);
        assertThat(json).contains("\"cellsRedacted\" : 2");
        assertThat(json).contains("\"coord\" : \"D18\"");
        assertThat(json).contains("\"original\" : \"0.4\"");
        assertThat(json).contains("\"redacted\" : \"0.19\"");
    }
}
