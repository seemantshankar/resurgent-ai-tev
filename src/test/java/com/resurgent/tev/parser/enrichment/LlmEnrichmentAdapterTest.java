package com.resurgent.tev.parser.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resurgent.tev.parser.enrichment.EnrichmentReport.Cell;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.CellRole;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.Region;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.RegionPurpose;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.TypeMenu;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LlmEnrichmentAdapterTest {

    private static final String FIXTURE_MODEL_ID = "fixture-enrichment-model";

    @TempDir
    Path tempDir;

    @Test
    void configuredAdapterReturnsVersionedParsedReportWithoutLiveNetwork() throws Exception {
        Path redacted = tempDir.resolve("fixture-redacted.xlsx");
        Path unhidden = tempDir.resolve("fixture-unhidden.xlsx");
        writeWorkbook(unhidden);
        EnrichmentReport response = fixtureReport(redacted, unhidden);
        CapturingClient fakeClient = new CapturingClient(EnrichmentReportJson.toJson(response));
        LlmEnrichmentConfig config = new LlmEnrichmentConfig(
                "test-api-key",
                FIXTURE_MODEL_ID,
                URI.create("https://example.invalid/v1/chat/completions"),
                null,
                "tev-parse",
                LlmEnrichmentConfigLoader.DEFAULT_MAX_OUTPUT_TOKENS);
        LlmEnrichmentAdapter adapter = new LlmEnrichmentAdapter(config, fakeClient);

        EnrichmentReport actual = adapter.enrich(
                new EnrichmentInput(redacted, unhidden, "Project Cost", List.of("Civil Cost")));

        assertThat(actual).isEqualTo(response);
        assertThat(actual.promptVersion()).isEqualTo(LlmEnrichmentAdapter.PROMPT_VERSION);
        assertThat(fakeClient.request.apiKey()).isEqualTo("test-api-key");
        assertThat(fakeClient.request.maxOutputTokens())
                .isEqualTo(LlmEnrichmentConfigLoader.DEFAULT_MAX_OUTPUT_TOKENS);
        assertThat(fakeClient.request.prompt())
                .contains("Project Cost")
                .contains("Civil Cost")
                .contains("Sparse grid")
                .contains("A1:Revenue")
                .contains("Cell index (NDJSON")
                .doesNotContain("Island hints");
    }

    @Test
    void promptOmitsBlankCellsAndReportsFilledCount() throws Exception {
        Path unhidden = tempDir.resolve("mixed.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Project Cost");
            sheet.createRow(0).createCell(0).setCellValue("Revenue");
            sheet.getRow(0).createCell(1);
            try (OutputStream output = Files.newOutputStream(unhidden)) {
                workbook.write(output);
            }
        }
        CapturingClient fakeClient = new CapturingClient(
                EnrichmentReportJson.toJson(fixtureReport(tempDir.resolve("r.xlsx"), unhidden)));
        LlmEnrichmentAdapter adapter = new LlmEnrichmentAdapter(
                new LlmEnrichmentConfig(
                        "test-api-key",
                        FIXTURE_MODEL_ID,
                        URI.create("https://example.invalid/v1/chat/completions"),
                        null,
                        "tev-parse",
                        LlmEnrichmentConfigLoader.DEFAULT_MAX_OUTPUT_TOKENS),
                fakeClient);

        adapter.enrich(new EnrichmentInput(
                tempDir.resolve("r.xlsx"), unhidden, "Project Cost", List.of("Civil Cost")));

        assertThat(fakeClient.request.prompt())
                .contains("Filled cell count: 1")
                .contains("Sparse grid")
                .contains("A1:Revenue")
                .doesNotContain("B1:");
    }

    @Test
    void regionsOnlyPromptIncludesExampleAndOmitsPerCellInstructions() throws Exception {
        Path unhidden = tempDir.resolve("mixed.xlsx");
        writeWorkbook(unhidden);
        CapturingClient fakeClient = new CapturingClient(
                EnrichmentReportJson.toJson(regionsOnlyFixture(tempDir.resolve("r.xlsx"), unhidden)));
        LlmEnrichmentAdapter adapter = new LlmEnrichmentAdapter(
                new LlmEnrichmentConfig(
                        "test-api-key",
                        FIXTURE_MODEL_ID,
                        URI.create("https://example.invalid/v1/chat/completions"),
                        null,
                        "tev-parse",
                        LlmEnrichmentConfigLoader.DEFAULT_MAX_OUTPUT_TOKENS),
                fakeClient);

        adapter.enrich(new EnrichmentInput(
                tempDir.resolve("r.xlsx"),
                unhidden,
                "Project Cost",
                List.of("Civil Cost"),
                EnrichmentPromptMode.REGIONS_ONLY));

        assertThat(fakeClient.request.prompt())
                .contains("Return only valid JSON matching enrichment-report-v1")
                .contains("\"promptVersion\": \"enrichment-v2.4-regions-only\"")
                .contains("\"cells\": []")
                .contains("regions-only pass")
                .doesNotContain("list every filled cell exactly once in region.cells");
    }

    @Test
    void promptUsesLabelsAndFormulasNotIslandHints() throws Exception {
        Path unhidden = tempDir.resolve("mixed.xlsx");
        writeWorkbook(unhidden);
        CapturingClient fakeClient = new CapturingClient(
                EnrichmentReportJson.toJson(regionsOnlyFixture(tempDir.resolve("r.xlsx"), unhidden)));
        LlmEnrichmentAdapter adapter = new LlmEnrichmentAdapter(
                new LlmEnrichmentConfig(
                        "test-api-key",
                        FIXTURE_MODEL_ID,
                        URI.create("https://example.invalid/v1/chat/completions"),
                        null,
                        "tev-parse",
                        LlmEnrichmentConfigLoader.DEFAULT_MAX_OUTPUT_TOKENS),
                fakeClient);

        adapter.enrich(new EnrichmentInput(
                tempDir.resolve("r.xlsx"),
                unhidden,
                "Project Cost",
                List.of("Civil Cost"),
                EnrichmentPromptMode.REGIONS_ONLY));

        assertThat(fakeClient.request.prompt())
                .contains("Prompt version: enrichment-v2.4-regions-only")
                .contains("Decide regions from labels and formulas")
                .contains("unless a formula in a main")
                .contains("O47:V48")
                .doesNotContain("computed from a Required table")
                .doesNotContain("Island hints")
                .doesNotContain("island-");
    }

    @Test
    void regionsOnlyResponseUsesRegionsOnlyPromptVersion() throws Exception {
        Path redacted = tempDir.resolve("fixture-redacted.xlsx");
        Path unhidden = tempDir.resolve("fixture-unhidden.xlsx");
        writeWorkbook(unhidden);
        EnrichmentReport response = regionsOnlyFixture(redacted, unhidden);
        LlmEnrichmentAdapter adapter = new LlmEnrichmentAdapter(
                new LlmEnrichmentConfig(
                        "test-api-key",
                        FIXTURE_MODEL_ID,
                        URI.create("https://example.invalid/v1/chat/completions"),
                        null,
                        "tev-parse",
                        LlmEnrichmentConfigLoader.DEFAULT_MAX_OUTPUT_TOKENS),
                new CapturingClient(EnrichmentReportJson.toJson(response)));

        EnrichmentReport actual = adapter.enrich(new EnrichmentInput(
                redacted, unhidden, "Project Cost", List.of("Civil Cost"), EnrichmentPromptMode.REGIONS_ONLY));

        assertThat(actual.promptVersion()).isEqualTo(LlmEnrichmentAdapter.PROMPT_VERSION_REGIONS_ONLY);
    }

    @Test
    void repairPromptCropsToLeftoversAndNearbyRegions() throws Exception {
        Path unhidden = tempDir.resolve("repair.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Project Cost");
            sheet.createRow(0).createCell(0).setCellValue("Revenue");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("Sales");
            row.createCell(1).setCellValue(100.0);
            sheet.createRow(19).createCell(5).setCellValue("Far");
            try (OutputStream output = Files.newOutputStream(unhidden)) {
                workbook.write(output);
            }
        }
        Path redacted = tempDir.resolve("r.xlsx");
        EnrichmentReport response = new EnrichmentReport(
                EnrichmentReport.VERSION,
                "fixture.xlsx",
                "Project Cost",
                redacted.toString(),
                unhidden.toString(),
                FIXTURE_MODEL_ID,
                LlmEnrichmentAdapter.PROMPT_VERSION_REPAIR,
                new TypeMenu(List.of("Civil Cost"), List.of()),
                List.of(new Region(
                        "sales",
                        "A1:B2",
                        "Sales",
                        "Civil Cost",
                        RegionPurpose.REQUIRED,
                        List.of(
                                new Cell("A1", CellRole.TITLE, null, null, null, null),
                                new Cell("A2", CellRole.ROW_HEADER, null, null, null, null),
                                new Cell("B2", CellRole.AMOUNT, "Sales", null, "Year 1", null)),
                        List.of())),
                List.of());
        CapturingClient fakeClient = new CapturingClient(EnrichmentReportJson.toJson(response));
        LlmEnrichmentAdapter adapter = new LlmEnrichmentAdapter(
                new LlmEnrichmentConfig(
                        "test-api-key",
                        FIXTURE_MODEL_ID,
                        URI.create("https://example.invalid/v1/chat/completions"),
                        null,
                        "tev-parse",
                        LlmEnrichmentConfigLoader.DEFAULT_MAX_OUTPUT_TOKENS),
                fakeClient);
        RepairWindow window = new RepairWindow(
                List.of("A2", "B2"),
                List.of(new Region(
                        "sales",
                        "A1:A1",
                        "Sales",
                        "Sales",
                        RegionPurpose.REQUIRED,
                        List.of(),
                        List.of())),
                Set.of("A1", "A2", "B2"));

        EnrichmentReport actual = adapter.repair(new EnrichmentRepairInput(
                redacted,
                unhidden,
                "Project Cost",
                List.of("Civil Cost"),
                EnrichmentPromptMode.FULL,
                window));

        assertThat(actual.promptVersion()).isEqualTo(LlmEnrichmentAdapter.PROMPT_VERSION_REPAIR);
        assertThat(fakeClient.request.prompt())
                .contains("This is a repair pass")
                .contains("Leftover filled cells")
                .contains("A2, B2")
                .contains("Nearby regions to replace or expand")
                .contains("\"id\":\"sales\"")
                .contains("Prompt version: enrichment-v2.4-repair")
                .contains("A1:Revenue")
                .contains("A2:Sales")
                .doesNotContain("F20")
                .doesNotContain("Island hints")
                .doesNotContain("island-");
    }

    @Test
    void malformedModelResponseIsAnInfrastructureFailure() throws Exception {
        Path redacted = tempDir.resolve("fixture-redacted.xlsx");
        Path unhidden = tempDir.resolve("fixture-unhidden.xlsx");
        writeWorkbook(unhidden);
        LlmEnrichmentAdapter adapter = new LlmEnrichmentAdapter(
                new LlmEnrichmentConfig(
                        "test-api-key",
                        FIXTURE_MODEL_ID,
                        URI.create("https://example.invalid/v1/chat/completions"),
                        null,
                        "tev-parse",
                        LlmEnrichmentConfigLoader.DEFAULT_MAX_OUTPUT_TOKENS),
                request -> "not-json");

        assertThatThrownBy(() -> adapter.enrich(
                new EnrichmentInput(redacted, unhidden, "Project Cost", List.of("Civil Cost"))))
                .isInstanceOf(EnrichmentInfrastructureException.class)
                .hasMessageContaining("external enrichment failed")
                .hasRootCauseInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    private static EnrichmentReport regionsOnlyFixture(Path redacted, Path unhidden) {
        return new EnrichmentReport(
                EnrichmentReport.VERSION,
                "fixture.xlsx",
                "Project Cost",
                redacted.toString(),
                unhidden.toString(),
                FIXTURE_MODEL_ID,
                LlmEnrichmentAdapter.PROMPT_VERSION_REGIONS_ONLY,
                new TypeMenu(List.of("Civil Cost"), List.of()),
                List.of(new Region(
                        "civil",
                        "A1:A1",
                        "Civil Cost",
                        "Civil Cost",
                        RegionPurpose.REQUIRED,
                        List.of(),
                        List.of())),
                List.of());
    }

    private static void writeWorkbook(Path path) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Project Cost").createRow(0).createCell(0)
                    .setCellValue("Revenue");
            try (OutputStream output = Files.newOutputStream(path)) {
                workbook.write(output);
            }
        }
    }

    private static EnrichmentReport fixtureReport(Path redacted, Path unhidden) {
        return new EnrichmentReport(
                EnrichmentReport.VERSION,
                "fixture.xlsx",
                "Project Cost",
                redacted.toString(),
                unhidden.toString(),
                FIXTURE_MODEL_ID,
                LlmEnrichmentAdapter.PROMPT_VERSION,
                new TypeMenu(List.of("Civil Cost"), List.of()),
                List.of(new Region(
                        "civil",
                        "A1:A1",
                        "Civil Cost",
                        "Civil Cost",
                        RegionPurpose.REQUIRED,
                        List.of(new Cell("A1", CellRole.TITLE, null, null, null, null)),
                        List.of())),
                List.of());
    }

    private static final class CapturingClient implements EnrichmentModelClient {
        private final String response;
        private EnrichmentModelRequest request;

        private CapturingClient(String response) {
            this.response = response;
        }

        @Override
        public String generate(EnrichmentModelRequest request) {
            this.request = request;
            return response;
        }
    }
}
