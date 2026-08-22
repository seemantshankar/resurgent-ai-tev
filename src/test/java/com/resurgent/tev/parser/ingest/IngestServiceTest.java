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
                assertThat(count(c, "review_queue")).isEqualTo(0);

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
                assertThat(count(c, "review_queue")).isEqualTo(1);

                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT category, summary, detail FROM review_queue")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("category")).isEqualTo("external_link");
                    assertThat(rs.getString("summary")).contains("[99]Missing!A1");
                    Map<String, Object> detail = Jsonb.fromJson(rs.getString("detail"), Map.class);
                    assertThat(detail).containsEntry("externalRef", "[99]Missing!A1");
                    assertThat(detail).containsEntry("coord", "A1");
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
                            .contains("\"sheetNames\"");
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
    void externalRefReconciliation_countsResolvedAndQueuedRefs() throws Exception {
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
            assertThat(metrics).containsEntry("externalRefsTotal", 2);
            assertThat(metrics).containsEntry("externalRefsResolved", 1);
            assertThat(metrics).containsEntry("externalRefsQueued", 1);
            assertThat(metrics).containsEntry("qaStatus", "success");
            assertThat(summary.status()).isEqualTo("success");
        }
    }
}
