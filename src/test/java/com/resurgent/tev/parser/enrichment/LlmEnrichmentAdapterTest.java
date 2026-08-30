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
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LlmEnrichmentAdapterTest {

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
                "stub-model",
                URI.create("https://example.invalid/v1/chat/completions"));
        LlmEnrichmentAdapter adapter = new LlmEnrichmentAdapter(config, fakeClient);

        EnrichmentReport actual = adapter.enrich(
                new EnrichmentInput(redacted, unhidden, "Project Cost", List.of("Civil Cost")));

        assertThat(actual).isEqualTo(response);
        assertThat(actual.promptVersion()).isEqualTo(LlmEnrichmentAdapter.PROMPT_VERSION);
        assertThat(fakeClient.request.apiKey()).isEqualTo("test-api-key");
        assertThat(fakeClient.request.modelId()).isEqualTo("stub-model");
        assertThat(fakeClient.request.prompt())
                .contains("Project Cost")
                .contains("Civil Cost")
                .contains("A1\tRevenue");
    }

    @Test
    void malformedModelResponseIsAnInfrastructureFailure() throws Exception {
        Path redacted = tempDir.resolve("fixture-redacted.xlsx");
        Path unhidden = tempDir.resolve("fixture-unhidden.xlsx");
        writeWorkbook(unhidden);
        LlmEnrichmentAdapter adapter = new LlmEnrichmentAdapter(
                new LlmEnrichmentConfig(
                        "test-api-key",
                        "stub-model",
                        URI.create("https://example.invalid/v1/chat/completions")),
                request -> "not-json");

        assertThatThrownBy(() -> adapter.enrich(
                new EnrichmentInput(redacted, unhidden, "Project Cost", List.of("Civil Cost"))))
                .isInstanceOf(EnrichmentInfrastructureException.class)
                .hasMessageContaining("external enrichment failed")
                .hasRootCauseInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
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
                "stub-model",
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
