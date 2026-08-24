package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.resurgent.tev.parser.config.ConfigLoader;
import com.resurgent.tev.parser.db.Jsonb;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ingest-seam tests for locked column roles and unit/currency inference.
 */
class RegionSchemaIngestTest {

    @TempDir
    Path tempDir;

    @Test
    void explicitHeaders_assignLockedColumnRolesWithConfidenceAndReasons() throws Exception {
        Path db = ingestXlsx(workbook -> {
            Sheet sheet = workbook.createSheet("Capex");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("S.No");
            header.createCell(1).setCellValue("Particulars");
            header.createCell(2).setCellValue("Qty");
            header.createCell(3).setCellValue("Rate");
            header.createCell(4).setCellValue("Amount");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue(1);
            data.createCell(1).setCellValue("Foundation");
            data.createCell(2).setCellValue(10);
            data.createCell(3).setCellValue(5);
            data.createCell(4).setCellValue(50);
        }, "roles.xlsx");

        List<Map<String, Object>> columns = amountRegionColumns(db);
        assertThat(role(columns, 1)).isEqualTo("serial");
        assertThat(role(columns, 2)).isEqualTo("description");
        assertThat(role(columns, 3)).isEqualTo("quantity");
        assertThat(role(columns, 4)).isEqualTo("rate");
        assertThat(role(columns, 5)).isEqualTo("amount");
        for (Map<String, Object> column : columns) {
            assertThat(conf(column)).isGreaterThan(0.5);
            assertThat((List<?>) column.get("reasons")).isNotEmpty();
        }
    }

    @Test
    void ambiguousAndUnrecognizedHeaders_remainOther() throws Exception {
        Path db = ingestXlsx(workbook -> {
            Sheet sheet = workbook.createSheet("Capex");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Particulars");
            header.createCell(1).setCellValue("Value / Rate");
            header.createCell(2).setCellValue("Notes");
            header.createCell(3).setCellValue("Amount");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("Piling");
            data.createCell(1).setCellValue(12);
            data.createCell(2).setCellValue("see annex");
            data.createCell(3).setCellValue(40);
        }, "other.xlsx");

        List<Map<String, Object>> columns = amountRegionColumns(db);
        assertThat(role(columns, 2)).isEqualTo("other");
        assertThat(role(columns, 3)).isEqualTo("other");
        assertThat(role(columns, 4)).isEqualTo("amount");
    }

    @Test
    void valueHeader_remainsOther() throws Exception {
        Path db = ingestXlsx(workbook -> {
            Sheet sheet = workbook.createSheet("Capex");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Particulars");
            header.createCell(1).setCellValue("Value");
            header.createCell(2).setCellValue("Amount");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("Piling");
            data.createCell(1).setCellValue(12);
            data.createCell(2).setCellValue(40);
        }, "value.xlsx");

        List<Map<String, Object>> columns = amountRegionColumns(db);
        assertThat(role(columns, 2)).isEqualTo("other");
        assertThat(role(columns, 3)).isEqualTo("amount");
    }

