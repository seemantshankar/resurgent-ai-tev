package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.resurgent.tev.parser.db.Jsonb;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ingest-seam tests for content- and dependency-weighted worksheet roles.
 */
class WorksheetRoleIngestTest {

    @TempDir
    Path tempDir;

    @Test
    void civilWorksAmountTable_isPrimaryWithConfidenceAndStructuredReasons() throws Exception {
        Path db = ingest(civilCapexWorkbook(), "civil-primary.xlsx");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT sheet_name, role, role_conf, role_reasons FROM worksheet")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("sheet_name")).isEqualTo("Capex");
                assertThat(rs.getString("role")).isEqualTo("primary");
                assertThat(rs.getDouble("role_conf")).isGreaterThan(0);
                String reasons = rs.getString("role_reasons");
                assertThat(reasons).isNotBlank();
                assertThat(reasons).contains("COST_HEAD_CONTRIBUTION");
                assertThat(reasons).doesNotContain("Civil");
                assertThat(reasons).doesNotContain("150");
            }
            try (ResultSet rs = c.createStatement().executeQuery("SELECT metrics FROM parse_run")) {
                assertThat(rs.next()).isTrue();
                Map<String, Object> metrics = Jsonb.fromJson(rs.getString(1), new TypeReference<>() {});
                assertThat(metrics).containsKey("worksheets");
                List<Map<String, Object>> worksheets = (List<Map<String, Object>>) metrics.get("worksheets");
                assertThat(worksheets).hasSize(1);
                assertThat(worksheets.get(0).get("sheetName")).isEqualTo("Capex");
                assertThat(worksheets.get(0).get("role")).isEqualTo("primary");
                assertThat(worksheets.get(0).get("roleConf")).isNotNull();
                assertThat(worksheets.get(0).get("reasons").toString()).contains("COST_HEAD_CONTRIBUTION");
            }
        }
    }

    @Test
    void transitiveFormulaFeeders_areSupport() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet capex = workbook.createSheet("Capex");
        Row capexHeader = capex.createRow(0);
        capexHeader.createCell(0).setCellValue("Civil works");
        capexHeader.createCell(1).setCellValue("Amount");
        capex.createRow(1).createCell(0).setCellValue("Foundation");
        capex.getRow(1).createCell(1).setCellValue(100.0);
        capex.createRow(2).createCell(0).setCellValue("From drivers");
        capex.getRow(2).createCell(1).setCellFormula("Drivers!B1");
        capex.createRow(3).createCell(0).setCellValue("Total");
        capex.getRow(3).createCell(1).setCellFormula("SUM(B2:B3)");

        Sheet drivers = workbook.createSheet("Drivers");
        drivers.createRow(0).createCell(0).setCellValue("Driver");
        drivers.getRow(0).createCell(1).setCellFormula("Assumptions!B1");

        Sheet assumptions = workbook.createSheet("Assumptions");
        assumptions.createRow(0).createCell(0).setCellValue("Rate");
        assumptions.getRow(0).createCell(1).setCellValue(50.0);

        Path db = ingest(workbook, "transitive-support.xlsx");
        Map<String, String> roles = roles(db);
        assertThat(roles.get("Capex")).isEqualTo("primary");
        assertThat(roles.get("Drivers")).isEqualTo("support");
        assertThat(roles.get("Assumptions")).isEqualTo("support");
        assertThat(reasons(db, "Drivers")).contains("DEPENDENCY_INTO_PRIMARY");
        assertThat(reasons(db, "Assumptions")).contains("DEPENDENCY_INTO_PRIMARY");
    }

    @Test
    void unlabeledFormulaIsland_withNoFeedIntoPrimary_isScratch() throws Exception {
        XSSFWorkbook workbook = civilCapexWorkbook();
        Sheet scratch = workbook.createSheet("Scratchpad");
        scratch.createRow(0).createCell(0).setCellFormula("1+1");
        scratch.createRow(1).createCell(0).setCellFormula("2+2");
        scratch.createRow(2).createCell(0).setCellFormula("3+3");

        Path db = ingest(workbook, "scratch.xlsx");
        Map<String, String> roles = roles(db);
        assertThat(roles.get("Capex")).isEqualTo("primary");
        assertThat(roles.get("Scratchpad")).isEqualTo("scratch");
    }

    @Test
    void scratchSheetThatFeedsPrimary_isSupport() throws Exception {
        XSSFWorkbook workbook = civilCapexWorkbook();
        Sheet capex = workbook.getSheet("Capex");
        capex.getRow(1).getCell(1).setCellFormula("Scratchpad!A1");
        Sheet scratch = workbook.createSheet("Scratchpad");
        scratch.createRow(0).createCell(0).setCellFormula("1+1");
        scratch.createRow(1).createCell(0).setCellFormula("2+2");

        Path db = ingest(workbook, "scratch-feeds.xlsx");
        Map<String, String> roles = roles(db);
        assertThat(roles.get("Capex")).isEqualTo("primary");
        assertThat(roles.get("Scratchpad")).isEqualTo("support");
    }

    @Test
    void largeStatementWithTwoScratchIslands_staysPrimary() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet pnl = workbook.createSheet("P  L ");
        pnl.createRow(0).createCell(0).setCellValue("Particulars");
        pnl.getRow(0).createCell(1).setCellValue("Amount");
        String[] lines = {
                "Revenue", "Other income", "Expense", "COGS", "Gross profit", "Overheads", "PBT", "Tax", "PAT"};
        double[] values = {100, 10, 30, 40, 70, 20, 50, 10, 40};
        for (int i = 0; i < lines.length; i++) {
            pnl.createRow(i + 1).createCell(0).setCellValue(lines[i]);
            pnl.getRow(i + 1).createCell(1).setCellValue(values[i]);
        }
        pnl.createRow(20).createCell(25).setCellFormula("1+1");
        pnl.createRow(21).createCell(25).setCellFormula("2+2");

        Path db = ingest(workbook, "statement-primary.xlsx");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT role FROM worksheet WHERE sheet_name = 'P  L '")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("primary");
        }
    }

    @Test
    void statementPlusScratchIsland_isUnknownConflict() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet mixed = workbook.createSheet("Mixed");
        mixed.createRow(0).createCell(0).setCellValue("Revenue");
        mixed.getRow(0).createCell(1).setCellValue(10.0);
        mixed.createRow(1).createCell(0).setCellValue("Expense");
        mixed.getRow(1).createCell(1).setCellValue(4.0);
        mixed.createRow(20).createCell(25).setCellFormula("1+1");
        mixed.createRow(21).createCell(25).setCellFormula("2+2");
        mixed.createRow(22).createCell(25).setCellFormula("3+3");
        mixed.createRow(23).createCell(25).setCellFormula("4+4");

        Path db = ingest(workbook, "unknown-conflict.xlsx");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT role, role_reasons FROM worksheet WHERE sheet_name = 'Mixed'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("role")).isEqualTo("unknown");
            assertThat(rs.getString("role_reasons")).contains("ROLE_CONFLICT");
        }
    }

    @Test
    void hiddenSheetFeedingPrimary_isSupport() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet capex = workbook.createSheet("Capex");
        Row capexHeader = capex.createRow(0);
        capexHeader.createCell(0).setCellValue("Civil works");
        capexHeader.createCell(1).setCellValue("Amount");
        capex.createRow(1).createCell(0).setCellValue("Foundation");
        capex.getRow(1).createCell(1).setCellValue(100.0);
        capex.createRow(2).createCell(0).setCellValue("Pages feed");
        capex.getRow(2).createCell(1).setCellFormula("Pages!B1");
        capex.createRow(3).createCell(0).setCellValue("Total");
        capex.getRow(3).createCell(1).setCellFormula("SUM(B2:B3)");

        Sheet pages = workbook.createSheet("Pages");
        pages.createRow(0).createCell(0).setCellValue("Hidden driver");
        pages.getRow(0).createCell(1).setCellValue(50.0);
        workbook.setSheetHidden(1, true);

        Path db = ingest(workbook, "hidden-support.xlsx");
        Map<String, String> roles = roles(db);
        assertThat(roles.get("Capex")).isEqualTo("primary");
        assertThat(roles.get("Pages")).isEqualTo("support");
        assertThat(reasons(db, "Pages")).contains("DEPENDENCY_INTO_PRIMARY");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT sheet_state FROM worksheet WHERE sheet_name = 'Pages'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("hidden");
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT source_amount FROM cost_head_contribution")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getDouble(1)).isEqualTo(150.0);
            }
        }
    }

    @Test
    void unknownSheetWithCivilTable_stillPersistsContribution() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet mixed = workbook.createSheet("MixedCivil");
        Row header = mixed.createRow(0);
        header.createCell(0).setCellValue("Civil works");
        header.createCell(1).setCellValue("Amount");
        mixed.createRow(1).createCell(0).setCellValue("Foundation");
        mixed.getRow(1).createCell(1).setCellValue(100.0);
        mixed.createRow(2).createCell(0).setCellValue("Finishes");
        mixed.getRow(2).createCell(1).setCellValue(50.0);
        mixed.createRow(3).createCell(0).setCellValue("Total");
        mixed.getRow(3).createCell(1).setCellFormula("SUM(B2:B3)");
        mixed.createRow(20).createCell(0).setCellValue("Revenue");
        mixed.getRow(20).createCell(1).setCellValue(10.0);
        mixed.createRow(21).createCell(0).setCellValue("Expense");
        mixed.getRow(21).createCell(1).setCellValue(4.0);
        mixed.createRow(40).createCell(25).setCellFormula("1+1");
        mixed.createRow(41).createCell(25).setCellFormula("2+2");
        mixed.createRow(42).createCell(25).setCellFormula("3+3");
        mixed.createRow(43).createCell(25).setCellFormula("4+4");

        Path db = ingest(workbook, "non-interference.xlsx");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT role FROM worksheet WHERE sheet_name = 'MixedCivil'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isNotBlank();
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT basis, source_amount FROM cost_head_contribution")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("basis")).isEqualTo("explicit_total_anchor");
                assertThat(rs.getDouble("source_amount")).isEqualTo(150.0);
            }
        }
    }

    @Test
    void oneCellUnknownRegions_doNotOutvoteCostHeadContent() throws Exception {
        XSSFWorkbook workbook = civilCapexWorkbook();
        Sheet capex = workbook.getSheet("Capex");
        for (int i = 0; i < 30; i++) {
            capex.createRow(30 + i * 3).createCell(10).setCellValue("Note " + i);
        }

        Path db = ingest(workbook, "one-cell-regions.xlsx");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT role, (SELECT COUNT(*) FROM region r WHERE r.worksheet_id = worksheet.worksheet_id)"
                                + " FROM worksheet WHERE sheet_name = 'Capex'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("primary");
            assertThat(rs.getInt(2)).isGreaterThan(1);
        }
    }

    private static Map<String, String> roles(Path db) throws Exception {
        Map<String, String> roles = new LinkedHashMap<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT sheet_name, role FROM worksheet")) {
            while (rs.next()) {
                roles.put(rs.getString(1), rs.getString(2));
            }
        }
        return roles;
    }

    private static String reasons(Path db, String sheet) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT role_reasons FROM worksheet WHERE sheet_name = '" + sheet + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private XSSFWorkbook civilCapexWorkbook() {
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
        sheet.getRow(3).createCell(1).setCellFormula("SUM(B2:B3)");
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
}
