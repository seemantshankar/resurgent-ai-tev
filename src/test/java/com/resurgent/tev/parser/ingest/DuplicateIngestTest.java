package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resurgent.tev.parser.Main;
import com.resurgent.tev.parser.config.ParserConfig;
import com.resurgent.tev.parser.review.ReviewService;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ingest-seam tests for duplicate proposals and multi-region composition.
 */
class DuplicateIngestTest {

    @TempDir
    Path tempDir;

    @Test
    void exactCopyOnTwoSheets_proposesExactRowHashWithoutDeletingSources() throws Exception {
        Path db = ingest(identicalCivilSheets("Assets", "Details", 100.0, 50.0), "exact.xlsx");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            assertThat(scalar(c, "SELECT COUNT(*) FROM region")).isEqualTo(2);
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT method, score, reasons FROM duplicate_proposal")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("method")).isEqualTo("exact_row_hash");
                assertThat(rs.getDouble("score")).isEqualTo(1.0);
                assertThat(rs.getString("reasons")).contains("EXACT_CONTENT_HASH");
                assertThat(rs.next()).isFalse();
            }
            assertThat(scalar(c, "SELECT COUNT(*) FROM review_queue WHERE category = 'duplicate'"))
                    .isEqualTo(1);
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT amount, reasons FROM cost_head_candidate")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getDouble("amount")).isEqualTo(150.0);
                assertThat(rs.getString("reasons")).contains("UNRESOLVED_DUPLICATE");
            }
            assertThat(scalar(c, "SELECT COUNT(*) FROM cost_head_contribution")).isEqualTo(2);
            assertThat(scalar(c, "SELECT COUNT(*) FROM review_queue WHERE category = 'cost_head_candidate'"))
                    .isEqualTo(1);
        }
    }

    @Test
    void similarShiftedCopy_proposesShiftedBlockSignature() throws Exception {
        Path db;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            writeCivilBlock(workbook.createSheet("Assets"), 0, "Civil works", "Foundation", "Finishes",
                    100.0, 50.0, true);
            writeCivilBlock(workbook.createSheet("Details"), 8, "Civil works", "Foundation work",
                    "Finishes work", 100.0, 50.0, true);
            db = ingest(workbook, "fuzzy.xlsx");
        }

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT method, score, reasons FROM duplicate_proposal")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("method")).isEqualTo("shifted_block_signature");
            assertThat(rs.getDouble("score")).isGreaterThan(0.5).isLessThan(1.0);
            assertThat(rs.getString("reasons")).contains("SHIFTED_BLOCK_SIGNATURE");
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    void incompatibleCostHeadOrCurrency_doesNotPropose() throws Exception {
        Path differentHead;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            writeCivilBlock(workbook.createSheet("Civil"), 0, "Civil works", "Foundation", "Finishes",
                    100.0, 50.0, true);
            writeCivilBlock(workbook.createSheet("Plant"), 0, "Plant & machinery", "Foundation", "Finishes",
                    100.0, 50.0, true);
            differentHead = ingest(workbook, "incompatible-head.xlsx");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + differentHead)) {
            assertThat(scalar(c, "SELECT COUNT(*) FROM duplicate_proposal")).isZero();
        }

        Path differentCurrency;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            writeCivilBlock(workbook.createSheet("Inr"), 0, "Civil works", "Foundation", "Finishes",
                    100.0, 50.0, true, "Amount (Rs.)");
            writeCivilBlock(workbook.createSheet("Usd"), 0, "Civil works", "Foundation", "Finishes",
                    100.0, 50.0, true, "Amount (USD)");
            differentCurrency = ingest(workbook, "incompatible-fx.xlsx");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + differentCurrency)) {
            assertThat(scalar(c, "SELECT COUNT(*) FROM duplicate_proposal")).isZero();
        }

        Path differentUnit;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            writeCivilBlock(workbook.createSheet("Rupees"), 0, "Civil works", "Foundation", "Finishes",
                    100.0, 50.0, true, "Amount (Rs.)");
            writeCivilBlock(workbook.createSheet("Sqm"), 0, "Civil works", "Foundation", "Finishes",
                    100.0, 50.0, true, "Amount (sqm)");
            differentUnit = ingest(workbook, "incompatible-unit.xlsx");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + differentUnit)) {
            assertThat(scalar(c, "SELECT COUNT(*) FROM duplicate_proposal")).isZero();
        }
    }

    @Test
    void disjointCivilTables_areAdded() throws Exception {
        Path db;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            writeCivilBlock(workbook.createSheet("Buildings"), 0, "Civil works", "Foundation", "Finishes",
                    100.0, 50.0, true);
            writeCivilBlock(workbook.createSheet("Site"), 0, "Civil works", "Piling", "Earthwork",
                    80.0, 20.0, true);
            db = ingest(workbook, "disjoint.xlsx");
        }

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            assertThat(scalar(c, "SELECT COUNT(*) FROM duplicate_proposal")).isZero();
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT amount, reasons FROM cost_head_candidate")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getDouble("amount")).isEqualTo(250.0);
                assertThat(rs.getString("reasons")).doesNotContain("UNRESOLVED_DUPLICATE");
            }
            assertThat(scalar(c, "SELECT COUNT(*) FROM cost_head_contribution")).isEqualTo(2);
        }
    }

    @Test
    void formulaTotalOnAnotherSheet_composesByReachableLeavesNotBoundingBoxes() throws Exception {
        Path db;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            writeCivilBlock(workbook.createSheet("Assets"), 0, "Civil works", "Foundation", "Finishes",
                    100.0, 50.0, true);
            Sheet summary = workbook.createSheet("Summary");
            Row header = summary.createRow(0);
            header.createCell(0).setCellValue("Civil works");
            header.createCell(1).setCellValue("Amount");
            summary.createRow(1).createCell(0).setCellValue("Total");
            summary.getRow(1).createCell(1).setCellFormula("SUM(Assets!B2:B3)");
            db = ingest(workbook, "formula-containment.xlsx");
        }

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            assertThat(scalar(c, "SELECT COUNT(*) FROM region")).isEqualTo(2);
            assertThat(scalar(c, "SELECT COUNT(*) FROM duplicate_proposal")).isZero();
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT amount, reasons FROM cost_head_candidate")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getDouble("amount")).isEqualTo(150.0);
                assertThat(rs.getString("reasons")).doesNotContain("UNRESOLVED_DUPLICATE");
            }
        }
    }

    @Test
    void unresolvedDuplicate_blocksOnlyTheIntersectingCandidate() throws Exception {
        Path db;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            writeCivilBlock(workbook.createSheet("Assets"), 0, "Civil works", "Foundation", "Finishes",
                    100.0, 50.0, true);
            writeCivilBlock(workbook.createSheet("Details"), 0, "Civil works", "Foundation", "Finishes",
                    100.0, 50.0, true);
            writeCivilBlock(workbook.createSheet("Plant"), 0, "Plant & machinery", "Boiler", "Turbine",
                    200.0, 50.0, true);
            db = ingest(workbook, "intersect.xlsx");
        }

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT h.code, cand.reasons FROM cost_head_candidate cand"
                            + " JOIN cost_head h ON h.cost_head_id = cand.cost_head_id"
                            + " ORDER BY h.code")) {
                List<String> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(rs.getString("code") + ":" + rs.getString("reasons"));
                }
                assertThat(rows).anyMatch(row -> row.startsWith("CIVIL:") && row.contains("UNRESOLVED_DUPLICATE"));
                assertThat(rows).anyMatch(row -> row.startsWith("PLANT_MACHINERY:")
                        && !row.contains("UNRESOLVED_DUPLICATE"));
            }
        }
    }

    @Test
    void distinctDecision_allowsBothContributionsOnReingest() throws Exception {
        Path file;
        try (XSSFWorkbook workbook = identicalCivilSheets("Assets", "Details", 100.0, 50.0)) {
            file = writeWorkbook(workbook, "distinct.xlsx");
        }
        Path db = tempDir.resolve("distinct.db");
        new IngestService().ingest(file, 1L, db);
        ReviewService review = new ReviewService();
        ReviewService.DuplicateReviewItem pending = review.listPendingDuplicates(db).getFirst();
        review.markDistinct(db, pending.reviewQueueId(), "analyst", "two live site copies");

        new IngestService().ingest(file, 1L, db, reparseConfig());

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            assertThat(scalar(c, "SELECT COUNT(*) FROM duplicate_decision")).isEqualTo(1);
            assertThat(scalar(c, "SELECT COUNT(*) FROM review_queue WHERE category = 'duplicate'"
                    + " AND status = 'Pending'")).isZero();
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT amount, reasons FROM cost_head_candidate"
                            + " ORDER BY cost_head_candidate_id DESC LIMIT 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getDouble("amount")).isEqualTo(300.0);
                assertThat(rs.getString("reasons")).doesNotContain("UNRESOLVED_DUPLICATE");
            }
            assertThat(scalar(c, "SELECT COUNT(*) FROM cost_head_candidate")).isEqualTo(2);
            assertThat(scalar(c, "SELECT COUNT(*) FROM audit_log WHERE event_type = 'duplicate_distinct'"))
                    .isEqualTo(1);
        }
    }

    @Test
    void duplicateDecision_supersedesOneContributionOnReingest() throws Exception {
        Path file;
        try (XSSFWorkbook workbook = identicalCivilSheets("Assets", "Details", 100.0, 50.0)) {
            file = writeWorkbook(workbook, "dup-dec.xlsx");
        }
        Path db = tempDir.resolve("dup-dec.db");
        new IngestService().ingest(file, 1L, db);
        ReviewService review = new ReviewService();
        ReviewService.DuplicateReviewItem pending = review.listPendingDuplicates(db).getFirst();
        String superseded = pending.rightRegionKey();
        review.markDuplicate(db, pending.reviewQueueId(), "analyst", "details is a copy", superseded);

        new IngestService().ingest(file, 1L, db, reparseConfig());

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT decision, superseded_region_key, actor FROM duplicate_decision"
                            + " ORDER BY duplicate_decision_id DESC LIMIT 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("decision")).isEqualTo("Duplicate");
                assertThat(rs.getString("superseded_region_key")).isEqualTo(superseded);
                assertThat(rs.getString("actor")).isEqualTo("analyst");
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT amount, reasons FROM cost_head_candidate"
                            + " ORDER BY cost_head_candidate_id DESC LIMIT 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getDouble("amount")).isEqualTo(150.0);
                assertThat(rs.getString("reasons")).doesNotContain("UNRESOLVED_DUPLICATE");
            }
            assertThat(scalar(c, "SELECT COUNT(*) FROM cost_head_contribution"
                    + " WHERE cost_head_candidate_id ="
                    + " (SELECT MAX(cost_head_candidate_id) FROM cost_head_candidate)"))
                    .isEqualTo(1);
            assertThat(scalar(c, "SELECT COUNT(*) FROM audit_log WHERE event_type = 'duplicate_duplicate'"))
                    .isEqualTo(1);
        }
    }

    @Test
    void reviewCli_listsAndResolvesDuplicates() throws Exception {
        Path db = ingest(identicalCivilSheets("Assets", "Details", 100.0, 50.0), "cli-dup.xlsx");
        long reviewId = new ReviewService().listPendingDuplicates(db).getFirst().reviewQueueId();
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        int list = Main.commandLine().setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute("review", "list-duplicates", "--db", db.toString());
        assertThat(list).isZero();
        assertThat(out.toString()).contains(String.valueOf(reviewId));

        int show = Main.commandLine().setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute("review", "show-duplicate", "--db", db.toString(), String.valueOf(reviewId));
        assertThat(show).isZero();

        int distinct = Main.commandLine().setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute("review", "mark-distinct", "--db", db.toString(), "--actor", "analyst",
                        "--reason", "two live copies", String.valueOf(reviewId));
        assertThat(distinct).as(err.toString()).isZero();
        assertThat(out.toString()).contains("Distinct");
    }

    @Test
    void markDuplicate_rejectsSupersedeKeyOutsideReviewedPair() throws Exception {
        Path db = ingest(identicalCivilSheets("Assets", "Details", 100.0, 50.0), "bad-supersede.xlsx");
        ReviewService review = new ReviewService();
        ReviewService.DuplicateReviewItem pending = review.listPendingDuplicates(db).getFirst();
        assertThatThrownBy(() -> review.markDuplicate(
                        db, pending.reviewQueueId(), "analyst", "copy", "not-a-region-key"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("superseded region key");
        assertThat(review.listPendingDuplicates(db)).isNotEmpty();
    }

    @Test
    void reviewCli_marksDuplicateWithSupersede() throws Exception {
        Path db = ingest(identicalCivilSheets("Assets", "Details", 100.0, 50.0), "cli-mark-dup.xlsx");
        ReviewService.DuplicateReviewItem pending = new ReviewService().listPendingDuplicates(db).getFirst();
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int marked = Main.commandLine().setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute("review", "mark-duplicate", "--db", db.toString(), "--actor", "analyst",
                        "--reason", "details is a copy", "--supersede", pending.rightRegionKey(),
                        String.valueOf(pending.reviewQueueId()));
        assertThat(marked).as(err.toString()).isZero();
        assertThat(out.toString()).contains("Duplicate");
    }

    private XSSFWorkbook identicalCivilSheets(String left, String right, double a, double b) {
        XSSFWorkbook workbook = new XSSFWorkbook();
        writeCivilBlock(workbook.createSheet(left), 0, "Civil works", "Foundation", "Finishes", a, b, true);
        writeCivilBlock(workbook.createSheet(right), 0, "Civil works", "Foundation", "Finishes", a, b, true);
        return workbook;
    }

    private static void writeCivilBlock(Sheet sheet, int startRow, String label, String item1, String item2,
            double amount1, double amount2, boolean formulaTotal) {
        writeCivilBlock(sheet, startRow, label, item1, item2, amount1, amount2, formulaTotal, "Amount");
    }

    private static void writeCivilBlock(Sheet sheet, int startRow, String label, String item1, String item2,
            double amount1, double amount2, boolean formulaTotal, String amountHeader) {
        Row header = sheet.createRow(startRow);
        header.createCell(0).setCellValue(label);
        header.createCell(1).setCellValue(amountHeader);
        sheet.createRow(startRow + 1).createCell(0).setCellValue(item1);
        sheet.getRow(startRow + 1).createCell(1).setCellValue(amount1);
        sheet.createRow(startRow + 2).createCell(0).setCellValue(item2);
        sheet.getRow(startRow + 2).createCell(1).setCellValue(amount2);
        sheet.createRow(startRow + 3).createCell(0).setCellValue("Total");
        if (formulaTotal) {
            int first = startRow + 2;
            int last = startRow + 3;
            sheet.getRow(startRow + 3).createCell(1)
                    .setCellFormula("SUM(B" + first + ":B" + last + ")");
        } else {
            sheet.getRow(startRow + 3).createCell(1).setCellValue(amount1 + amount2);
        }
    }

    private Path ingest(XSSFWorkbook workbook, String name) throws Exception {
        Path db = tempDir.resolve(name.replace(".xlsx", ".db"));
        try (workbook) {
            new IngestService().ingest(writeWorkbook(workbook, name), 1L, db);
        }
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

    private static ParserConfig reparseConfig() {
        return new ParserConfig(
                100L * 1024 * 1024, 200, 1_000_000, 16_384, 5_000_000L, 100,
                false, true, true, 4);
    }

    private static long scalar(Connection c, String sql) throws Exception {
        try (ResultSet rs = c.createStatement().executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }
}
