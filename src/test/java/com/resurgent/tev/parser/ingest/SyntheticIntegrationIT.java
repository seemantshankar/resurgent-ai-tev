package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.Jsonb;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyntheticIntegrationIT {

    @TempDir
    Path tempDir;

    private long scalarLong(Connection c, String sql) throws Exception {
        try (ResultSet rs = c.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void errorBarrierStopsCascade() throws Exception {
        Path workbookPath = tempDir.resolve("barrier.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("BarrierTest");
            Row r0 = sheet.createRow(0);
            Cell cA1 = r0.createCell(0);
            cA1.setCellFormula("1/0");

            Row r1 = sheet.createRow(1);
            Cell cA2 = r1.createCell(0);
            cA2.setCellFormula("IFERROR(A1,999)");

            Row r2 = sheet.createRow(2);
            Cell cA3 = r2.createCell(0);
            cA3.setCellFormula("A2+10");

            try (FileOutputStream out = new FileOutputStream(workbookPath.toFile())) {
                wb.write(out);
            }
        }

        Path dbPath = tempDir.resolve("barrier.db");
        IngestSummary summary = new IngestService().ingest(workbookPath, 1L, dbPath);
        assertThat(summary.status()).isIn("success", "partial");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            // A1 is an error root
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT is_error, error_type FROM cell WHERE coord = 'A1'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt("is_error")).isEqualTo(1);
                    assertThat(rs.getString("error_type")).isEqualTo("#DIV/0!");
                }
            }

            // A2 is behind the IFERROR barrier: not an error, not an error descendant
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT is_error, error_descendant FROM cell WHERE coord = 'A2'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt("is_error")).isEqualTo(0);
                    assertThat(rs.getInt("error_descendant")).isEqualTo(0);
                }
            }

            // A3 is downstream of barrier A2: not an error, not an error descendant
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT is_error, error_descendant FROM cell WHERE coord = 'A3'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt("is_error")).isEqualTo(0);
                    assertThat(rs.getInt("error_descendant")).isEqualTo(0);
                }
            }
        }
    }

    @Test
    void barrierNameEmbeddedInASheetNameDoesNotStopTheCascade() throws Exception {
        Path workbookPath = tempDir.resolve("nearbarrier.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet lookup = wb.createSheet("Discount");
            lookup.createRow(0).createCell(0).setCellValue(5.0);

            Sheet sheet = wb.createSheet("Cascade");
            sheet.createRow(0).createCell(0).setCellFormula("1/0");
            // Contains "COUNT" inside "Discount", but calls no barrier function.
            sheet.createRow(1).createCell(0).setCellFormula("A1+Discount!A1");

            try (FileOutputStream out = new FileOutputStream(workbookPath.toFile())) {
                wb.write(out);
            }
        }

        Path dbPath = tempDir.resolve("nearbarrier.db");
        new IngestService().ingest(workbookPath, 1L, dbPath);

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT cell.error_descendant FROM cell"
                            + " JOIN worksheet ON cell.worksheet_id = worksheet.worksheet_id"
                            + " WHERE worksheet.sheet_name = 'Cascade' AND cell.coord = 'A2'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt("error_descendant"))
                            .as("A2 is downstream of the #DIV/0! root and behind no barrier")
                            .isEqualTo(1);
                }
            }
        }
    }

    @Test
    void wholeColumnRefClampsToBbox() throws Exception {
        Path workbookPath = tempDir.resolve("wholecol.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("WholeColTest");
            for (int r = 0; r < 10; r++) {
                Row row = sheet.createRow(r);
                Cell c = row.createCell(0);
                c.setCellValue(5.0);
            }
            Row r0 = sheet.getRow(0);
            Cell cB1 = r0.createCell(1);
            cB1.setCellFormula("SUM(A:A)");

            try (FileOutputStream out = new FileOutputStream(workbookPath.toFile())) {
                wb.write(out);
            }
        }

        Path dbPath = tempDir.resolve("wholecol.db");
        IngestSummary summary = new IngestService().ingest(workbookPath, 1L, dbPath);
        assertThat(summary.status()).isIn("success", "partial");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT is_whole_column, target_range FROM cell_reference"
                            + " JOIN cell ON cell_reference.from_cell_id = cell.cell_id"
                            + " WHERE cell.coord = 'B1'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt("is_whole_column")).isEqualTo(1);
                }
            }
        }
    }

    @Test
    void circularReferenceDetectionAndSeverity() throws Exception {
        Path workbookPath = tempDir.resolve("circular.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("CycleTest");
            Row r0 = sheet.createRow(0);
            Cell cA1 = r0.createCell(0);
            cA1.setCellFormula("B1");
            Cell cB1 = r0.createCell(1);
            cB1.setCellFormula("A1");

            try (FileOutputStream out = new FileOutputStream(workbookPath.toFile())) {
                wb.write(out);
            }
        }

        Path dbPath = tempDir.resolve("circular.db");
        IngestSummary summary = new IngestService().ingest(workbookPath, 1L, dbPath);

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            assertThat(scalarLong(c, "SELECT calc_is_circular FROM workbook")).isEqualTo(1);
            assertThat(scalarLong(c, "SELECT calc_circular_group_count FROM workbook")).isEqualTo(1);
            assertThat(scalarLong(c, "SELECT COUNT(*) FROM cell WHERE is_circular = 1")).isEqualTo(2);
            assertThat(scalarLong(c, "SELECT COUNT(*) FROM review_queue WHERE category = 'circular_reference'")).isGreaterThan(0);
        }
    }

    @Test
    void uncachedFormulaMarkedMissing() throws Exception {
        Path workbookPath = tempDir.resolve("uncached.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("UncachedTest");
            Row r0 = sheet.createRow(0);
            Cell cA1 = r0.createCell(0);
            cA1.setCellValue(10.0);

            Row r1 = sheet.createRow(1);
            Cell cA2 = r1.createCell(0);
            cA2.setCellFormula("A1*2");
            // Deliberately DO NOT set cached formula result or evaluate

            try (FileOutputStream out = new FileOutputStream(workbookPath.toFile())) {
                wb.write(out);
            }
        }

        Path dbPath = tempDir.resolve("uncached.db");
        IngestSummary summary = new IngestService().ingest(workbookPath, 1L, dbPath);

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT cache_state, numeric_value FROM cell WHERE coord = 'A2'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("cache_state")).isEqualTo("missing");
                    assertThat(rs.getObject("numeric_value")).isNull();
                }
            }
        }
    }

    @Test
    void referenceSalvageFallbackOnParseError() {
        FormulaTokenizerResult res = FormulaTokenizer.tokenize("SUM(A1:A5", 2, 1, Map.of());
        assertThat(res.formulaState()).isEqualTo("parse_error");
        assertThat(res.tokens()).hasSize(1);
        assertThat(res.tokens().get(0).targetRange()).isEqualTo("A1:A5");
    }

    @Test
    void csvAdapterIntegration() throws Exception {
        Path csvPath = tempDir.resolve("sample.csv");
        Files.writeString(csvPath, "HeaderA,HeaderB\n10,20\n30,40\n", StandardCharsets.UTF_8);

        Path dbPath = tempDir.resolve("csv_test.db");
        IngestSummary summary = new IngestService().ingest(csvPath, 1L, dbPath);

        assertThat(summary.status()).isEqualTo("success");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            assertThat(scalarLong(c, "SELECT COUNT(*) FROM worksheet")).isEqualTo(1);
            assertThat(scalarLong(c, "SELECT COUNT(*) FROM cell")).isEqualTo(6);

            try (PreparedStatement ps = c.prepareStatement("SELECT raw_metadata FROM source_file")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    Map<String, Object> meta = Jsonb.fromJson(rs.getString(1), Map.class);
                    assertThat(meta).containsEntry("encoding", "UTF-8");
                    assertThat(meta).containsEntry("delimiter", ",");
                }
            }
        }
    }
}
