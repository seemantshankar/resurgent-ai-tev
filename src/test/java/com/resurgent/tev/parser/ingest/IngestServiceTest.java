package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.config.ConfigLoader;
import com.resurgent.tev.parser.config.ParserConfig;
import com.resurgent.tev.parser.db.Jsonb;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for the full XLSX ingestion path, focusing on external-link
 * resolution and defined-name pruning.
 */
class IngestServiceTest {

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

    private long count(Connection c, String table) throws Exception {
        try (ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void csvIngestLeavesStyleColumnsNullAndRecordsWhyStyleWasUnavailable() throws Exception {
        Path csv = tempDir.resolve("model.csv");
        Files.writeString(csv, "Title,Amount\nProject cost summary,1200\n");
        Path db = tempDir.resolve("model.csv.db");

        new IngestService().ingest(csv, 1L, db);

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT is_bold, has_fill, has_border, number_format FROM cell WHERE coord = 'A1'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("is_bold")).isNull();
                assertThat(rs.getObject("has_fill")).isNull();
                assertThat(rs.getObject("has_border")).isNull();
                assertThat(rs.getString("number_format")).isNull();
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT raw_metadata FROM source_file")) {
                assertThat(rs.next()).isTrue();
                Map<String, Object> metadata = Jsonb.fromJson(rs.getString(1), Map.class);
                assertThat(metadata).containsEntry("style_capture_reason", "csv_has_no_cell_styles");
            }
        }
    }

    @Test
    void xlsxIngestPersistsStyledTitleFields() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Model");
            org.apache.poi.ss.usermodel.Cell title = sheet.createRow(0).createCell(0);
            title.setCellValue("Project cost summary");
            org.apache.poi.ss.usermodel.CellStyle style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("$#,##0.00"));
            style.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.YELLOW.getIndex());
            style.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            style.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            org.apache.poi.ss.usermodel.Font font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);
            title.setCellStyle(style);

            Path xlsx = writeWorkbook(workbook, "styled-title.xlsx");
            Path db = tempDir.resolve("styled-title.db");
            new IngestService().ingest(xlsx, 1L, db);
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                    ResultSet rs = c.createStatement().executeQuery(
                            "SELECT is_bold, has_fill, has_border, number_format FROM cell WHERE coord = 'A1'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("is_bold")).isEqualTo(1);
                assertThat(rs.getInt("has_fill")).isEqualTo(1);
                assertThat(rs.getInt("has_border")).isEqualTo(1);
                assertThat(rs.getString("number_format")).isEqualTo("$#,##0.00");
            }
        }
    }

    @Test
    void regionDetectionSplitsABannerThatBridgesTwoDisjointBlocks() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Model");
            sheet.createRow(0).createCell(0).setCellValue("Revenue schedule");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));
            sheet.createRow(1).createCell(0).setCellValue("Left");
            sheet.getRow(1).createCell(1).setCellValue(1.0);
            sheet.getRow(1).createCell(4).setCellValue("Right");
            sheet.getRow(1).createCell(5).setCellValue(2.0);

            Path xlsx = writeWorkbook(workbook, "banner-two-blocks.xlsx");
            Path db = tempDir.resolve("banner-two-blocks.db");
            new IngestService().ingest(xlsx, 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                assertThat(count(c, "region")).isEqualTo(3);
                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT region_type, region_key FROM region ORDER BY start_row, start_col")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("region_type")).isEqualTo("unknown");
                    assertThat(rs.getString("region_key")).isEqualTo("Model!A1");
                }
                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT COUNT(DISTINCT region_id) FROM cell WHERE region_id IS NOT NULL")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(3);
                }
                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT COUNT(*) = COUNT(region_id) FROM cell")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
        }
    }

    @Test
    void regionDetectionKeepsBannerWithASingleBlock() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Model");
            sheet.createRow(0).createCell(0).setCellValue("Revenue schedule");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));
            sheet.createRow(1).createCell(0).setCellValue("Label");
            sheet.getRow(1).createCell(1).setCellValue(1.0);

            Path xlsx = writeWorkbook(workbook, "banner-one-block.xlsx");
            Path db = tempDir.resolve("banner-one-block.db");
            new IngestService().ingest(xlsx, 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                assertThat(count(c, "region")).isEqualTo(1);
            }
        }
    }

    @Test
    void regionPersistenceUsesPeriodHeadersForAxisAndDenormalizedCellLabels() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Model");
            Row headers = sheet.createRow(0);
            headers.createCell(0).setCellValue("Particulars");
            headers.createCell(1).setCellValue("FY 2024-25");
            headers.createCell(2).setCellValue("FY 2025-26");
            Row pbit = sheet.createRow(1);
            pbit.createCell(0).setCellValue("PBIT");
            pbit.createCell(1).setCellValue(100.0);
            pbit.createCell(2).setCellValue(200.0);

            Path xlsx = writeWorkbook(workbook, "period-axis.xlsx");
            Path db = tempDir.resolve("period-axis.db");
            new IngestService().ingest(xlsx, 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT header_rows, period_axis, detection_reasons FROM region")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(Jsonb.fromJson(rs.getString("header_rows"), List.class)).containsExactly(1);
                    assertThat(Jsonb.fromJson(rs.getString("period_axis"), Map.class))
                            .containsEntry("B", 1).containsEntry("C", 2);
                    assertThat(Jsonb.fromJson(rs.getString("detection_reasons"), List.class)).isNotEmpty();
                }
                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT row_label, col_label FROM cell WHERE coord = 'B2'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("row_label")).isEqualTo("PBIT");
                    assertThat(rs.getString("col_label")).isEqualTo("FY 2024-25");
                }
                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT category, detail FROM review_queue WHERE category = 'region_classification'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(Jsonb.fromJson(rs.getString("detail"), Map.class))
                            .containsEntry("regionType", "unknown")
                            .containsKey("reasonCodes");
                }
            }
        }
    }

    @Test
    void regionDetectionUsesFormulaSkeletonsForOneCellDilation() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Model");
            sheet.createRow(0).createCell(0).setCellFormula("1+1");
            sheet.createRow(2).createCell(0).setCellFormula("2+2");

            Path xlsx = writeWorkbook(workbook, "formula-gap.xlsx");
            Path db = tempDir.resolve("formula-gap.db");
            new IngestService().ingest(xlsx, 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                assertThat(count(c, "region")).isEqualTo(1);
            }
        }
    }

    @Test
    void regionDetectionAllowsOneSkeletonTokenDifferenceForOneCellDilation() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Model");
            sheet.createRow(0).createCell(0).setCellFormula("SUM(1)");
            sheet.createRow(2).createCell(0).setCellFormula("SUM(2)");

            Path xlsx = writeWorkbook(workbook, "formula-one-token-gap.xlsx");
            Path db = tempDir.resolve("formula-one-token-gap.db");
            new IngestService().ingest(xlsx, 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                assertThat(count(c, "region")).isEqualTo(1);
            }
        }
    }

    @Test
    void regionBreakScoringSplitsPersistentStyledSchemaChange() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Model");
            for (int row = 0; row < 3; row++) {
                sheet.createRow(row).createCell(0).setCellFormula("1+1");
            }
            org.apache.poi.ss.usermodel.Cell title = sheet.createRow(3).createCell(0);
            title.setCellValue("Details");
            org.apache.poi.ss.usermodel.CellStyle style = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);
            title.setCellStyle(style);
            for (int row = 4; row < 7; row++) {
                sheet.createRow(row).createCell(0).setCellValue("line item " + row);
                sheet.getRow(row).createCell(1).setCellValue(row);
            }

            Path xlsx = writeWorkbook(workbook, "scored-region-break.xlsx");
            Path db = tempDir.resolve("scored-region-break.db");
            new IngestService().ingest(xlsx, 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                assertThat(count(c, "region")).isEqualTo(2);
            }
        }
    }

    @Test
    void regionBreakScoringDoesNotSplitKnownTotal() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Model");
            for (int row = 0; row < 3; row++) {
                sheet.createRow(row).createCell(0).setCellFormula("1+1");
            }
            sheet.createRow(3).createCell(0).setCellValue("Total");
            sheet.getRow(3).createCell(1).setCellFormula("SUM(A1:A3)");
            sheet.createRow(4).createCell(0).setCellFormula("1+1");

            Path xlsx = writeWorkbook(workbook, "total-is-not-break.xlsx");
            Path db = tempDir.resolve("total-is-not-break.db");
            new IngestService().ingest(xlsx, 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                assertThat(count(c, "region")).isEqualTo(1);
            }
        }
    }

    @Test
    void regionDetectionDoesNotTreatHiddenColumnsAsConnectivity() throws Exception {
        try (XSSFWorkbook emptySeparator = new XSSFWorkbook();
                XSSFWorkbook populatedSeparator = new XSSFWorkbook()) {
            Sheet empty = emptySeparator.createSheet("Model");
            empty.createRow(0).createCell(0).setCellValue(1.0);
            empty.getRow(0).createCell(2).setCellValue(2.0);
            empty.setColumnHidden(1, true);

            Sheet populated = populatedSeparator.createSheet("Model");
            populated.createRow(0).createCell(0).setCellValue(1.0);
            populated.getRow(0).createCell(1).setCellValue(3.0);
            populated.getRow(0).createCell(2).setCellValue(2.0);
            populated.setColumnHidden(1, true);

            Path emptyXlsx = writeWorkbook(emptySeparator, "hidden-empty-separator.xlsx");
            Path populatedXlsx = writeWorkbook(populatedSeparator, "hidden-populated-separator.xlsx");
            Path emptyDb = tempDir.resolve("hidden-empty-separator.db");
            Path populatedDb = tempDir.resolve("hidden-populated-separator.db");
            new IngestService().ingest(emptyXlsx, 1L, emptyDb);
            new IngestService().ingest(populatedXlsx, 1L, populatedDb);

            try (Connection emptyConnection = DriverManager.getConnection("jdbc:sqlite:" + emptyDb);
                    Connection populatedConnection = DriverManager.getConnection("jdbc:sqlite:" + populatedDb)) {
                assertThat(count(emptyConnection, "region")).isEqualTo(2);
                assertThat(count(populatedConnection, "region")).isEqualTo(1);
            }
        }
    }

    @Test
    void externalLinkProducesRowAndResolvedCellRef() throws Exception {
        try (XSSFWorkbook external = new XSSFWorkbook();
                XSSFWorkbook main = new XSSFWorkbook()) {
            external.createSheet("Other");
            Sheet sheet = main.createSheet("Sheet1");
            main.linkExternalWorkbook("other.xlsx", external);
            sheet.createRow(0).createCell(0).setCellFormula("[1]Other!A1");

            Path xlsx = writeWorkbook(main, "external.xlsx");
            Path db = tempDir.resolve("external.db");
            new IngestService().ingest(xlsx, 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                assertThat(count(c, "workbook")).isEqualTo(1);
                assertThat(count(c, "external_link")).isEqualTo(1);
                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT COUNT(*) FROM review_queue WHERE category = 'formula_reference'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isZero();
                }

                long linkId;
                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT external_link_id, target_path FROM external_link")) {
                    assertThat(rs.next()).isTrue();
                    linkId = rs.getLong("external_link_id");
                    assertThat(rs.getString("target_path")).isEqualTo("other.xlsx");
                }

                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT formula_text FROM cell WHERE coord = 'A1'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("formula_text")).isEqualTo("[1]Other!A1");
                }
            }
        }
    }

    @Test
    void unresolvableExternalRefLandsInReviewQueue() throws Exception {
        try (XSSFWorkbook main = new XSSFWorkbook()) {
            Sheet sheet = main.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellFormula("[99]Missing!A1");

            Path xlsx = writeWorkbook(main, "missing.xlsx");
            Path db = tempDir.resolve("missing.db");
            new IngestService().ingest(xlsx, 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                assertThat(count(c, "external_link")).isEqualTo(0);
                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT COUNT(*) FROM review_queue WHERE category = 'formula_reference'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }

                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT category, summary, detail FROM review_queue WHERE category = 'formula_reference'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("category")).isEqualTo("formula_reference");
                    assertThat(rs.getString("summary")).contains("[99]Missing").contains("A1");
                    Map<String, Object> detail = Jsonb.fromJson(rs.getString("detail"), Map.class);
                    assertThat(detail.get("rawToken").toString()).contains("[99]Missing").contains("A1");
                }
            }
        }
    }

    @Test
    void definedNamesArePrunedToWorkbookAndPreservedInRawMetadata() throws Exception {
        try (XSSFWorkbook main = new XSSFWorkbook()) {
            Sheet sheet = main.createSheet("Sheet1");

            Name referenced = main.createName();
            referenced.setNameName("ReferencedName");
            referenced.setRefersToFormula("Sheet1!$A$1");

            Name unreferenced = main.createName();
            unreferenced.setNameName("UnreferencedName");
            unreferenced.setRefersToFormula("Sheet1!$B$1");

            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(1.0);
            row.createCell(1).setCellFormula("ReferencedName+1");

            Path xlsx = writeWorkbook(main, "names.xlsx");
            Path db = tempDir.resolve("names.db");
            new IngestService().ingest(xlsx, 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                String rawMetadata;
                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT raw_metadata FROM source_file")) {
                    assertThat(rs.next()).isTrue();
                    rawMetadata = rs.getString(1);
                }
                Map<String, Object> raw = Jsonb.fromJson(rawMetadata, Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> definedInRaw = (Map<String, Object>) raw.get("definedNames");
                assertThat(definedInRaw).containsKeys("ReferencedName", "UnreferencedName");

                String workbookNames;
                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT defined_names FROM workbook")) {
                    assertThat(rs.next()).isTrue();
                    workbookNames = rs.getString(1);
                }
                List<String> referencedNames = Jsonb.fromJson(workbookNames,
                        new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                assertThat(referencedNames).containsExactly("ReferencedName");
            }
        }
    }

    @Test
    void xlsDisabled_rejectsWithXlsDisabledReasonAndPersistsRow() throws Exception {
        Path xls = tempDir.resolve("legacy.xls");
        Files.write(xls, new byte[] {0x50});
        Path db = tempDir.resolve("xls-reject.db");

        assertThatThrownBy(() -> new IngestService().ingest(xls, 7L, db))
                .isInstanceOf(IngestRejectionException.class)
                .satisfies(e -> {
                    IngestRejectionException r = (IngestRejectionException) e;
                    assertThat(r.reason()).isEqualTo(RejectionReason.XLS_DISABLED);
                    assertThat(r.reasonCode()).isEqualTo("xls_disabled");
                });

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            assertThat(count(c, "source_file")).isEqualTo(0);
            assertThat(count(c, "ingest_rejection")).isEqualTo(1);
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT mandate_id, file_name, reason, detail FROM ingest_rejection")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("mandate_id")).isEqualTo(7L);
                assertThat(rs.getString("file_name")).isEqualTo("legacy.xls");
                assertThat(rs.getString("reason")).isEqualTo("xls_disabled");
                assertThat(rs.getString("detail")).contains("configuredLimit")
                        .contains("observedValue");
            }

            assertThat(count(c, "audit_log")).isEqualTo(1);
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT parse_run_id, event_type, severity, payload FROM audit_log")) {
                assertThat(rs.next()).isTrue();
                rs.getLong("parse_run_id");
                assertThat(rs.wasNull()).isTrue();
                assertThat(rs.getString("event_type")).isEqualTo("ingest_rejected");
                assertThat(rs.getString("severity")).isEqualTo("warning");
                assertThat(rs.getString("payload")).contains("xls_disabled")
                        .contains("legacy.xls");
            }
        }
    }

    @Test
    void fileExceedingConfiguredLimit_rejectsAndPersistsRow() throws Exception {
        Path csv = tempDir.resolve("big.csv");
        Files.writeString(csv, "too large for one-byte limit");
        Path db = tempDir.resolve("size-reject.db");
        ParserConfig config = ConfigLoader.load("{\"maxFileSizeBytes\": 1}");

        assertThatThrownBy(() -> new IngestService().ingest(csv, 9L, db, config))
                .isInstanceOf(IngestRejectionException.class)
                .satisfies(e -> {
                    IngestRejectionException r = (IngestRejectionException) e;
                    assertThat(r.reason()).isEqualTo(RejectionReason.FILE_TOO_LARGE);
                    assertThat(r.configuredLimit()).isEqualTo(1L);
                });

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            assertThat(count(c, "source_file")).isEqualTo(0);
            assertThat(count(c, "ingest_rejection")).isEqualTo(1);
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT reason FROM ingest_rejection")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("reason")).isEqualTo("file_too_large");
            }
        }
    }

    @Test
    void dimensionalCap_rejectsThroughSafetyEnforcer() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("A");
            workbook.createSheet("B");
            workbook.createSheet("C");

            Path xlsx = writeWorkbook(workbook, "too-many-sheets.xlsx");
            Path db = tempDir.resolve("safety.db");
            ParserConfig config = ConfigLoader.load("{\"maxSheetCount\": 2}");

            assertThatThrownBy(() -> new IngestService().ingest(xlsx, 13L, db, config))
                    .isInstanceOf(IngestRejectionException.class)
                    .satisfies(e -> {
                        IngestRejectionException r = (IngestRejectionException) e;
                        assertThat(r.reason()).isEqualTo(RejectionReason.SHEET_COUNT_EXCEEDED);
                        assertThat(r.configuredLimit()).isEqualTo(2);
                        assertThat(r.observedValue()).isEqualTo(3);
                    });

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                assertThat(count(c, "source_file")).isEqualTo(0);
                assertThat(count(c, "ingest_rejection")).isEqualTo(1);
                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT mandate_id, file_name, reason FROM ingest_rejection")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong("mandate_id")).isEqualTo(13L);
                    assertThat(rs.getString("file_name")).isEqualTo("too-many-sheets.xlsx");
                    assertThat(rs.getString("reason")).isEqualTo("sheet_count_exceeded");
                }
            }
        }
    }

    @Test
    void xlsEnabled_ingestsLegacyWorkbook() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Legacy");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("Name");
            row.createCell(1).setCellValue(123.0);

            Path xls = tempDir.resolve("legacy.xls");
            try (FileOutputStream out = new FileOutputStream(xls.toFile())) {
                workbook.write(out);
            }

            Path db = tempDir.resolve("xls.db");
            ParserConfig config = ConfigLoader.load("{\"xlsEnabled\": true}");
            IngestSummary summary = new IngestService().ingest(xls, 11L, db, config);

            assertThat(summary.existingRun()).isFalse();
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                assertThat(count(c, "source_file")).isEqualTo(1);
                assertThat(count(c, "parse_run")).isEqualTo(1);
                assertThat(count(c, "worksheet")).isEqualTo(1);
                assertThat(count(c, "cell")).isEqualTo(2);
                assertThat(count(c, "ingest_rejection")).isEqualTo(0);

                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT formula_state FROM cell WHERE coord = 'B1'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).satisfiesAnyOf(
                            s -> assertThat(s).isNull(),
                            s -> assertThat(s).isEqualTo("unavailable"));
                }

                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT raw_metadata FROM source_file")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1))
                            .contains("\"format\":\"xls\"")
                            .contains("\"sheetNames\"")
                            .contains("\"style_capture_reason\":\"xls_style_capture_not_supported\"");
                }
            }
        }
    }

    @Test
    void idempotentReingest_returnsExistingRunWithoutDuplicateGraph() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellValue(42.0);

            Path xlsx = writeWorkbook(workbook, "idempotent.xlsx");
            Path db = tempDir.resolve("idempotent.db");
            byte[] bytesBeforeIngest = Files.readAllBytes(xlsx);

            IngestSummary first = new IngestService().ingest(xlsx, 12L, db);
            assertThat(first.existingRun()).isFalse();
            assertThat(Files.readAllBytes(xlsx)).isEqualTo(bytesBeforeIngest);

            IngestSummary second = new IngestService().ingest(xlsx, 12L, db);
            assertThat(second.existingRun()).isTrue();
            assertThat(second.parseRunId()).isEqualTo(first.parseRunId());
            assertThat(second.sourceFileId()).isEqualTo(first.sourceFileId());

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                assertThat(count(c, "source_file")).isEqualTo(1);
                assertThat(count(c, "parse_run")).isEqualTo(1);
                assertThat(count(c, "worksheet")).isEqualTo(1);
                assertThat(count(c, "cell")).isEqualTo(1);
            }
        }
    }

    @Test
    void identicalInput_producesByteIdenticalMetricsAcrossRuns() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellValue(42.0);
            sheet.createRow(1).createCell(1).setCellValue("text");

            Path xlsx = writeWorkbook(workbook, "deterministic.xlsx");
            Path dbA = tempDir.resolve("deterministic-a.db");
            Path dbB = tempDir.resolve("deterministic-b.db");

            IngestSummary a = new IngestService().ingest(xlsx, 1L, dbA);
            IngestSummary b = new IngestService().ingest(xlsx, 1L, dbB);

            assertThat(a.status()).isEqualTo("success");
            assertThat(a.metricsJson()).isEqualTo(b.metricsJson());

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbA)) {
                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT metrics, status FROM parse_run")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("metrics")).isEqualTo(a.metricsJson());
                    assertThat(rs.getString("status")).isEqualTo("success");
                }
            }
        }
    }

    @Test
    void reportPayloadIsByteIdenticalToStoredMetrics() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellValue(7.0);

            Path xlsx = writeWorkbook(workbook, "report.xlsx");
            Path db = tempDir.resolve("report.db");
            Path report = tempDir.resolve("report.json");

            IngestSummary summary = new IngestService().ingest(xlsx, 1L, db);
            summary.writeReport(report);

            String reportContent = Files.readString(report).strip();
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT metrics FROM parse_run")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(reportContent).isEqualTo(rs.getString("metrics"));
                }
            }
        }
    }

    @Test
    void provenanceAndAuditLogArePopulatedOnSuccessfulIngest() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(42.0);
            row.createCell(1).setCellValue("text");

            Path xlsx = writeWorkbook(workbook, "provenance.xlsx");
            Path db = tempDir.resolve("provenance.db");
            IngestSummary summary = new IngestService().ingest(xlsx, 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                long cellCount = count(c, "cell");
                assertThat(count(c, "provenance")).isEqualTo(cellCount);
                assertThat(count(c, "audit_log")).isGreaterThan(0);

                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT p.entity_type, p.location, p.source_file_id, p.parse_run_id"
                                + " FROM provenance p JOIN cell c ON p.entity_id = c.cell_id"
                                + " WHERE c.coord = 'A1'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("entity_type")).isEqualTo("cell");
                    assertThat(rs.getString("location")).isEqualTo("Sheet1!A1");
                    assertThat(rs.getLong("source_file_id")).isEqualTo(summary.sourceFileId());
                    assertThat(rs.getLong("parse_run_id")).isEqualTo(summary.parseRunId());
                }

                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT event_type, severity FROM audit_log"
                                + " WHERE parse_run_id = " + summary.parseRunId()
                                + " ORDER BY audit_log_id")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("event_type")).isEqualTo("parse_run_started");
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("event_type")).isEqualTo("parse_run_completed");
                    assertThat(rs.getString("severity")).isEqualTo("info");
                    assertThat(rs.next()).isFalse();
                }
            }
        }
    }

    @Test
    void referenceReconciliation_countsResolvedAndUnresolvedRefs() throws Exception {
        try (XSSFWorkbook external = new XSSFWorkbook();
                XSSFWorkbook main = new XSSFWorkbook()) {
            external.createSheet("Other");
            Sheet sheet = main.createSheet("Sheet1");
            main.linkExternalWorkbook("other.xlsx", external);
            sheet.createRow(0).createCell(0).setCellFormula("[1]Other!A1");
            sheet.getRow(0).createCell(1).setCellFormula("[99]Missing!A1");

            Path xlsx = writeWorkbook(main, "mixed-refs.xlsx");
            Path db = tempDir.resolve("mixed-refs.db");
            IngestSummary summary = new IngestService().ingest(xlsx, 1L, db);

            Map<String, Object> metrics = Jsonb.fromJson(summary.metricsJson(), Map.class);
            assertThat(metrics).containsEntry("referencesTotal", 2);
            assertThat(metrics).containsEntry("referencesResolved", 1);
            assertThat(metrics).containsEntry("referencesUnresolved", 1);
            assertThat(metrics).containsEntry("qaStatus", "success");
            assertThat(summary.status()).isEqualTo("success");
        }
    }

    // ---- §13 synthetic fixtures ----

    @Test
    void refToDeletedSheetIsUnresolvedSheetNotFoundViaFullIngest() throws Exception {
        try (XSSFWorkbook main = new XSSFWorkbook()) {
            Sheet sheet = main.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellFormula("DeletedSheet!A1");

            Path xlsx = writeWorkbook(main, "deleted-sheet.xlsx");
            Path db = tempDir.resolve("deleted-sheet.db");
            new IngestService().ingest(xlsx, 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                    ResultSet rs = c.createStatement().executeQuery(
                            "SELECT unresolved_reason FROM cell_reference")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("unresolved_reason")).isEqualTo("sheet_not_found");
            }
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                assertThat(count(c, "review_queue")).isGreaterThanOrEqualTo(1);
            }
        }
    }

    @Test
    void barrierFunctionStopsErrorCascadeViaFullIngest() throws Exception {
        try (XSSFWorkbook main = new XSSFWorkbook()) {
            Sheet sheet = main.createSheet("Sheet1");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellFormula("1/0");
            row.createCell(1).setCellFormula("IFERROR(A1,0)");
            row.createCell(2).setCellFormula("B1");

            org.apache.poi.ss.usermodel.FormulaEvaluator evaluator =
                    main.getCreationHelper().createFormulaEvaluator();
            evaluator.evaluateFormulaCell(row.getCell(0));

            Path xlsx = writeWorkbook(main, "barrier.xlsx");
            Path db = tempDir.resolve("barrier.db");
            new IngestService().ingest(xlsx, 1L, db);

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
    }

    @Test
    void circularReferenceSeverityFollowsIterativeCalcSetting() throws Exception {
        try (XSSFWorkbook main = new XSSFWorkbook()) {
            Sheet sheet = main.createSheet("Sheet1");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellFormula("B1");
            row.createCell(1).setCellFormula("A1");
            main.getCTWorkbook().addNewCalcPr().setIterate(true);

            Path xlsx = writeWorkbook(main, "circular-iterative.xlsx");
            Path db = tempDir.resolve("circular-iterative.db");
            new IngestService().ingest(xlsx, 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                    ResultSet rs = c.createStatement().executeQuery(
                            "SELECT detail FROM review_queue WHERE category = 'circular_reference'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("detail")).contains("\"severity\":\"info\"");
            }
        }

        try (XSSFWorkbook main = new XSSFWorkbook()) {
            Sheet sheet = main.createSheet("Sheet1");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellFormula("B1");
            row.createCell(1).setCellFormula("A1");

            Path xlsx = writeWorkbook(main, "circular-noniterative.xlsx");
            Path db = tempDir.resolve("circular-noniterative.db");
            new IngestService().ingest(xlsx, 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                    ResultSet rs = c.createStatement().executeQuery(
                            "SELECT detail FROM review_queue WHERE category = 'circular_reference'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("detail")).contains("\"severity\":\"warning\"");
            }
        }
    }
}