    @Test
    void yearHeaders_assignPeriodRole() throws Exception {
        Path db = ingestXlsx(workbook -> {
            Sheet sheet = workbook.createSheet("Projections");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Particulars");
            header.createCell(1).setCellValue("Year 1");
            header.createCell(2).setCellValue("Year 2");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("Revenue");
            data.createCell(1).setCellValue(10);
            data.createCell(2).setCellValue(20);
        }, "period.xlsx");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery("SELECT schema_json FROM region")) {
            assertThat(rs.next()).isTrue();
            List<Map<String, Object>> columns = Jsonb.fromJson(rs.getString(1), new TypeReference<>() {});
            assertThat(role(columns, 1)).isEqualTo("description");
            assertThat(role(columns, 2)).isEqualTo("period");
            assertThat(role(columns, 3)).isEqualTo("period");
        }
    }

    @Test
    void mergedAndStackedHeaders_stillAssignAmountRoleAndLakhUnit() throws Exception {
        Path db = ingestXlsx(workbook -> {
            Sheet sheet = workbook.createSheet("Capex");
            Row top = sheet.createRow(0);
            top.createCell(0).setCellValue("Particulars");
            top.createCell(1).setCellValue("Amount");
            Row stacked = sheet.createRow(1);
            stacked.createCell(1).setCellValue("Rs. Lakh");
            sheet.addMergedRegion(new CellRangeAddress(0, 1, 0, 0));
            Row data = sheet.createRow(2);
            data.createCell(0).setCellValue("Civil works");
            data.createCell(1).setCellValue(12.5);
        }, "merged.xlsx");

        List<Map<String, Object>> columns = amountRegionColumns(db);
        assertThat(role(columns, 1)).isEqualTo("description");
        assertThat(role(columns, 2)).isEqualTo("amount");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT inferred_unit, inferred_currency FROM region "
                                + "WHERE schema_json LIKE '%\"amount\"%'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("inferred_unit")).isEqualTo("lakh");
            assertThat(rs.getString("inferred_currency")).isEqualTo("INR");
        }
    }

    @Test
    void columnLabel_outranksNumberFormatForUnit() throws Exception {
        Path db = ingestXlsx(workbook -> {
            Sheet sheet = workbook.createSheet("Capex");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Particulars");
            header.createCell(1).setCellValue("Amount (Rs. Lakh)");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("Steel");
            Cell amount = data.createCell(1);
            amount.setCellValue(8.0);
            amount.setCellStyle(format(workbook, "#,##0.00\" Cr\""));
        }, "label-over-format.xlsx");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT inferred_unit FROM region WHERE schema_json LIKE '%\"amount\"%'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("lakh");
        }
        assertThat(reviewDetails(db, "unit_currency")).noneMatch(detail -> detail.contains("CONFLICT"));
    }

    @Test
    void unambiguousNumberFormat_usedWhenLabelsAreSilent() throws Exception {
        Path db = ingestXlsx(workbook -> {
            Sheet sheet = workbook.createSheet("Capex");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Particulars");
            header.createCell(1).setCellValue("Amount");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("Steel");
            Cell amount = data.createCell(1);
            amount.setCellValue(3.0);
            amount.setCellStyle(format(workbook, "\"₹ \"#,##0.00"));
        }, "format.xlsx");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT inferred_unit, inferred_currency, inferred_unit_conf, inferred_currency_conf "
                                + "FROM region WHERE schema_json LIKE '%\"amount\"%'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("inferred_unit")).isEqualTo("rs");
            assertThat(rs.getString("inferred_currency")).isEqualTo("INR");
            assertThat(rs.getDouble("inferred_unit_conf")).isGreaterThan(0.5);
            assertThat(rs.getDouble("inferred_currency_conf")).isGreaterThan(0.5);
        }
    }

    @Test
    void worksheetBanner_usedWhenColumnAndFormatAreSilent() throws Exception {
        Path db = ingestXlsx(workbook -> {
            Sheet sheet = workbook.createSheet("Capex");
            sheet.createRow(0).createCell(0).setCellValue("All figures in Rs. Crore");
            Row header = sheet.createRow(2);
            header.createCell(0).setCellValue("Particulars");
            header.createCell(1).setCellValue("Amount");
            Row data = sheet.createRow(3);
            data.createCell(0).setCellValue("Plant");
            data.createCell(1).setCellValue(4.2);
        }, "banner.xlsx");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT inferred_unit, inferred_currency FROM region "
                                + "WHERE schema_json LIKE '%\"amount\"%'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("inferred_unit")).isEqualTo("crore");
            assertThat(rs.getString("inferred_currency")).isEqualTo("INR");
        }
    }

    @Test
    void conflictingUnitEvidence_isUnknownAndQueuedForReview() throws Exception {
        Path db = ingestXlsx(workbook -> {
            Sheet sheet = workbook.createSheet("Capex");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Particulars");
            header.createCell(1).setCellValue("Amount (Rs. Lakh)");
            header.createCell(2).setCellValue("Total (Rs. Crore)");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("Civil");
            data.createCell(1).setCellValue(10.0);
            data.createCell(2).setCellValue(0.1);
        }, "conflict.xlsx");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT inferred_unit FROM region WHERE schema_json LIKE '%\"amount\"%'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("unknown");
        }
        assertThat(reviewDetails(db, "unit_currency")).anyMatch(detail -> detail.contains("CONFLICT"));
    }

    @Test
    void sheetNameOnly_doesNotInventAUnitAndQueuesReview() throws Exception {
        Path db = ingestXlsx(workbook -> {
            Sheet sheet = workbook.createSheet("Rs Lakh");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Particulars");
            header.createCell(1).setCellValue("Amount");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("Earthwork");
            data.createCell(1).setCellValue(9.0);
        }, "lakh-costs.xlsx");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT inferred_unit FROM region WHERE schema_json LIKE '%\"amount\"%'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("unknown");
        }
        assertThat(reviewDetails(db, "unit_currency")).anyMatch(detail -> detail.contains("NAME_ONLY"));
    }

    @Test
    void highConfidenceInrLakh_preservesNativeAmountAndRecordsRsNormalization() throws Exception {
        Path db = ingestXlsx(workbook -> {
            Sheet sheet = workbook.createSheet("Capex");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Particulars");
            header.createCell(1).setCellValue("Amount (Rs. Lakh)");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("Civil works");
            data.createCell(1).setCellValue(2.5);
        }, "normalize.xlsx");

        Map<String, Object> amount = column(amountRegionColumns(db), 2);
        assertThat(amount.get("unit")).isEqualTo("lakh");
        assertThat(amount.get("currency")).isEqualTo("INR");
        assertThat(amount.get("normalizedUnit")).isEqualTo("rs");
        assertThat(((Number) amount.get("normalizeFactor")).longValue()).isEqualTo(100_000L);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT numeric_value FROM cell WHERE coord = 'B2'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getDouble(1)).isEqualTo(2.5);
        }
    }

    @Test
    void foreignCurrency_isNotConvertedAndBlocksArithmeticTrust() throws Exception {
        Path db = ingestXlsx(workbook -> {
            Sheet sheet = workbook.createSheet("Capex");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Particulars");
            header.createCell(1).setCellValue("Amount (USD)");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("Imported equipment");
            data.createCell(1).setCellValue(100.0);
        }, "usd.xlsx");

        Map<String, Object> amount = column(amountRegionColumns(db), 2);
        assertThat(amount.get("currency")).isEqualTo("USD");
        assertThat(amount.get("normalizedUnit")).isNull();
        assertThat(amount.get("normalizeFactor")).isNull();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT numeric_value FROM cell WHERE coord = 'B2'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getDouble(1)).isEqualTo(100.0);
        }
        assertThat(reviewDetails(db, "unit_currency"))
                .anyMatch(detail -> detail.contains("FOREIGN_CURRENCY"));
    }

    @Test
    void csvWithoutNumberFormats_stillInfersRolesFromHeaders() throws Exception {
        Path csv = tempDir.resolve("capex.csv");
        Files.writeString(csv, "Particulars,Qty,Rate,Amount (Rs. Lakh)\nFoundation,10,5,50\n",
                StandardCharsets.UTF_8);
        Path db = tempDir.resolve("csv.db");
        new IngestService().ingest(csv, 1L, db);

        List<Map<String, Object>> columns = amountRegionColumns(db);
        assertThat(role(columns, 1)).isEqualTo("description");
        assertThat(role(columns, 2)).isEqualTo("quantity");
        assertThat(role(columns, 3)).isEqualTo("rate");
        assertThat(role(columns, 4)).isEqualTo("amount");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT inferred_unit, inferred_currency FROM region "
                                + "WHERE schema_json LIKE '%\"amount\"%'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("inferred_unit")).isEqualTo("lakh");
            assertThat(rs.getString("inferred_currency")).isEqualTo("INR");
        }
    }

    @Test
    void xlsWithoutCapturedFormats_stillInfersRolesFromHeaders() throws Exception {
        Path xls = tempDir.resolve("capex.xls");
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Capex");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Particulars");
            header.createCell(1).setCellValue("Amount");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("Piling");
            data.createCell(1).setCellValue(50.0);
            try (FileOutputStream out = new FileOutputStream(xls.toFile())) {
                workbook.write(out);
            }
        }
        Path db = tempDir.resolve("xls.db");
        new IngestService().ingest(xls, 1L, db, ConfigLoader.load("{\"xlsEnabled\": true}"));

        List<Map<String, Object>> columns = amountRegionColumns(db);
        assertThat(role(columns, 1)).isEqualTo("description");
        assertThat(role(columns, 2)).isEqualTo("amount");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT inferred_unit FROM region WHERE schema_json LIKE '%\"amount\"%'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("unknown");
        }
    }

    private Path ingestXlsx(WorkbookWriter writer, String name) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            writer.write(workbook);
            Path file = tempDir.resolve(name);
            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                workbook.write(out);
            }
            Path db = tempDir.resolve(name.replace(".xlsx", ".db"));
            new IngestService().ingest(file, 1L, db);
            return db;
        }
    }

    private static CellStyle format(Workbook workbook, String pattern) {
        CellStyle style = workbook.createCellStyle();
        DataFormat dataFormat = workbook.createDataFormat();
        style.setDataFormat(dataFormat.getFormat(pattern));
        return style;
    }

    private static List<Map<String, Object>> amountRegionColumns(Path db) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT schema_json FROM region WHERE schema_json LIKE '%\"amount\"%'")) {
            assertThat(rs.next()).isTrue();
            return Jsonb.fromJson(rs.getString(1), new TypeReference<>() {});
        }
    }

    private static List<String> reviewDetails(Path db, String category) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT detail FROM review_queue WHERE category = '" + category + "'")) {
            java.util.ArrayList<String> details = new java.util.ArrayList<>();
            while (rs.next()) {
                details.add(rs.getString(1));
            }
            return details;
        }
    }

    private static Map<String, Object> column(List<Map<String, Object>> columns, int col) {
        return columns.stream()
                .filter(entry -> ((Number) entry.get("col")).intValue() == col)
                .findFirst()
                .orElseThrow();
    }

    private static String role(List<Map<String, Object>> columns, int col) {
        return (String) column(columns, col).get("role");
    }

    private static double conf(Map<String, Object> column) {
        return ((Number) column.get("conf")).doubleValue();
    }

    @FunctionalInterface
    private interface WorkbookWriter {
        void write(XSSFWorkbook workbook);
    }
}
