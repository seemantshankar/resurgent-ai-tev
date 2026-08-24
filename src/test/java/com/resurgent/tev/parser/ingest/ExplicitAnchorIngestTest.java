package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.config.ParserConfig;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ingest-seam tests for explicit-total-anchor candidates.
 */
class ExplicitAnchorIngestTest {

    @TempDir
    Path tempDir;

    @Test
    void formulaTotalWithRecognizedLabel_isExplicitAnchorWithLeafCoverage() throws Exception {
        Path db = ingest(civilTotalWorkbook(true), "anchor.xlsx");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT basis, source_amount, source_unit, source_currency, region_id,"
                            + " anchor_cell_id, confidence, reasons FROM cost_head_contribution")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("basis")).isEqualTo("explicit_total_anchor");
                assertThat(rs.getDouble("source_amount")).isEqualTo(150.0);
                assertThat(rs.getLong("region_id")).isPositive();
                assertThat(rs.getLong("anchor_cell_id")).isPositive();
                assertThat(rs.getDouble("confidence")).isGreaterThan(0);
                assertThat(rs.getString("reasons")).contains("EXPLICIT_TOTAL_ANCHOR");
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
                assertThat(included).contains("B2", "B3");
                assertThat(excluded).contains("B4");
            }
        }
    }

    @Test
    void labelledLiteralWithoutFormula_isReviewCandidateNotExplicitAnchor() throws Exception {
        Path db = ingest(civilTotalWorkbook(false), "literal.xlsx");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT basis, source_amount FROM cost_head_contribution")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("basis")).isEqualTo("leaf_sum");
                assertThat(rs.getDouble("source_amount")).isEqualTo(150.0);
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM review_queue WHERE category = 'cost_head_candidate'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isGreaterThan(0);
            }
        }
    }

    @Test
    void subtotalFormula_isExcludedFromEligibleLeaves() throws Exception {
        Path db;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Capex");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Civil works");
            header.createCell(1).setCellValue("Amount");
            sheet.createRow(1).createCell(0).setCellValue("Foundation");
            sheet.getRow(1).createCell(1).setCellValue(100.0);
            sheet.createRow(2).createCell(0).setCellValue("Subtotal");
            sheet.getRow(2).createCell(1).setCellFormula("B2");
            sheet.createRow(3).createCell(0).setCellValue("Finishes");
            sheet.getRow(3).createCell(1).setCellValue(50.0);
            sheet.createRow(4).createCell(0).setCellValue("Total");
            sheet.getRow(4).createCell(1).setCellFormula("SUM(B2:B4)");
            db = ingest(workbook, "subtotal.xlsx");
        }

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT c.coord, cc.participation, cc.reason FROM cost_head_contribution_cell cc"
                                + " JOIN cell c ON c.cell_id = cc.cell_id WHERE c.coord IN ('B2','B3','B4')")) {
            boolean sawSubtotalExcluded = false;
            boolean sawFoundationIncluded = false;
            while (rs.next()) {
                if ("B3".equals(rs.getString("coord"))) {
                    assertThat(rs.getString("participation")).isEqualTo("excluded");
                    assertThat(rs.getString("reason")).isEqualTo("SUBTOTAL");
                    sawSubtotalExcluded = true;
                }
                if ("B2".equals(rs.getString("coord")) && "included".equals(rs.getString("participation"))) {
                    sawFoundationIncluded = true;
                }
            }
            assertThat(sawSubtotalExcluded).isTrue();
            assertThat(sawFoundationIncluded).isTrue();
        }
    }

    @Test
    void mergedParticipant_isExcluded() throws Exception {
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
            sheet.addMergedRegion(new CellRangeAddress(1, 2, 1, 1));
            sheet.createRow(3).createCell(0).setCellValue("Total");
            sheet.getRow(3).createCell(1).setCellFormula("SUM(B2:B3)");
            db = ingest(workbook, "merged.xlsx");
        }

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT cc.participation, cc.reason FROM cost_head_contribution_cell cc"
                                + " JOIN cell c ON c.cell_id = cc.cell_id WHERE c.coord = 'B3'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("participation")).isEqualTo("excluded");
            assertThat(rs.getString("reason")).isEqualTo("MERGED_PARTICIPANT");
        }
    }

    @Test
    void errorAndScratchCells_areExcluded() throws Exception {
        Path db;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Capex");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Civil works");
            header.createCell(1).setCellValue("Amount");
            sheet.createRow(1).createCell(0).setCellValue("Foundation");
            sheet.getRow(1).createCell(1).setCellValue(100.0);
            sheet.createRow(2).createCell(0).setCellValue("Broken");
            Cell error = sheet.getRow(2).createCell(1);
            error.setCellFormula("1/0");
            Row disabled = sheet.createRow(3);
            disabled.createCell(0).setCellValue("Dropped line");
            disabled.createCell(1).setCellFormula("12117678.83*0");
            sheet.createRow(4).createCell(0).setCellValue("Total");
            sheet.getRow(4).createCell(1).setCellFormula("SUM(B2:B3)");
            db = ingest(workbook, "error-scratch.xlsx");
        }

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT c.coord, cc.reason FROM cost_head_contribution_cell cc"
                                + " JOIN cell c ON c.cell_id = cc.cell_id"
                                + " WHERE cc.participation = 'excluded' AND c.coord IN ('B3','B4')")) {
            List<String> reasons = new ArrayList<>();
            while (rs.next()) {
                reasons.add(rs.getString("coord") + ":" + rs.getString("reason"));
            }
            assertThat(reasons).anyMatch(value -> value.startsWith("B3:") && value.contains("ERROR"));
            assertThat(reasons).anyMatch(value -> value.startsWith("B4:") && value.contains("SCRATCH"));
        }
    }

    @Test
    void twoRegionsSameCostHead_shareOneCandidateWithoutOwnershipField() throws Exception {
        Path db;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet civil = workbook.createSheet("Civil");
            civil.createRow(0).createCell(0).setCellValue("Civil works");
            civil.getRow(0).createCell(1).setCellValue("Amount");
            civil.createRow(1).createCell(0).setCellValue("Foundation");
            civil.getRow(1).createCell(1).setCellValue(100.0);
            civil.createRow(2).createCell(0).setCellValue("Total");
            civil.getRow(2).createCell(1).setCellFormula("B2");

            Sheet more = workbook.createSheet("MoreCivil");
            more.createRow(0).createCell(0).setCellValue("Civil works");
            more.getRow(0).createCell(1).setCellValue("Amount");
            more.createRow(1).createCell(0).setCellValue("Finishes");
            more.getRow(1).createCell(1).setCellValue(50.0);
            more.createRow(2).createCell(0).setCellValue("Total");
            more.getRow(2).createCell(1).setCellFormula("B2");
            db = ingest(workbook, "two-regions.xlsx");
        }

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM cost_head_candidate")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            try (ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM cost_head_contribution")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "PRAGMA table_info(cost_head)")) {
                List<String> columns = new ArrayList<>();
                while (rs.next()) {
                    columns.add(rs.getString("name"));
                }
                assertThat(columns).doesNotContain("primary_region_id", "owning_region_id");
            }
        }
    }

    @Test
    void fingerprint_isStableAcrossConfigOnlyChangesAndShiftsWhenAmountChanges() throws Exception {
        XSSFWorkbook first = civilTotalWorkbook(true);
        Path db = tempDir.resolve("fp.db");
        Path xlsx = writeWorkbook(first, "fp.xlsx");
        new IngestService().ingest(xlsx, 1L, db);
        String fingerprint = fingerprint(db);

        ParserConfig reparse = new ParserConfig(
                100L * 1024 * 1024, 200, 1_000_000, 16_384, 5_000_000L, 100,
                false, true, true, 4, 4);
        new IngestService().ingest(xlsx, 1L, db, reparse);
        assertThat(fingerprint(db, true)).isEqualTo(fingerprint);

        XSSFWorkbook changed = civilTotalWorkbook(true);
        changed.getSheetAt(0).getRow(1).getCell(1).setCellValue(120.0);
        changed.getSheetAt(0).getRow(3).getCell(1).setCellFormula("SUM(B2:B3)");
        Path changedFile = writeWorkbook(changed, "fp-changed.xlsx");
        Path db2 = tempDir.resolve("fp2.db");
        new IngestService().ingest(changedFile, 1L, db2);
        assertThat(fingerprint(db2)).isNotEqualTo(fingerprint);
    }

    @Test
    void explicitAnchorAmount_equalsSumOfIncludedSourceCells() throws Exception {
        Path db = ingest(civilTotalWorkbook(true), "provenance.xlsx");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            double persisted;
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT source_amount FROM cost_head_contribution")) {
                assertThat(rs.next()).isTrue();
                persisted = rs.getDouble(1);
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT SUM(c.numeric_value) FROM cost_head_contribution_cell cc"
                            + " JOIN cell c ON c.cell_id = cc.cell_id"
                            + " WHERE cc.participation = 'included'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getDouble(1)).isEqualTo(persisted);
                assertThat(persisted).isEqualTo(150.0);
            }
        }
    }

    @Test
    void periodColumnNumeric_isExcludedWithPeriodReason() throws Exception {
        Path db;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Capex");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Civil works");
            header.createCell(1).setCellValue("Amount");
            header.createCell(2).setCellValue("Year 1");
            sheet.createRow(1).createCell(0).setCellValue("Foundation");
            sheet.getRow(1).createCell(1).setCellValue(100.0);
            sheet.getRow(1).createCell(2).setCellValue(10.0);
            sheet.createRow(2).createCell(0).setCellValue("Finishes");
            sheet.getRow(2).createCell(1).setCellValue(50.0);
            sheet.getRow(2).createCell(2).setCellValue(20.0);
            sheet.createRow(3).createCell(0).setCellValue("Total");
            sheet.getRow(3).createCell(1).setCellFormula("SUM(B2:B3)");
            db = ingest(workbook, "period.xlsx");
        }

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT cc.participation, cc.reason FROM cost_head_contribution_cell cc"
                                + " JOIN cell c ON c.cell_id = cc.cell_id WHERE c.coord = 'C2'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("participation")).isEqualTo("excluded");
            assertThat(rs.getString("reason")).isEqualTo("PERIOD");
        }
    }

    @Test
    void mixedLakhAndCroreRows_excludesConflictingUnitLeaves() throws Exception {
        Path db;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Capex");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Civil works");
            header.createCell(1).setCellValue("Amount");
            sheet.createRow(1).createCell(0).setCellValue("Foundation (Rs. Lakh)");
            sheet.getRow(1).createCell(1).setCellValue(10.0);
            sheet.createRow(2).createCell(0).setCellValue("Plant (Rs. Crore)");
            sheet.getRow(2).createCell(1).setCellValue(2.0);
            sheet.createRow(3).createCell(0).setCellValue("Total");
            sheet.getRow(3).createCell(1).setCellFormula("SUM(B2:B3)");
            db = ingest(workbook, "mixed-unit.xlsx");
        }

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT c.coord, cc.participation, cc.reason FROM cost_head_contribution_cell cc"
                                + " JOIN cell c ON c.cell_id = cc.cell_id WHERE c.coord IN ('B2','B3')")) {
            List<String> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(rs.getString("coord") + ":" + rs.getString("participation")
                        + ":" + rs.getString("reason"));
            }
            assertThat(rows).anyMatch(value -> value.contains("UNIT"));
            assertThat(rows.stream().allMatch(value -> value.contains(":included:"))).isFalse();
        }
    }

    private XSSFWorkbook civilTotalWorkbook(boolean formulaTotal) {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Capex");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Civil works");
        header.createCell(1).setCellValue("Amount");
        sheet.createRow(1).createCell(0).setCellValue("Foundation");
        sheet.getRow(1).createCell(1).setCellValue(100.0);
        sheet.createRow(2).createCell(0).setCellValue("Finishes");
        sheet.getRow(2).createCell(1).setCellValue(50.0);
        sheet.createRow(3).createCell(0).setCellValue("Total");
        if (formulaTotal) {
            sheet.getRow(3).createCell(1).setCellFormula("SUM(B2:B3)");
        } else {
            sheet.getRow(3).createCell(1).setCellValue(150.0);
        }
        return workbook;
    }

    private Path ingest(XSSFWorkbook workbook, String name) throws Exception {
        Path db = tempDir.resolve(name.replace(".xlsx", ".db"));
        new IngestService().ingest(writeWorkbook(workbook, name), 1L, db);
        workbook.close();
        return db;
    }

    private Path writeWorkbook(XSSFWorkbook workbook, String name) throws Exception {
        workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
        Path file = tempDir.resolve(name);
        try (FileOutputStream out = new FileOutputStream(file.toFile())) {
            workbook.write(out);
        }
        return file;
    }

    private static String fingerprint(Path db) throws Exception {
        return fingerprint(db, false);
    }

    private static String fingerprint(Path db, boolean latest) throws Exception {
        String sql = "SELECT candidate_fingerprint FROM cost_head_candidate"
                + (latest ? " ORDER BY cost_head_candidate_id DESC LIMIT 1" : "");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }
}
