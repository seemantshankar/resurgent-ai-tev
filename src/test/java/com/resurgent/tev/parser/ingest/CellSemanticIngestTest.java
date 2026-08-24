package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Ingest-seam tests for scratch / support / orphan classification.
 */
class CellSemanticIngestTest {

    @TempDir
    Path tempDir;

    private Path writeWorkbook(XSSFWorkbook workbook, String name) throws Exception {
        Path file = tempDir.resolve(name);
        try (FileOutputStream out = new FileOutputStream(file.toFile())) {
            workbook.write(out);
        }
        workbook.close();
        return file;
    }

    private static long count(Connection c, String sql) throws Exception {
        try (ResultSet rs = c.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void unlabeledFormulaIsland_isScratchWithStableReason() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Model");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Particulars");
            header.createCell(1).setCellValue("Amount");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Civil works");
            row.createCell(1).setCellValue(100.0);
            sheet.createRow(20).createCell(10).setCellFormula("1+2");

            Path xlsx = writeWorkbook(workbook, "island.xlsx");
            Path db = tempDir.resolve("island.db");
            new IngestService().ingest(xlsx, 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                    ResultSet rs = c.createStatement().executeQuery(
                            "SELECT is_scratch, scratch_reason, is_support, is_orphan FROM cell WHERE coord = 'K21'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("is_scratch")).isEqualTo(1);
                assertThat(rs.getString("scratch_reason")).isEqualTo("UNLABELED_FORMULA_ISLAND");
                assertThat(rs.getInt("is_support")).isEqualTo(0);
                assertThat(rs.getInt("is_orphan")).isEqualTo(0);
            }
        }
    }

