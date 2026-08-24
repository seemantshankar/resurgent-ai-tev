package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.parser.config.ParserConfig;
import com.resurgent.tev.parser.review.ReviewService;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ingest-seam tests for automatic trust gates, reports, and QA.
 */
class TrustIngestTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void passingGates_markCandidateAutomaticallyTrustedWithoutReviewQueue() throws Exception {
        Path db = ingest(civilFormulaTotal("Civil works", "Amount (Rs.)"), "trusted.xlsx");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT automatic_trust_eligible, reasons FROM cost_head_candidate")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("automatic_trust_eligible")).isEqualTo(1);
                String reasons = rs.getString("reasons");
                assertThat(reasons).contains("GATE_MAPPING_PASS");
                assertThat(reasons).contains("GATE_BASIS_PASS");
                assertThat(reasons).contains("GATE_UNIT_CURRENCY_PASS");
                assertThat(reasons).contains("GATE_COVERAGE_PASS");
                assertThat(reasons).contains("GATE_CACHE_NUMERIC_PASS");
                assertThat(reasons).contains("GATE_BLOCKERS_PASS");
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM review_queue WHERE category = 'cost_head_candidate'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
            }
            try (ResultSet rs = c.createStatement().executeQuery("SELECT status FROM parse_run")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("success");
            }
            JsonNode head = costHead(metrics(c), "CIVIL");
            assertThat(head.get("state").asText()).isEqualTo("trusted");
            assertThat(head.get("source").asText()).isEqualTo("automatic");
            assertThat(head.get("reviewStatus").asText()).isEqualTo("none");
        }
    }

    @Test
    void fuzzyMapping_failsMappingGate() throws Exception {
        Path db = ingest(civilFormulaTotal("civil wrks", "Amount (Rs.)"), "fuzzy-map.xlsx");
        assertGateFailure(db, "GATE_MAPPING_FAIL");
    }

    @Test
    void leafSumOnly_failsBasisGateAndStaysInReview() throws Exception {
        Path db = ingest(civilLeavesOnly("Civil works", "Amount (Rs.)"), "leaf-sum.xlsx");
        assertGateFailure(db, "GATE_BASIS_FAIL");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT COUNT(*) FROM review_queue WHERE category = 'cost_head_candidate'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isGreaterThan(0);
        }
    }

    @Test
    void unknownUnit_failsUnitCurrencyGate() throws Exception {
        Path db = ingest(civilFormulaTotal("Civil works", "Amount"), "unknown-unit.xlsx");
        assertGateFailure(db, "GATE_UNIT_CURRENCY_FAIL");
    }

    @Test
    void unresolvedDuplicate_failsBlockersGate() throws Exception {
        Path db;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            writeCivilFormula(workbook.createSheet("Assets"), "Civil works", "Amount (Rs.)");
            writeCivilFormula(workbook.createSheet("Details"), "Civil works", "Amount (Rs.)");
            db = ingest(workbook, "dup.xlsx");
        }
        assertGateFailure(db, "GATE_BLOCKERS_FAIL");
    }

    @Test
    void staleCache_failsCacheNumericGate() throws Exception {
        Path db;
        try (XSSFWorkbook workbook = civilFormulaTotal("Civil works", "Amount (Rs.)")) {
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            workbook.setForceFormulaRecalculation(true);
            db = ingest(workbook, "stale-cache.xlsx", false);
        }
        assertGateFailure(db, "GATE_CACHE_NUMERIC_FAIL");
    }

    @Test
    void pendingManual_failsBlockersGateOnReingest() throws Exception {
        Path file = writeWorkbook(civilFormulaTotal("Civil works", "Amount (Rs.)"), "pending-manual.xlsx");
        Path db = tempDir.resolve("pending-manual.db");
        new IngestService().ingest(file, 1L, db);
        ReviewService review = new ReviewService();
        review.addManual(db, "CIVIL", new BigDecimal("25.00"), "rs", "INR",
                "analyst", "contingency", contributionId(db));

        new IngestService().ingest(file, 1L, db, reparseConfig());
        assertGateFailure(db, "GATE_BLOCKERS_FAIL");
    }

    @Test
    void acceptedMapping_doesNotTrustALeafSumTotal() throws Exception {
        Path file = writeWorkbook(civilLeavesOnly("civil wrks", "Amount (Rs.)"), "map-only.xlsx");
        Path db = tempDir.resolve("map-only.db");
        new IngestService().ingest(file, 1L, db);
        ReviewService review = new ReviewService();
        ReviewService.MappingReviewItem mapping = review.listPendingMappings(db).getFirst();
        review.acceptMapping(db, mapping.reviewQueueId(), "analyst", "civil");

        new IngestService().ingest(file, 1L, db, reparseConfig());

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT automatic_trust_eligible, reasons FROM cost_head_candidate"
                                + " ORDER BY cost_head_candidate_id DESC LIMIT 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("reasons")).contains("GATE_MAPPING_PASS");
            assertThat(rs.getString("reasons")).contains("GATE_BASIS_FAIL");
            assertThat(rs.getInt("automatic_trust_eligible")).isEqualTo(0);
        }
    }

    @Test
    void automaticEligibleWithFailedGate_isQaFailure() {
        TrustEvaluator.Verdict verdict = new TrustEvaluator.Verdict(
                true,
                "trusted",
                "automatic",
                List.of(new TrustEvaluator.Gate("mapping", false)),
                List.of("GATE_MAPPING_FAIL"));
        assertThat(TrustQa.reasons(List.of(verdict)))
                .anyMatch(reason -> reason.contains("automatic_trust_failed_gate"));
    }

    private void assertGateFailure(Path db, String failedGate) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT automatic_trust_eligible, reasons FROM cost_head_candidate"
                                + " ORDER BY cost_head_candidate_id DESC LIMIT 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("automatic_trust_eligible")).isEqualTo(0);
            assertThat(rs.getString("reasons")).contains(failedGate);
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            JsonNode head = costHead(metrics(c), "CIVIL");
            assertThat(head.get("state").asText()).isNotEqualTo("trusted");
        }
    }

    private XSSFWorkbook civilFormulaTotal(String label, String amountHeader) {
        XSSFWorkbook workbook = new XSSFWorkbook();
        writeCivilFormula(workbook.createSheet("Capex"), label, amountHeader);
        return workbook;
    }

    private XSSFWorkbook civilLeavesOnly(String label, String amountHeader) {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Capex");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue(label);
        header.createCell(1).setCellValue(amountHeader);
        sheet.createRow(1).createCell(0).setCellValue("Foundation");
        sheet.getRow(1).createCell(1).setCellValue(100.0);
        sheet.createRow(2).createCell(0).setCellValue("Finishes");
        sheet.getRow(2).createCell(1).setCellValue(50.0);
        return workbook;
    }

    private static void writeCivilFormula(Sheet sheet, String label, String amountHeader) {
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue(label);
        header.createCell(1).setCellValue(amountHeader);
        sheet.createRow(1).createCell(0).setCellValue("Foundation");
        sheet.getRow(1).createCell(1).setCellValue(100.0);
        sheet.createRow(2).createCell(0).setCellValue("Finishes");
        sheet.getRow(2).createCell(1).setCellValue(50.0);
        sheet.createRow(3).createCell(0).setCellValue("All works");
        sheet.getRow(3).createCell(1).setCellFormula("SUM(B2:B3)");
    }

    private Path ingest(XSSFWorkbook workbook, String name) throws Exception {
        return ingest(workbook, name, true);
    }

    private Path ingest(XSSFWorkbook workbook, String name, boolean evaluate) throws Exception {
        Path db = tempDir.resolve(name.replace(".xlsx", ".db"));
        new IngestService().ingest(writeWorkbook(workbook, name, evaluate), 1L, db);
        workbook.close();
        return db;
    }

    private Path writeWorkbook(XSSFWorkbook workbook, String name) throws Exception {
        return writeWorkbook(workbook, name, true);
    }

    private Path writeWorkbook(XSSFWorkbook workbook, String name, boolean evaluate) throws Exception {
        if (evaluate) {
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
        }
        Path file = tempDir.resolve(name);
        try (FileOutputStream out = new FileOutputStream(file.toFile())) {
            workbook.write(out);
        }
        return file;
    }

    private static ParserConfig reparseConfig() {
        return new ParserConfig(
                100L * 1024 * 1024, 200, 1_000_000, 16_384, 5_000_000L, 100,
                false, true, true, 4, 4);
    }

    private static long contributionId(Path db) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT cost_head_contribution_id FROM cost_head_contribution")) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    private static JsonNode metrics(Connection c) throws Exception {
        try (ResultSet rs = c.createStatement().executeQuery(
                "SELECT metrics FROM parse_run ORDER BY parse_run_id DESC LIMIT 1")) {
            assertThat(rs.next()).isTrue();
            return JSON.readTree(rs.getString(1));
        }
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
