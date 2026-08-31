package com.resurgent.tev.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.enrichment.EnrichmentModelClient;
import com.resurgent.tev.parser.db.LegacyWorkspaceFactory;
import com.resurgent.tev.parser.enrichment.EnrichmentReport;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.Cell;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.CellRole;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.Region;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.RegionPurpose;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.TypeMenu;
import com.resurgent.tev.parser.enrichment.EnrichmentReportJson;
import com.resurgent.tev.parser.enrichment.LlmEnrichmentAdapter;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnrichCommandTest {

    private static final String FIXTURE_MODEL_ID = "fixture-enrichment-model";

    @TempDir
    Path tempDir;

    @Test
    void autoIngestsAndWritesCleanOneTabReportWithStubbedModel() throws Exception {
        Path input = writeWorkbook();
        Path db = tempDir.resolve("workspace.db");
        Path outputDir = tempDir.resolve("enriched");
        Path config = writeConfig();
        Path env = writeEnv();
        EnrichmentModelClient stub = request -> EnrichmentReportJson.toJson(cleanResponse());
        byte[] originalBytes = Files.readAllBytes(input);

        RunResult result = run(stub,
                "enrich",
                "--input", input.toString(),
                "--db", db.toString(),
                "--mandate-id", "1",
                "--sheet", "Project Cost",
                "--output-dir", outputDir.toString(),
                "--config", config.toString(),
                "--env", env.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout())
                .contains("Auto-ingested")
                .contains("Enriched sheet 'Project Cost'")
                .contains("1 region")
                .contains("0 problems");
        Path reportPath = outputDir.resolve("fixture-enrichment-report.json");
        Path redactedPath = outputDir.resolve("fixture-redacted.xlsx");
        assertThat(result.stderr()).contains("enrichment report written to " + reportPath);
        assertThat(reportPath).exists();
        assertThat(redactedPath).exists();
        assertThat(Files.readAllBytes(input)).isEqualTo(originalBytes);
        try (XSSFWorkbook redacted = new XSSFWorkbook(redactedPath.toFile())) {
            assertThat(redacted.getSheet("Project Cost").getRow(1).getZeroHeight()).isTrue();
            assertThat(redacted.getSheet("Project Cost").isColumnHidden(1)).isTrue();
        }

        EnrichmentReport report = EnrichmentReportJson.read(reportPath);
        assertThat(report.problems()).isEmpty();
        assertThat(report.regions()).hasSize(1);
        assertThat(report.modelId()).isEqualTo(FIXTURE_MODEL_ID);
        assertThat(Files.exists(Path.of(report.unhiddenTempPath()))).isFalse();
    }

    @Test
    void writesOverrideReportAndExitsThreeWhenQaFindsProblems() throws Exception {
        Path input = writeWorkbook();
        Path reportPath = tempDir.resolve("reports/dirty.json");
        Path env = writeEnv();
        EnrichmentModelClient stub =
                request -> EnrichmentReportJson.toJson(overlappingResponse());

        RunResult result = run(stub,
                "enrich",
                "--input", input.toString(),
                "--db", tempDir.resolve("dirty.db").toString(),
                "--mandate-id", "1",
                "--sheet", "Project Cost",
                "--output-dir", tempDir.resolve("dirty-output").toString(),
                "--report", reportPath.toString(),
                "--config", writeConfig().toString(),
                "--env", env.toString());

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(reportPath).exists();
        EnrichmentReport report = EnrichmentReportJson.read(reportPath);
        assertThat(report.problems())
                .extracting(EnrichmentReport.Problem::code)
                .containsExactly(EnrichmentReport.ProblemCode.OVERLAP);
        assertThat(result.stdout()).contains("1 problems");
        assertThat(result.stderr()).contains(reportPath.toString());
    }

    @Test
    void preservesAnExistingStandardRedactedExport() throws Exception {
        Path input = writeWorkbook();
        Path outputDir = tempDir.resolve("existing-output");
        Files.createDirectories(outputDir);
        Path existingRedacted = outputDir.resolve("fixture-redacted.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Do not replace").createRow(0).createCell(0)
                    .setCellValue("existing artifact");
            try (OutputStream output = Files.newOutputStream(existingRedacted)) {
                workbook.write(output);
            }
        }
        byte[] existingBytes = Files.readAllBytes(existingRedacted);

        RunResult result = run(
                request -> EnrichmentReportJson.toJson(cleanResponse()),
                "enrich",
                "--input", input.toString(),
                "--db", tempDir.resolve("preserve.db").toString(),
                "--mandate-id", "1",
                "--sheet", "Project Cost",
                "--output-dir", outputDir.toString(),
                "--config", writeConfig().toString(),
                "--env", writeEnv().toString());

        assertThat(result.exitCode()).isZero();
        assertThat(Files.readAllBytes(existingRedacted)).isEqualTo(existingBytes);
    }

    @Test
    void ingestGateRejectionExitsThree() throws Exception {
        Path input = writeWorkbook();
        Path db = tempDir.resolve("legacy.db");
        LegacyWorkspaceFactory.writePopulatedV10(db);

        RunResult result = run(
                request -> {
                    throw new AssertionError("model must not run after a gate rejection");
                },
                "enrich",
                "--input", input.toString(),
                "--db", db.toString(),
                "--mandate-id", "1",
                "--sheet", "Project Cost",
                "--output-dir", tempDir.resolve("gate-output").toString(),
                "--config", writeConfig().toString(),
                "--env", writeEnv().toString());

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stderr())
                .contains("Refusing to apply the Sprint 3b schema reset")
                .contains(db.toAbsolutePath().normalize().toString());
    }

    private RunResult run(EnrichmentModelClient client, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = Main.commandLine(client)
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute(args);
        return new RunResult(exit, out.toString(), err.toString());
    }

    private Path writeWorkbook() throws Exception {
        Path input = tempDir.resolve("fixture.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Project Cost");
            sheet.createRow(0).createCell(0).setCellValue("Revenue");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("Sales");
            row.createCell(1).setCellValue(100.0);
            row.setZeroHeight(true);
            sheet.setColumnHidden(1, true);
            try (OutputStream output = Files.newOutputStream(input)) {
                workbook.write(output);
            }
        }
        return input;
    }

    private Path writeConfig() throws Exception {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, """
                {
                  "llmApiKey": "test-key",
                  "llmEndpoint": "https://example.invalid/chat"
                }
                """);
        return config;
    }

    private Path writeEnv() throws Exception {
        Path env = tempDir.resolve("enrichment.env");
        Files.writeString(env, """
                OPENROUTER_API_KEY=test-key
                Excel_Enrichment_Model_id=%s
                """.formatted(FIXTURE_MODEL_ID));
        return env;
    }

    private static EnrichmentReport cleanResponse() {
        return new EnrichmentReport(
                EnrichmentReport.VERSION,
                "fixture.xlsx",
                "Project Cost",
                "/stub/redacted.xlsx",
                "/stub/unhidden.xlsx",
                FIXTURE_MODEL_ID,
                LlmEnrichmentAdapter.PROMPT_VERSION,
                new TypeMenu(List.of("Sales"), List.of()),
                List.of(new Region(
                        "sales",
                        "A1:B2",
                        "Sales",
                        "Sales",
                        RegionPurpose.REQUIRED,
                        List.of(
                                new Cell("A1", CellRole.TITLE, null, null, null, null),
                                new Cell("A2", CellRole.ROW_HEADER, null, null, null, null),
                                new Cell("B2", CellRole.AMOUNT, "Sales", null, "Year 1", null)),
                        List.of())),
                List.of());
    }

    private static EnrichmentReport overlappingResponse() {
        EnrichmentReport clean = cleanResponse();
        Region overlapping = new Region(
                "overlapping",
                "B1:C2",
                "Overlapping Sales",
                "Sales",
                RegionPurpose.REQUIRED,
                List.of(new Cell("B2", CellRole.AMOUNT, "Sales", null, "Year 1", null)),
                List.of());
        return new EnrichmentReport(
                clean.version(),
                clean.fileName(),
                clean.sheetName(),
                clean.redactedInputPath(),
                clean.unhiddenTempPath(),
                clean.modelId(),
                clean.promptVersion(),
                clean.typeMenu(),
                List.of(clean.regions().getFirst(), overlapping),
                List.of());
    }

    private record RunResult(int exitCode, String stdout, String stderr) {}
}
