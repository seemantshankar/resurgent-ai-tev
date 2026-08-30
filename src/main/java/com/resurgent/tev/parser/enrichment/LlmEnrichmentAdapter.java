package com.resurgent.tev.parser.enrichment;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.Objects;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Versioned prompt and response boundary for one-tab LLM enrichment.
 */
public final class LlmEnrichmentAdapter {

    public static final String PROMPT_VERSION = "enrichment-v1";

    private final LlmEnrichmentConfig config;
    private final EnrichmentModelClient client;

    public LlmEnrichmentAdapter(LlmEnrichmentConfig config, EnrichmentModelClient client) {
        this.config = Objects.requireNonNull(config, "config");
        this.client = Objects.requireNonNull(client, "client");
    }

    public EnrichmentReport enrich(EnrichmentInput input)
            throws EnrichmentInfrastructureException {
        try {
            String prompt = buildPrompt(input);
            String response = client.generate(new EnrichmentModelRequest(
                    config.apiKey(), config.modelId(), config.endpoint(), prompt));
            EnrichmentReport report = EnrichmentReportJson.fromJson(response);
            if (!config.modelId().equals(report.modelId())) {
                throw new EnrichmentReportFormatException(
                        "modelId must match configured model " + config.modelId());
            }
            if (!PROMPT_VERSION.equals(report.promptVersion())) {
                throw new EnrichmentReportFormatException(
                        "promptVersion must be " + PROMPT_VERSION);
            }
            return report;
        } catch (Exception e) {
            throw new EnrichmentInfrastructureException(
                    "external enrichment failed: " + message(e), e);
        }
    }

    private static String buildPrompt(EnrichmentInput input) throws Exception {
        StringBuilder prompt = new StringBuilder("""
                Produce JSON matching enrichment-report-v1 for the worksheet below.
                Use one region per distinct table and assign every filled cell exactly once.
                Region purpose is Required, Scratch, or Orphan. A region referenced by a
                Required formula must be Required. Reuse synonymous entries from the type
                menu before proposing a new type. Cell roles are title, annotation,
                rowHeader, columnHeader, and amount. Only amount cells in Required regions
                receive row and column labels.
                """);
        prompt.append("Prompt version: ").append(PROMPT_VERSION).append('\n');
        prompt.append("Sheet: ").append(input.sheetName()).append('\n');
        prompt.append("Type menu: ").append(String.join(", ", input.typeMenu())).append('\n');
        prompt.append("Cells (address<TAB>value or formula):\n");

        DataFormatter formatter = new DataFormatter();
        try (InputStream stream = Files.newInputStream(input.unhiddenWorkbook());
                XSSFWorkbook workbook = new XSSFWorkbook(stream)) {
            Sheet sheet = workbook.getSheet(input.sheetName());
            if (sheet == null) {
                throw new IllegalArgumentException("sheet not found: " + input.sheetName());
            }
            for (Row row : sheet) {
                for (Cell cell : row) {
                    String value = cell.getCellType() == CellType.FORMULA
                            ? "=" + cell.getCellFormula()
                            : formatter.formatCellValue(cell);
                    if (value.isBlank()) {
                        continue;
                    }
                    String address = CellReference.convertNumToColString(cell.getColumnIndex())
                            + (cell.getRowIndex() + 1);
                    prompt.append(address)
                            .append('\t')
                            .append(value.replace("\\", "\\\\")
                                    .replace("\t", "\\t")
                                    .replace("\r", "\\r")
                                    .replace("\n", "\\n"))
                            .append('\n');
                }
            }
        }
        return prompt.toString();
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null ? exception.toString() : exception.getMessage();
    }
}