    @Test
    void disabledLine_isScratchEvenWhenLabeled() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Model");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Particulars");
            header.createCell(1).setCellValue("Amount");
            Row live = sheet.createRow(1);
            live.createCell(0).setCellValue("Civil works");
            live.createCell(1).setCellValue(100.0);
            Row disabled = sheet.createRow(2);
            disabled.createCell(0).setCellValue("Dropped line");
            disabled.createCell(1).setCellFormula("12117678.83*0");

            Path xlsx = writeWorkbook(workbook, "disabled.xlsx");
            Path db = tempDir.resolve("disabled.db");
            new IngestService().ingest(xlsx, 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                    ResultSet rs = c.createStatement().executeQuery(
                            "SELECT is_scratch, scratch_reason, region_id FROM cell WHERE coord = 'B3'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("is_scratch")).isEqualTo(1);
                assertThat(rs.getString("scratch_reason")).isEqualTo("DISABLED_LINE");
                long regionId = rs.getLong("region_id");
                try (ResultSet region = c.createStatement().executeQuery(
                        "SELECT region_type, semantic_region_type FROM region WHERE region_id = " + regionId)) {
                    assertThat(region.next()).isTrue();
                    assertThat(region.getString("region_type")).isNotEqualTo("scratch");
                    assertThat(region.getString("semantic_region_type")).isNotEqualTo("scratch");
                }
            }
        }
    }

    @Test
    void referencedIsolatedConstant_becomesSupportNotOrphan() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Model");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Particulars");
            header.createCell(1).setCellValue("Amount");
            sheet.createRow(1).createCell(0).setCellValue("Assumption");
            sheet.getRow(1).createCell(1).setCellFormula("Z21");
            sheet.createRow(20).createCell(25).setCellValue(42.0);

            Path xlsx = writeWorkbook(workbook, "support.xlsx");
            Path db = tempDir.resolve("support.db");
            new IngestService().ingest(xlsx, 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                    ResultSet rs = c.createStatement().executeQuery(
                            "SELECT is_support, support_reason, is_orphan, is_scratch FROM cell WHERE coord = 'Z21'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("is_support")).isEqualTo(1);
                assertThat(rs.getString("support_reason")).isEqualTo("SUPPORT_DEPENDENCY");
                assertThat(rs.getInt("is_orphan")).isEqualTo(0);
                assertThat(rs.getInt("is_scratch")).isEqualTo(0);
            }
        }
    }

    @Test
    void indirectSupportChain_promotesToFixedPoint() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Model");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Particulars");
            header.createCell(1).setCellValue("Amount");
            sheet.createRow(1).createCell(0).setCellValue("Live total");
            sheet.getRow(1).createCell(1).setCellFormula("Y21");
            sheet.createRow(20).createCell(24).setCellFormula("Z21");
            sheet.getRow(20).createCell(25).setCellValue(7.0);

            Path xlsx = writeWorkbook(workbook, "indirect.xlsx");
            Path db = tempDir.resolve("indirect.db");
            new IngestService().ingest(xlsx, 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                    ResultSet rs = c.createStatement().executeQuery(
                            "SELECT coord, is_support FROM cell WHERE coord IN ('Y21','Z21') ORDER BY coord")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("coord")).isEqualTo("Y21");
                assertThat(rs.getInt("is_support")).isEqualTo(1);
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("coord")).isEqualTo("Z21");
                assertThat(rs.getInt("is_support")).isEqualTo(1);
            }
        }
    }

    @Test
    void unlabeledIsolatedText_isOrphan() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Model");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Particulars");
            header.createCell(1).setCellValue("Amount");
            sheet.createRow(1).createCell(0).setCellValue("Civil");
            sheet.getRow(1).createCell(1).setCellValue(100.0);
            sheet.createRow(30).createCell(10).setCellValue("leftover note");

            Path xlsx = writeWorkbook(workbook, "orphan.xlsx");
            Path db = tempDir.resolve("orphan.db");
            new IngestService().ingest(xlsx, 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                    ResultSet rs = c.createStatement().executeQuery(
                            "SELECT is_orphan, is_scratch, is_support FROM cell WHERE coord = 'K31'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("is_orphan")).isEqualTo(1);
                assertThat(rs.getInt("is_scratch")).isEqualTo(0);
                assertThat(rs.getInt("is_support")).isEqualTo(0);
            }
        }
    }

    @Test
    void allMeaningfulCellsScratch_marksRegionScratch() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Scratchpad");
            sheet.createRow(0).createCell(0).setCellFormula("1+1");
            sheet.createRow(1).createCell(0).setCellFormula("2+2");

            Path xlsx = writeWorkbook(workbook, "all-scratch.xlsx");
            Path db = tempDir.resolve("region-scratch.db");
            new IngestService().ingest(xlsx, 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                    ResultSet rs = c.createStatement().executeQuery(
                            "SELECT region_type, semantic_region_type FROM region")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("semantic_region_type")).isEqualTo("scratch");
            }
        }
    }

    @Test
    void classificationNeverDeletesCellsAndAccountsInReviewQueue() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Model");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Particulars");
            header.createCell(1).setCellValue("Amount");
            sheet.createRow(1).createCell(0).setCellValue("Civil");
            sheet.getRow(1).createCell(1).setCellValue(100.0);
            sheet.createRow(8).createCell(0).setCellFormula("3+4");
            sheet.createRow(9).createCell(5).setCellValue("stray");

            Path xlsx = writeWorkbook(workbook, "preserve.xlsx");
            Path db = tempDir.resolve("preserve.db");
            new IngestService().ingest(xlsx, 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                assertThat(count(c, "SELECT COUNT(*) FROM cell")).isEqualTo(6);
                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT detail FROM review_queue WHERE category = 'semantic_accounting'")) {
                    assertThat(rs.next()).isTrue();
                    String detail = rs.getString(1);
                    assertThat(detail).contains("\"scratch\"");
                    assertThat(detail).contains("\"support\"");
                    assertThat(detail).contains("\"orphan\"");
                    assertThat(detail).contains("\"promotions\"");
                }
            }
        }
    }

    @Test
    void unlabeledPageConstant_isScratchWithStableReason() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Model");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Particulars");
            header.createCell(1).setCellValue("Amount");
            sheet.createRow(1).createCell(0).setCellValue("Civil");
            sheet.getRow(1).createCell(1).setCellValue(100.0);
            sheet.createRow(40).createCell(15).setCellValue(12.0);

            Path xlsx = writeWorkbook(workbook, "page.xlsx");
            Path db = tempDir.resolve("page.db");
            new IngestService().ingest(xlsx, 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                    ResultSet rs = c.createStatement().executeQuery(
                            "SELECT is_scratch, scratch_reason FROM cell WHERE coord = 'P41'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("is_scratch")).isEqualTo(1);
                assertThat(rs.getString("scratch_reason")).isEqualTo("PAGE_CONSTANT");
            }
        }
    }
}
