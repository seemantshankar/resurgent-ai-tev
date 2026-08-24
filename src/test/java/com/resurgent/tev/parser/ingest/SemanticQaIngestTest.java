package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ingest-seam tests for Sprint 3b semantic QA accounting and coverage reports.
 */
class SemanticQaIngestTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void pendingAccountedSemantics_doNotFailParse() throws Exception {
        Path db = ingest(fuzzyCivilLeaves(), "pending.xlsx");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery("SELECT status, metrics FROM parse_run")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("status")).isEqualTo("success");
            JsonNode metrics = JSON.readTree(rs.getString("metrics"));
            assertThat(metrics.get("qaFailureReasons")).isEmpty();
            assertThat(metrics.path("vocabulary").get("observed").toString()).contains("CIVIL");
            assertThat(metrics.path("vocabulary").get("unobserved").isArray()).isTrue();
            assertThat(metrics.path("vocabulary").get("unobserved").size()).isGreaterThan(0);
            assertThat(metrics.path("mappings").get("pending").asInt()).isGreaterThan(0);
            assertThat(metrics.path("totals").get("candidate").asInt()).isGreaterThan(0);
            assertThat(metrics.path("bases").get("leaf_sum").asInt()).isGreaterThan(0);
            assertThat(metrics.has("unknownRegionRatio")).isFalse();
            assertThat(metrics.has("fuzzyTruthThreshold")).isFalse();
        }
    }

    @Test
    void trustedExactCivil_reportsObservedVocabularyAndBases() throws Exception {
        Path db = ingest(civilFormulaTotal(), "trusted-report.xlsx");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery("SELECT status, metrics FROM parse_run")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("status")).isEqualTo("success");
            JsonNode metrics = JSON.readTree(rs.getString("metrics"));
            assertThat(metrics.path("vocabulary").get("observed").toString()).contains("CIVIL");
            assertThat(metrics.path("mappings").get("exact").asInt()).isGreaterThan(0);
            assertThat(metrics.path("totals").get("trusted").asInt()).isGreaterThan(0);
            assertThat(metrics.path("worksheets").isArray()).isTrue();
            assertThat(metrics.path("scratch").has("scratch")).isTrue();
            assertThat(metrics.path("duplicates").has("proposed")).isTrue();
            assertThat(metrics.path("unitCurrencyUnknowns").isNumber()).isTrue();
            assertThat(metrics.path("blockers").isArray()).isTrue();
        }
    }

    @Test
    void overlappingContributions_blockAutomaticTrustAndStayAccounted() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet assets = workbook.createSheet("Assets");
        writeCivilFormula(assets, "Civil works", "Amount (Rs.)", 100, 50);
        Sheet details = workbook.createSheet("Details");
        Row header = details.createRow(0);
        header.createCell(0).setCellValue("Civil works");
        header.createCell(1).setCellValue("Amount (Rs.)");
        details.createRow(1).createCell(0).setCellValue("Foundation");
        details.getRow(1).createCell(1).setCellFormula("Assets!B2");
        details.createRow(2).createCell(0).setCellValue("Extra");
        details.getRow(2).createCell(1).setCellValue(25.0);
        details.createRow(3).createCell(0).setCellValue("All works");
        details.getRow(3).createCell(1).setCellFormula("SUM(B2:B3)");

        Path db = ingest(workbook, "overlap.xlsx");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT status, metrics FROM parse_run")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("status")).isEqualTo("success");
            JsonNode head = costHead(JSON.readTree(rs.getString("metrics")), "CIVIL");
            assertThat(head.get("state").asText()).isNotEqualTo("trusted");
            assertThat(head.get("reasons").toString()).contains("PARTIAL_OVERLAP");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT COUNT(*) FROM review_queue WHERE category = 'cost_head_candidate'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isGreaterThan(0);
        }
    }

    @Test
    void countOverError_isAnErrorBarrierViaFullIngest() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Sheet1");
        Row row = sheet.createRow(0);
        row.createCell(0).setCellFormula("1/0");
        row.createCell(1).setCellFormula("COUNT(A1)");
        row.createCell(2).setCellFormula("B1");
        workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(row.getCell(0));

        Path db = ingest(workbook, "count-barrier.xlsx");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT is_error_barrier FROM cell WHERE coord = 'B1'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("is_error_barrier")).isTrue();
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT error_descendant FROM cell WHERE coord = 'C1'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("error_descendant")).isFalse();
            }
        }
    }

    private XSSFWorkbook civilFormulaTotal() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        writeCivilFormula(workbook.createSheet("Capex"), "Civil works", "Amount (Rs.)", 100, 50);
        return workbook;
    }

    private XSSFWorkbook fuzzyCivilLeaves() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Capex");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("civil wrks");
        header.createCell(1).setCellValue("Amount (Rs.)");
        sheet.createRow(1).createCell(0).setCellValue("Foundation");
        sheet.getRow(1).createCell(1).setCellValue(100.0);
        sheet.createRow(2).createCell(0).setCellValue("Finishes");
        sheet.getRow(2).createCell(1).setCellValue(50.0);
        return workbook;
    }

    private static void writeCivilFormula(Sheet sheet, String label, String amountHeader,
            double first, double second) {
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue(label);
        header.createCell(1).setCellValue(amountHeader);
        sheet.createRow(1).createCell(0).setCellValue("Foundation");
        sheet.getRow(1).createCell(1).setCellValue(first);
        sheet.createRow(2).createCell(0).setCellValue("Finishes");
        sheet.getRow(2).createCell(1).setCellValue(second);
        sheet.createRow(3).createCell(0).setCellValue("All works");
        sheet.getRow(3).createCell(1).setCellFormula("SUM(B2:B3)");
    }

    private Path ingest(XSSFWorkbook workbook, String name) throws Exception {
        workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
        Path file = tempDir.resolve(name);
        try (FileOutputStream out = new FileOutputStream(file.toFile())) {
            workbook.write(out);
        }
        workbook.close();
        Path db = tempDir.resolve(name.replace(".xlsx", ".db"));
        new IngestService().ingest(file, 1L, db);
        return db;
    }

    private static JsonNode costHead(JsonNode metrics, String code) {
        for (JsonNode node : metrics.get("costHeads")) {
            if (code.equals(node.get("code").asText())) {
                return node;
            }
        }
        throw new AssertionError("cost head " + code + " missing from " + metrics);
    }
}
