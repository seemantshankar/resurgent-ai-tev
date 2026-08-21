package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.config.ConfigLoader;
import com.resurgent.tev.parser.config.ParserConfig;
import com.resurgent.tev.parser.db.Jsonb;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                        "SELECT external_ref, external_link_id FROM cell WHERE coord = 'A1'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("external_ref")).isEqualTo("[1]Other!A1");
                    assertThat(rs.getLong("external_link_id")).isEqualTo(linkId);
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
}
