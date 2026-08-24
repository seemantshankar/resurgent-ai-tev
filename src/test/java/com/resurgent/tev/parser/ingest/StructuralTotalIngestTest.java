package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ingest-seam tests for unlabeled structural totals and leaf-sum fallbacks.
 */
class StructuralTotalIngestTest {

    @TempDir
    Path tempDir;

    @Test
    void unlabeledExactSum_isStructuralTotalWithLeafCoverage() throws Exception {
        Path db;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Capex");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Civil works");
            header.createCell(1).setCellValue("Amount (Rs.)");
            sheet.createRow(1).createCell(0).setCellValue("Foundation");
            sheet.getRow(1).createCell(1).setCellValue(100.0);
            sheet.createRow(2).createCell(0).setCellValue("Finishes");
            sheet.getRow(2).createCell(1).setCellValue(50.0);
            sheet.createRow(3).createCell(0).setCellValue("All works");
            sheet.getRow(3).createCell(1).setCellFormula("SUM(B2:B3)");
            db = ingest(workbook, "exact-sum.xlsx");
        }

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT ch.basis, ch.source_amount, cand.automatic_trust_eligible, ch.reasons"
                            + " FROM cost_head_contribution ch"
                            + " JOIN cost_head_candidate cand ON cand.cost_head_candidate_id"
                            + " = ch.cost_head_candidate_id")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("basis")).isEqualTo("structural_total");
                assertThat(rs.getDouble("source_amount")).isEqualTo(150.0);
                assertThat(rs.getInt("automatic_trust_eligible")).isEqualTo(0);
                assertThat(rs.getString("reasons")).contains("STRUCTURAL_TOTAL");
                assertThat(rs.next()).isFalse();
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT c.coord FROM cell c WHERE c.cell_id = ("
                            + "SELECT anchor_cell_id FROM cost_head_contribution)")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("B4");
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT c.coord, cc.participation FROM cost_head_contribution_cell cc"
                            + " JOIN cell c ON c.cell_id = cc.cell_id ORDER BY c.coord")) {
                List<String> included = new ArrayList<>();
                List<String> excluded = new ArrayList<>();
                while (rs.next()) {
                    if ("included".equals(rs.getString("participation"))) {
                        included.add(rs.getString("coord"));
                    } else {
                        excluded.add(rs.getString("coord"));
                    }
                }
                assertThat(included).containsExactly("B2", "B3");
                assertThat(excluded).contains("B4");
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM review_queue WHERE category = 'cost_head_candidate'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }
        }
    }

    @Test
    void unlabeledDirectArithmeticAndNonSumFunctions_remainLeafSumReviewCandidates() throws Exception {
        Path arithmetic = ingest(unlabeledFormula("B2+B3"), "arithmetic.xlsx");
        Path subtotal = ingest(unlabeledFormula("SUBTOTAL(9,B2:B3)"), "subtotal.xlsx");
        Path round = ingest(unlabeledFormula("ROUND(SUM(B2:B3),0)"), "round.xlsx");
        assertLeafSumReview(arithmetic, 150.0, "STRUCTURAL_SHAPE_NOT_SUM");
        assertLeafSumReview(subtotal, 150.0, "STRUCTURAL_SHAPE_NOT_SUM");
        assertLeafSumReview(round, 150.0, "STRUCTURAL_SHAPE_NOT_SUM");
    }

    @Test
    void skippedAndDuplicateLeaves_preventStructuralQualification() throws Exception {
        Path skipped;
        try (XSSFWorkbook workbook = civilAmountHeader()) {
            Sheet sheet = workbook.getSheetAt(0);
            sheet.createRow(1).createCell(0).setCellValue("Foundation");
            sheet.getRow(1).createCell(1).setCellValue(100.0);
            sheet.createRow(2).createCell(0).setCellValue("Finishes");
            sheet.getRow(2).createCell(1).setCellValue(50.0);
            sheet.createRow(3).createCell(0).setCellValue("Roof");
            sheet.getRow(3).createCell(1).setCellValue(10.0);
            sheet.createRow(4).createCell(0).setCellValue("All works");
            sheet.getRow(4).createCell(1).setCellFormula("SUM(B2:B3)");
            skipped = ingest(workbook, "skipped.xlsx");
        }
        assertLeafSumReview(skipped, 160.0, "STRUCTURAL_SKIPPED_LEAF");

        Path duplicate;
        try (XSSFWorkbook workbook = civilAmountHeader()) {
            Sheet sheet = workbook.getSheetAt(0);
            sheet.createRow(1).createCell(0).setCellValue("Foundation");
            sheet.getRow(1).createCell(1).setCellValue(100.0);
            sheet.createRow(2).createCell(0).setCellValue("Finishes");
            sheet.getRow(2).createCell(1).setCellValue(50.0);
            sheet.createRow(3).createCell(0).setCellValue("All works");
            sheet.getRow(3).createCell(1).setCellFormula("SUM(B2:B3,B2)");
            duplicate = ingest(workbook, "duplicate.xlsx");
        }
        assertLeafSumReview(duplicate, 150.0, "STRUCTURAL_DUPLICATE_LEAF");
    }

    @Test
    void staleAndMissingCache_preventStructuralQualification() throws Exception {
        Path stale;
        try (XSSFWorkbook workbook = unlabeledFormula("SUM(B2:B3)")) {
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            workbook.setForceFormulaRecalculation(true);
            stale = ingest(workbook, "stale.xlsx", false);
        }
        assertLeafSumReview(stale, 150.0, "STRUCTURAL_STALE_CACHE");

        Path missing;
        try (XSSFWorkbook workbook = unlabeledFormula("SUM(B2:B3)")) {
            missing = ingest(workbook, "missing.xlsx", false);
        }
        assertLeafSumReview(missing, 150.0, "STRUCTURAL_MISSING_CACHE");
    }

    @Test
    void unknownUnitOrCurrency_preventsStructuralQualification() throws Exception {
        Path db;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Capex");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Civil works");
            header.createCell(1).setCellValue("Amount");
            sheet.createRow(1).createCell(0).setCellValue("Foundation");
            sheet.getRow(1).createCell(1).setCellValue(100.0);
            sheet.createRow(2).createCell(0).setCellValue("Finishes");
            sheet.getRow(2).createCell(1).setCellValue(50.0);
            sheet.createRow(3).createCell(0).setCellValue("All works");
            sheet.getRow(3).createCell(1).setCellFormula("SUM(B2:B3)");
            db = ingest(workbook, "unknown-unit.xlsx");
        }
        assertLeafSumReview(db, 150.0, "STRUCTURAL_UNKNOWN_UNIT");
    }

    @Test
    void crossRegionSum_preventsStructuralQualification() throws Exception {
        Path db;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet civil = workbook.createSheet("Civil");
            Row header = civil.createRow(0);
            header.createCell(0).setCellValue("Civil works");
            header.createCell(1).setCellValue("Amount (Rs.)");
            civil.createRow(1).createCell(0).setCellValue("Foundation");
            civil.getRow(1).createCell(1).setCellValue(100.0);
            civil.createRow(2).createCell(0).setCellValue("All works");
            civil.getRow(2).createCell(1).setCellFormula("SUM(B2,MoreCivil!B2)");

            Sheet more = workbook.createSheet("MoreCivil");
            more.createRow(0).createCell(0).setCellValue("Civil works");
            more.getRow(0).createCell(1).setCellValue("Amount (Rs.)");
            more.createRow(1).createCell(0).setCellValue("Finishes");
            more.getRow(1).createCell(1).setCellValue(50.0);
            db = ingest(workbook, "cross-region.xlsx");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT ch.basis, ch.reasons FROM cost_head_contribution ch"
                                + " JOIN region r ON r.region_id = ch.region_id"
                                + " JOIN worksheet w ON w.worksheet_id = r.worksheet_id"
                                + " WHERE w.sheet_name = 'Civil'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("basis")).isEqualTo("leaf_sum");
            assertThat(rs.getString("reasons")).contains("STRUCTURAL_CROSS_REGION");
        }
    }

    @Test
    void errorAndScratchInputs_preventStructuralQualification() throws Exception {
        Path db;
        try (XSSFWorkbook workbook = civilAmountHeader()) {
            Sheet sheet = workbook.getSheetAt(0);
            sheet.createRow(1).createCell(0).setCellValue("Foundation");
            sheet.getRow(1).createCell(1).setCellValue(100.0);
            sheet.createRow(2).createCell(0).setCellValue("Broken");
            sheet.getRow(2).createCell(1).setCellFormula("1/0");
            org.apache.poi.ss.usermodel.Row disabled = sheet.createRow(3);
            disabled.createCell(0).setCellValue("Dropped line");
            disabled.createCell(1).setCellFormula("12117678.83*0");
            sheet.createRow(4).createCell(0).setCellValue("All works");
            sheet.getRow(4).createCell(1).setCellFormula("SUM(B2:B3)");
            db = ingest(workbook, "error-scratch.xlsx");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT ch.basis, ch.reasons FROM cost_head_contribution ch")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("basis")).isEqualTo("leaf_sum");
            assertThat(rs.getString("reasons")).contains("LEAF_SUM_FALLBACK");
        }
    }

    @Test
    void numberFormatPrecision_allowsDisplayRoundingWithoutGlobalTolerance() throws Exception {
        Path db;
        try (XSSFWorkbook workbook = civilAmountHeader()) {
            Sheet sheet = workbook.getSheetAt(0);
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
            sheet.createRow(1).createCell(0).setCellValue("Foundation");
            sheet.getRow(1).createCell(1).setCellValue(10.444);
            sheet.getRow(1).getCell(1).setCellStyle(style);
            sheet.createRow(2).createCell(0).setCellValue("Finishes");
            sheet.getRow(2).createCell(1).setCellValue(20.444);
            sheet.getRow(2).getCell(1).setCellStyle(style);
            sheet.createRow(3).createCell(0).setCellValue("All works");
            sheet.getRow(3).createCell(1).setCellFormula("SUM(B2:B3)");
            sheet.getRow(3).getCell(1).setCellStyle(style);
            db = ingest(workbook, "precision.xlsx");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT basis, source_amount FROM cost_head_contribution")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("basis")).isEqualTo("structural_total");
            assertThat(rs.getDouble("source_amount")).isEqualTo(30.888);
        }
    }

    @Test
    void leafOnlyTable_isReviewCandidateNeverAutoTrusted() throws Exception {
        Path db;
        try (XSSFWorkbook workbook = civilAmountHeader()) {
            Sheet sheet = workbook.getSheetAt(0);
            sheet.createRow(1).createCell(0).setCellValue("Foundation");
            sheet.getRow(1).createCell(1).setCellValue(100.0);
            sheet.createRow(2).createCell(0).setCellValue("Finishes");
            sheet.getRow(2).createCell(1).setCellValue(50.0);
            db = ingest(workbook, "leaf-only.xlsx");
        }
        assertLeafSumReview(db, 150.0, "LEAF_SUM_FALLBACK");
    }

    @Test
    void labelledAnchorThatPassesStructuralChecks_recordsAgreementOnce() throws Exception {
        Path db;
        try (XSSFWorkbook workbook = civilAmountHeader()) {
            Sheet sheet = workbook.getSheetAt(0);
            sheet.createRow(1).createCell(0).setCellValue("Foundation");
            sheet.getRow(1).createCell(1).setCellValue(100.0);
            sheet.createRow(2).createCell(0).setCellValue("Finishes");
            sheet.getRow(2).createCell(1).setCellValue(50.0);
            sheet.createRow(3).createCell(0).setCellValue("Total");
            sheet.getRow(3).createCell(1).setCellFormula("SUM(B2:B3)");
            db = ingest(workbook, "agree.xlsx");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT basis, reasons FROM cost_head_contribution")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("basis")).isEqualTo("explicit_total_anchor");
                assertThat(rs.getString("reasons")).contains("STRUCTURAL_AGREEMENT");
                assertThat(rs.next()).isFalse();
            }
            try (ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM cost_head_candidate")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void labelledAnchorDisagreeingWithLeafSum_retainsSeparateReviewCandidates() throws Exception {
        Path db;
        try (XSSFWorkbook workbook = civilAmountHeader()) {
            Sheet sheet = workbook.getSheetAt(0);
            sheet.createRow(1).createCell(0).setCellValue("Foundation");
            sheet.getRow(1).createCell(1).setCellValue(100.0);
            sheet.createRow(2).createCell(0).setCellValue("Finishes");
            sheet.getRow(2).createCell(1).setCellValue(50.0);
            sheet.createRow(3).createCell(0).setCellValue("Roof");
            sheet.getRow(3).createCell(1).setCellValue(10.0);
            sheet.createRow(4).createCell(0).setCellValue("Total");
            sheet.getRow(4).createCell(1).setCellFormula("SUM(B2:B3)");
            db = ingest(workbook, "disagree.xlsx");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT basis, source_amount FROM cost_head_contribution ORDER BY basis")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("basis")).isEqualTo("explicit_total_anchor");
                assertThat(rs.getDouble("source_amount")).isEqualTo(150.0);
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("basis")).isEqualTo("leaf_sum");
                assertThat(rs.getDouble("source_amount")).isEqualTo(160.0);
                assertThat(rs.next()).isFalse();
            }
            try (ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM cost_head_candidate")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM review_queue WHERE category = 'cost_head_candidate'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT automatic_trust_eligible FROM cost_head_candidate")) {
                while (rs.next()) {
                    assertThat(rs.getInt(1)).isEqualTo(0);
                }
            }
        }
    }

    @Test
    void partitionedPeriods_keepScalarAmountAndExcludePeriodCells() throws Exception {
        Path db;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Capex");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Civil works");
            header.createCell(1).setCellValue("Amount (Rs.)");
            header.createCell(2).setCellValue("Year 1");
            header.createCell(3).setCellValue("Year 2");
            sheet.createRow(1).createCell(0).setCellValue("Foundation");
            sheet.getRow(1).createCell(1).setCellValue(100.0);
            sheet.getRow(1).createCell(2).setCellValue(60.0);
            sheet.getRow(1).createCell(3).setCellValue(40.0);
            sheet.createRow(2).createCell(0).setCellValue("Finishes");
            sheet.getRow(2).createCell(1).setCellValue(50.0);
            sheet.getRow(2).createCell(2).setCellValue(30.0);
            sheet.getRow(2).createCell(3).setCellValue(20.0);
            sheet.createRow(3).createCell(0).setCellValue("All works");
            sheet.getRow(3).createCell(1).setCellFormula("SUM(B2:B3)");
            db = ingest(workbook, "partitioned.xlsx");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT basis, source_amount, reasons FROM cost_head_contribution")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("basis")).isEqualTo("structural_total");
                assertThat(rs.getDouble("source_amount")).isEqualTo(150.0);
                assertThat(rs.getString("reasons")).contains("PERIOD_PARTITION");
                assertThat(rs.next()).isFalse();
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT cc.participation, cc.reason FROM cost_head_contribution_cell cc"
                            + " JOIN cell c ON c.cell_id = cc.cell_id WHERE c.coord = 'C2'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("participation")).isEqualTo("excluded");
                assertThat(rs.getString("reason")).isEqualTo("PERIOD");
            }
        }
    }

    @Test
    void nonAdditivePeriods_areNotScalarSummed() throws Exception {
        Path db;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Capex");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Civil works");
            header.createCell(1).setCellValue("Year 1");
            header.createCell(2).setCellValue("Year 2");
            sheet.createRow(1).createCell(0).setCellValue("Foundation");
            sheet.getRow(1).createCell(1).setCellValue(100.0);
            sheet.getRow(1).createCell(2).setCellValue(50.0);
            db = ingest(workbook, "non-additive.xlsx");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT source_amount, basis, reasons FROM cost_head_contribution"
                            + " ORDER BY source_amount")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("basis")).isEqualTo("leaf_sum");
                assertThat(rs.getDouble("source_amount")).isEqualTo(50.0);
                assertThat(rs.getString("reasons")).contains("PERIODIZED");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getDouble("source_amount")).isEqualTo(100.0);
                assertThat(rs.next()).isFalse();
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT source_amount FROM cost_head_contribution WHERE source_amount = 150")) {
                assertThat(rs.next()).isFalse();
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT automatic_trust_eligible FROM cost_head_candidate")) {
                while (rs.next()) {
                    assertThat(rs.getInt(1)).isEqualTo(0);
                }
            }
        }
    }

    private XSSFWorkbook unlabeledFormula(String formula) {
        XSSFWorkbook workbook = civilAmountHeader();
        Sheet sheet = workbook.getSheetAt(0);
        sheet.createRow(1).createCell(0).setCellValue("Foundation");
        sheet.getRow(1).createCell(1).setCellValue(100.0);
        sheet.createRow(2).createCell(0).setCellValue("Finishes");
        sheet.getRow(2).createCell(1).setCellValue(50.0);
        sheet.createRow(3).createCell(0).setCellValue("All works");
        sheet.getRow(3).createCell(1).setCellFormula(formula);
        return workbook;
    }

    private XSSFWorkbook civilAmountHeader() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Capex");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Civil works");
        header.createCell(1).setCellValue("Amount (Rs.)");
        return workbook;
    }

    private void assertLeafSumReview(Path db, double amount, String reason) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT ch.basis, ch.source_amount, cand.automatic_trust_eligible, ch.reasons"
                            + " FROM cost_head_contribution ch"
                            + " JOIN cost_head_candidate cand ON cand.cost_head_candidate_id"
                            + " = ch.cost_head_candidate_id")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("basis")).isEqualTo("leaf_sum");
                assertThat(rs.getDouble("source_amount")).isEqualTo(amount);
                assertThat(rs.getInt("automatic_trust_eligible")).isEqualTo(0);
                assertThat(rs.getString("reasons")).contains("LEAF_SUM_FALLBACK");
                assertThat(rs.getString("reasons")).contains(reason);
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM review_queue WHERE category = 'cost_head_candidate'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isGreaterThan(0);
            }
        }
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
}
