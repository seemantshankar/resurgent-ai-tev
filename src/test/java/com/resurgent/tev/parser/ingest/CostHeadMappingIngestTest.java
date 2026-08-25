package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.Main;
import com.resurgent.tev.parser.config.ParserConfig;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CostHeadMappingIngestTest {

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

    @Test
    void classifiedRegion_persistsWorkbookLabelNotCostHeadCode() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("CASH FLOW");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("TOTAL INFLOWS");
            row.createCell(1).setCellValue("Plant & Machinery");
            row.createCell(2).setCellValue(100.0);

            Path db = tempDir.resolve("classified-label.db");
            new IngestService().ingest(writeWorkbook(workbook, "inflows.xlsx"), 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                    ResultSet rs = c.createStatement().executeQuery(
                            "SELECT m.source_label, h.code, q.status, q.detail"
                                    + " FROM cost_head_mapping m"
                                    + " JOIN cost_head h ON h.cost_head_id = m.cost_head_id"
                                    + " JOIN review_queue q ON q.category = 'cost_head_mapping'"
                                    + " AND CAST(json_extract(q.detail, '$.mappingId') AS INTEGER)"
                                    + " = m.cost_head_mapping_id"
                                    + " WHERE h.code = 'PLANT_MACHINERY'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("source_label")).isEqualTo("TOTAL INFLOWS");
                assertThat(rs.getString("code")).isEqualTo("PLANT_MACHINERY");
                assertThat(rs.getString("status")).isEqualTo("Pending");
                assertThat(rs.getString("detail")).contains("\"sourceLabel\":\"TOTAL INFLOWS\"");
            }
        }
    }

    @Test
    void uniqueExactAlias_createsCostHeadAndCalculatedMapping() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Capex");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Civil works");
            header.createCell(1).setCellValue("Amount");
            sheet.createRow(1).createCell(0).setCellValue("Foundation");
            sheet.getRow(1).createCell(1).setCellValue(100.0);

            Path db = tempDir.resolve("exact.db");
            new IngestService().ingest(writeWorkbook(workbook, "civil.xlsx"), 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                try (ResultSet heads = c.createStatement().executeQuery("SELECT code FROM cost_head")) {
                    assertThat(heads.next()).isTrue();
                    assertThat(heads.getString(1)).isEqualTo("CIVIL");
                }
                try (ResultSet mapping = c.createStatement().executeQuery(
                        "SELECT match_method, confidence FROM cost_head_mapping")) {
                    assertThat(mapping.next()).isTrue();
                    assertThat(mapping.getString("match_method")).isEqualTo("exact_alias");
                    assertThat(mapping.getDouble("confidence")).isEqualTo(1.0);
                }
                try (ResultSet unobserved = c.createStatement().executeQuery(
                        "SELECT detail FROM review_queue WHERE category = 'unobserved_cost_heads'")) {
                    assertThat(unobserved.next()).isTrue();
                    assertThat(unobserved.getString(1)).contains("LAND");
                    assertThat(unobserved.getString(1)).doesNotContain("\"CIVIL\"");
                }
            }
        }
    }

    @Test
    void fuzzyProposal_staysPendingAndDoesNotTreatScoreAsTruth() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Capex");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("civil wrks");
            header.createCell(1).setCellValue("Amount");
            sheet.createRow(1).createCell(0).setCellValue("Piling");
            sheet.getRow(1).createCell(1).setCellValue(50.0);

            Path db = tempDir.resolve("fuzzy.db");
            new IngestService().ingest(writeWorkbook(workbook, "fuzzy.xlsx"), 1L, db);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                    ResultSet rs = c.createStatement().executeQuery(
                            "SELECT m.match_method, q.status, m.source_label"
                                    + " FROM cost_head_mapping m"
                                    + " JOIN review_queue q ON q.category = 'cost_head_mapping'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("match_method")).isEqualTo("fuzzy_proposal");
                assertThat(rs.getString("status")).isEqualTo("Pending");
                assertThat(rs.getString("source_label")).isEqualToIgnoringCase("civil wrks");
            }
            assertThat(CostHeadMapper.rankedCodes("civil wrks").getFirst()).isEqualTo("CIVIL");
        }
    }

    @Test
    void acceptThenReingest_carriesDecisionWithoutChangingVocabulary() throws Exception {
        Path db = tempDir.resolve("carry.db");
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Capex");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("civil wrks");
            header.createCell(1).setCellValue("Amount");
            sheet.createRow(1).createCell(0).setCellValue("Piling");
            sheet.getRow(1).createCell(1).setCellValue(50.0);
            xlsx = writeWorkbook(workbook, "carry.xlsx");
        }
        new IngestService().ingest(xlsx, 1L, db);

        long reviewId;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT review_queue_id FROM review_queue WHERE category = 'cost_head_mapping'")) {
            assertThat(rs.next()).isTrue();
            reviewId = rs.getLong(1);
        }

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = Main.commandLine()
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute("review", "accept", "--db", db.toString(), "--actor", "analyst",
                        "--reason", "confirmed civil", String.valueOf(reviewId));
        assertThat(exit).as(err.toString()).isZero();

        ParserConfig reparse = new ParserConfig(
                100L * 1024 * 1024, 200, 1_000_000, 16_384, 5_000_000L, 100,
                false, true, true, 4);
        new IngestService().ingest(xlsx, 1L, db, reparse);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT match_method FROM cost_head_mapping ORDER BY cost_head_mapping_id DESC")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("carried");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT source_label FROM cost_head_mapping_decision WHERE decision = 'Accepted'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualToIgnoringCase("civil wrks");
        }
        assertThat(CostHeadVocabulary.exactMatch("civil wrks")).isEmpty();
    }

    @Test
    void equipmentLabel_queuesAmbiguousExactAliases() throws Exception {
        Path db = tempDir.resolve("ambiguous.db");
        new IngestService().ingest(writeCapex("equipment.xlsx", "equipment"), 1L, db);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT COUNT(*) FROM cost_head_mapping WHERE match_method = 'exact_alias'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(2);
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT COUNT(*) FROM review_queue WHERE category = 'cost_head_mapping' AND status = 'Pending'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(2);
        }
    }

    @Test
    void reject_doesNotCarryAndAuditIsRecorded() throws Exception {
        Path db = tempDir.resolve("reject.db");
        Path xlsx = writeCapex("reject.xlsx", "civil wrks");
        new IngestService().ingest(xlsx, 1L, db);
        long reviewId = pendingMappingReviewId(db);
        new com.resurgent.tev.parser.review.ReviewService()
                .rejectMapping(db, reviewId, "analyst", "not civil");
        ParserConfig reparse = new ParserConfig(
                100L * 1024 * 1024, 200, 1_000_000, 16_384, 5_000_000L, 100,
                false, true, true, 4);
        new IngestService().ingest(xlsx, 1L, db, reparse);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT match_method FROM cost_head_mapping ORDER BY cost_head_mapping_id DESC")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("fuzzy_proposal");
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM audit_log WHERE event_type = 'mapping_rejected'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void changedSourceBytes_reopenReview() throws Exception {
        Path db = tempDir.resolve("reopen.db");
        Path first = writeCapex("first.xlsx", "civil wrks");
        new IngestService().ingest(first, 1L, db);
        long reviewId = pendingMappingReviewId(db);
        new com.resurgent.tev.parser.review.ReviewService()
                .acceptMapping(db, reviewId, "analyst", "ok");
        Path second = writeCapex("second.xlsx", "civil wrks extra");
        new IngestService().ingest(second, 1L, db);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT match_method FROM cost_head_mapping m"
                                + " JOIN parse_run p ON p.parse_run_id = m.parse_run_id"
                                + " ORDER BY m.cost_head_mapping_id DESC")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isNotEqualTo("carried");
        }
    }

    @Test
    void reviewCli_listShowRejectExits() throws Exception {
        Path db = tempDir.resolve("cli.db");
        new IngestService().ingest(writeCapex("cli.xlsx", "civil wrks"), 1L, db);
        long reviewId = pendingMappingReviewId(db);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int list = Main.commandLine().setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute("review", "list", "--db", db.toString());
        assertThat(list).isZero();
        assertThat(out.toString()).contains(String.valueOf(reviewId));
        int show = Main.commandLine().setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute("review", "show", "--db", db.toString(), String.valueOf(reviewId));
        assertThat(show).isZero();
        int reject = Main.commandLine().setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute("review", "reject", "--db", db.toString(), "--actor", "analyst",
                        String.valueOf(reviewId));
        assertThat(reject).as(err.toString()).isZero();
        int missing = Main.commandLine().setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute("review", "show", "--db", db.toString(), String.valueOf(reviewId));
        assertThat(missing).isEqualTo(1);
    }

    private Path writeCapex(String name, String label) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Capex");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue(label);
            header.createCell(1).setCellValue("Amount");
            sheet.createRow(1).createCell(0).setCellValue("Piling");
            sheet.getRow(1).createCell(1).setCellValue(50.0);
            return writeWorkbook(workbook, name);
        }
    }

    private static long pendingMappingReviewId(Path db) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT review_queue_id FROM review_queue WHERE category = 'cost_head_mapping' AND status = 'Pending'")) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }
}
