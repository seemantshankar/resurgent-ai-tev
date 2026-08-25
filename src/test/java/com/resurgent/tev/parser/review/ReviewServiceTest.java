package com.resurgent.tev.parser.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.Main;
import com.resurgent.tev.parser.config.ParserConfig;
import com.resurgent.tev.parser.ingest.IngestService;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void listsPendingTotalsIndependentlyFromMappings() throws Exception {
        Path db = ingestLiteralCivil("list-totals.xlsx");
        ReviewService review = new ReviewService();

        List<ReviewService.TotalReviewItem> totals = review.listPendingTotals(db);
        assertThat(totals).isNotEmpty();
        ReviewService.TotalReviewItem total = totals.getFirst();
        assertThat(total.costHeadCode()).isEqualTo("CIVIL");
        assertThat(total.fingerprint()).isNotBlank();
        assertThat(total.candidateId()).isPositive();
        assertThat(total.detail()).contains("\"amount\"").contains("\"bases\"").contains("\"unit\"");
        assertThat(review.showTotal(db, total.reviewQueueId())).contains(total);

        assertThat(review.listPendingMappings(db)).isEmpty();
    }

    @Test
    void acceptTotal_insertsDecisionWithoutMutatingCandidate() throws Exception {
        Path db = ingestLiteralCivil("accept-total.xlsx");
        ReviewService review = new ReviewService();
        ReviewService.TotalReviewItem pending = review.listPendingTotals(db).getFirst();
        String fingerprint = pending.fingerprint();
        CandidateSnapshot before = candidateSnapshot(db, pending.candidateId());

        review.acceptTotal(db, pending.reviewQueueId(), "analyst", "agrees with source total");

        assertThat(review.listPendingTotals(db)).isEmpty();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT source_file_id, cost_head_code, candidate_fingerprint, decision, actor, reason, decided_at"
                            + " FROM cost_head_total_decision")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("source_file_id")).isPositive();
                assertThat(rs.getString("cost_head_code")).isEqualTo("CIVIL");
                assertThat(rs.getString("candidate_fingerprint")).isEqualTo(fingerprint);
                assertThat(rs.getString("decision")).isEqualTo("Accepted");
                assertThat(rs.getString("actor")).isEqualTo("analyst");
                assertThat(rs.getString("reason")).isEqualTo("agrees with source total");
                assertThat(rs.getString("decided_at")).isNotBlank();
                assertThat(rs.next()).isFalse();
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM audit_log WHERE event_type = 'total_accepted'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
        assertThat(candidateSnapshot(db, pending.candidateId())).isEqualTo(before);
    }

    @Test
    void rejectTotal_recordsDecisionAndLeavesCandidateUnchanged() throws Exception {
        Path db = ingestLiteralCivil("reject-total.xlsx");
        ReviewService review = new ReviewService();
        ReviewService.TotalReviewItem pending = review.listPendingTotals(db).getFirst();
        CandidateSnapshot before = candidateSnapshot(db, pending.candidateId());

        review.rejectTotal(db, pending.reviewQueueId(), "analyst", "leaf sum only");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT decision, actor, reason FROM cost_head_total_decision")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("decision")).isEqualTo("Rejected");
                assertThat(rs.getString("actor")).isEqualTo("analyst");
                assertThat(rs.getString("reason")).isEqualTo("leaf sum only");
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM audit_log WHERE event_type = 'total_rejected'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
        assertThat(candidateSnapshot(db, pending.candidateId())).isEqualTo(before);
        assertThat(review.listPendingTotals(db)).isEmpty();
    }

    @Test
    void mappingAndTotalDecisions_areIndependent() throws Exception {
        Path db = tempDir.resolve("independent.db");
        Path xlsx = writeLiteralCivil("independent.xlsx", "civil wrks", 100.0, 50.0);
        new IngestService().ingest(xlsx, 1L, db);
        ReviewService review = new ReviewService();
        ReviewService.MappingReviewItem mapping = review.listPendingMappings(db).getFirst();
        ReviewService.TotalReviewItem total = review.listPendingTotals(db).getFirst();

        review.acceptMapping(db, mapping.reviewQueueId(), "analyst", "label is civil");

        assertThat(review.listPendingTotals(db)).extracting(ReviewService.TotalReviewItem::reviewQueueId)
                .contains(total.reviewQueueId());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT COUNT(*) FROM cost_head_total_decision")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isZero();
        }

        review.acceptTotal(db, total.reviewQueueId(), "analyst", "amount is right");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM cost_head_mapping_decision WHERE decision = 'Accepted'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM cost_head_total_decision WHERE decision = 'Accepted'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void reingestIdenticalFingerprint_carriesTotalAcceptance() throws Exception {
        Path db = tempDir.resolve("carry-total.db");
        Path xlsx = writeLiteralCivil("carry-total.xlsx", "Civil works", 100.0, 50.0);
        new IngestService().ingest(xlsx, 1L, db);
        ReviewService review = new ReviewService();
        ReviewService.TotalReviewItem pending = review.listPendingTotals(db).getFirst();
        String fingerprint = pending.fingerprint();
        review.acceptTotal(db, pending.reviewQueueId(), "analyst", "ok");

        new IngestService().ingest(xlsx, 1L, db, reparseConfig());

        assertThat(review.listPendingTotals(db)).isEmpty();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT candidate_fingerprint FROM cost_head_candidate"
                            + " ORDER BY cost_head_candidate_id DESC LIMIT 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo(fingerprint);
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT status, carried_from_decision_id FROM review_queue"
                            + " WHERE category = 'cost_head_candidate'"
                            + " ORDER BY review_queue_id DESC LIMIT 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("Accepted");
                assertThat(rs.getLong("carried_from_decision_id")).isPositive();
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM cost_head_total_decision")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT automatic_trust_eligible FROM cost_head_candidate"
                            + " ORDER BY cost_head_candidate_id DESC LIMIT 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT metrics FROM parse_run ORDER BY parse_run_id DESC LIMIT 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).contains("\"state\":\"trusted\"");
                assertThat(rs.getString(1)).contains("\"source\":\"analyst\"");
            }
        }
    }

    @Test
    void changedAmount_reopensPendingAndDoesNotCarryAcceptance() throws Exception {
        Path db = tempDir.resolve("stale-total.db");
        Path first = writeLiteralCivil("stale-first.xlsx", "Civil works", 100.0, 50.0);
        new IngestService().ingest(first, 1L, db);
        ReviewService review = new ReviewService();
        ReviewService.TotalReviewItem pending = review.listPendingTotals(db).getFirst();
        String originalFingerprint = pending.fingerprint();
        review.acceptTotal(db, pending.reviewQueueId(), "analyst", "ok");

        Path second = writeLiteralCivil("stale-second.xlsx", "Civil works", 120.0, 50.0);
        new IngestService().ingest(second, 1L, db);

        List<ReviewService.TotalReviewItem> reopened = review.listPendingTotals(db);
        assertThat(reopened).isNotEmpty();
        assertThat(reopened.getFirst().fingerprint()).isNotEqualTo(originalFingerprint);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT status, carried_from_decision_id FROM review_queue"
                                + " WHERE category = 'cost_head_candidate'"
                                + " ORDER BY review_queue_id DESC LIMIT 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("status")).isEqualTo("Pending");
            assertThat(rs.getObject("carried_from_decision_id")).isNull();
        }
    }

    @Test
    void acceptedManualAfterTotalAcceptance_projectsStaleOnReingest() throws Exception {
        Path db = tempDir.resolve("stale-manual.db");
        Path xlsx = writeLiteralCivil("stale-manual.xlsx", "Civil works", 100.0, 50.0);
        new IngestService().ingest(xlsx, 1L, db);
        ReviewService review = new ReviewService();
        ReviewService.TotalReviewItem pending = review.listPendingTotals(db).getFirst();
        review.acceptTotal(db, pending.reviewQueueId(), "analyst", "ok");
        long manualId = review.addManual(db, "CIVIL", new BigDecimal("25.00"), "rs", "INR",
                "analyst", "contingency", contributionId(db));
        review.acceptManual(db, manualId, "analyst", "include contingency");

        new IngestService().ingest(xlsx, 1L, db, reparseConfig());

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT metrics FROM parse_run ORDER BY parse_run_id DESC LIMIT 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).contains("\"state\":\"stale\"");
            assertThat(rs.getString(1)).contains("\"source\":\"analyst\"");
        }
        assertThat(review.listPendingTotals(db)).isNotEmpty();
    }

    @Test
    void addManual_recordsDraftWithoutChangingFingerprint() throws Exception {
        Path db = tempDir.resolve("manual-draft.db");
        Path xlsx = writeLiteralCivil("manual-draft.xlsx", "Civil works", 100.0, 50.0);
        new IngestService().ingest(xlsx, 1L, db);
        ReviewService review = new ReviewService();
        String before = review.listPendingTotals(db).getFirst().fingerprint();
        long contributionId = contributionId(db);

        long manualId = review.addManual(db, "CIVIL", new BigDecimal("25.00"), "rs", "INR",
                "analyst", "missing contingency", contributionId);

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT amount, unit, currency, actor, reason, status, adjusts_contribution_id"
                            + " FROM manual_contribution WHERE manual_contribution_id = " + manualId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBigDecimal("amount")).isEqualByComparingTo("25.00");
                assertThat(rs.getString("unit")).isEqualTo("rs");
                assertThat(rs.getString("currency")).isEqualTo("INR");
                assertThat(rs.getString("actor")).isEqualTo("analyst");
                assertThat(rs.getString("reason")).isEqualTo("missing contingency");
                assertThat(rs.getString("status")).isEqualTo("Pending");
                assertThat(rs.getLong("adjusts_contribution_id")).isEqualTo(contributionId);
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM provenance WHERE entity_type = 'manual_contribution'"
                            + " AND entity_id = " + manualId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM audit_log WHERE event_type = 'manual_added'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }

        new IngestService().ingest(xlsx, 1L, db, reparseConfig());
        assertThat(review.listPendingTotals(db).getFirst().fingerprint()).isEqualTo(before);
    }

    @Test
    void acceptManual_changesFingerprintAndInvalidatesPriorTotalAcceptance() throws Exception {
        Path db = tempDir.resolve("manual-accept.db");
        Path xlsx = writeLiteralCivil("manual-accept.xlsx", "Civil works", 100.0, 50.0);
        new IngestService().ingest(xlsx, 1L, db);
        ReviewService review = new ReviewService();
        ReviewService.TotalReviewItem pending = review.listPendingTotals(db).getFirst();
        String originalFingerprint = pending.fingerprint();
        review.acceptTotal(db, pending.reviewQueueId(), "analyst", "ok");
        long manualId = review.addManual(db, "CIVIL", new BigDecimal("25.00"), "rs", "INR",
                "analyst", "missing contingency", contributionId(db));
        CandidateSnapshot calculated = candidateSnapshot(db, pending.candidateId());

        review.acceptManual(db, manualId, "analyst", "missing contingency");

        assertThat(candidateSnapshot(db, pending.candidateId())).isEqualTo(calculated);
        assertThat(review.listPendingTotals(db)).isNotEmpty();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT status, decided_at FROM manual_contribution WHERE manual_contribution_id = "
                            + manualId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("Accepted");
                assertThat(rs.getString("decided_at")).isNotBlank();
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT payload FROM audit_log WHERE event_type = 'manual_accepted'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).contains("missing contingency");
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT decision FROM cost_head_total_decision")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("Accepted");
                assertThat(rs.next()).isFalse();
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT status, carried_from_decision_id FROM review_queue"
                            + " WHERE category = 'cost_head_candidate'"
                            + " ORDER BY review_queue_id DESC LIMIT 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("Pending");
                assertThat(rs.getObject("carried_from_decision_id")).isNull();
            }
        }

        new IngestService().ingest(xlsx, 1L, db, reparseConfig());
        List<ReviewService.TotalReviewItem> reopened = review.listPendingTotals(db);
        assertThat(reopened).isNotEmpty();
        assertThat(reopened.getFirst().fingerprint()).isNotEqualTo(originalFingerprint);
        assertThat(reopened.getFirst().detail()).contains("manual");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT amount FROM cost_head_candidate ORDER BY cost_head_candidate_id DESC LIMIT 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("175");
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM cost_head_contribution WHERE basis = 'manual'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void changeManual_updatesAcceptedAmountAndFingerprint() throws Exception {
        Path db = tempDir.resolve("manual-change.db");
        Path xlsx = writeLiteralCivil("manual-change.xlsx", "Civil works", 100.0, 50.0);
        new IngestService().ingest(xlsx, 1L, db);
        ReviewService review = new ReviewService();
        long manualId = review.addManual(db, "CIVIL", new BigDecimal("25.00"), "rs", "INR",
                "analyst", "missing contingency", null);
        review.acceptManual(db, manualId, "analyst", "include contingency");
        new IngestService().ingest(xlsx, 1L, db, reparseConfig());
        String withTwentyFive = review.listPendingTotals(db).getFirst().fingerprint();

        review.changeManual(db, manualId, new BigDecimal("40.00"), "rs", "INR", "analyst",
                "revised contingency");
        ParserConfig third = new ParserConfig(
                100L * 1024 * 1024, 200, 1_000_000, 16_384, 5_000_000L, 100,
                false, true, true, 5);
        new IngestService().ingest(xlsx, 1L, db, third);

        List<ReviewService.TotalReviewItem> changed = review.listPendingTotals(db);
        assertThat(changed).isNotEmpty();
        assertThat(changed.getFirst().fingerprint()).isNotEqualTo(withTwentyFive);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT amount FROM cost_head_candidate ORDER BY cost_head_candidate_id DESC LIMIT 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("190");
        }
    }

    @Test
    void withdrawAcceptedManual_changesFingerprintAgain() throws Exception {
        Path db = tempDir.resolve("manual-withdraw.db");
        Path xlsx = writeLiteralCivil("manual-withdraw.xlsx", "Civil works", 100.0, 50.0);
        new IngestService().ingest(xlsx, 1L, db);
        ReviewService review = new ReviewService();
        long manualId = review.addManual(db, "CIVIL", new BigDecimal("25.00"), "rs", "INR",
                "analyst", "missing contingency", null);
        review.acceptManual(db, manualId, "analyst", "include contingency");
        new IngestService().ingest(xlsx, 1L, db, reparseConfig());
        String withManual = review.listPendingTotals(db).getFirst().fingerprint();
        review.acceptTotal(db, review.listPendingTotals(db).getFirst().reviewQueueId(), "analyst", "ok");

        review.withdrawManual(db, manualId, "analyst", "no longer needed");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT status FROM manual_contribution WHERE manual_contribution_id = " + manualId)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("Withdrawn");
        }
        ParserConfig third = new ParserConfig(
                100L * 1024 * 1024, 200, 1_000_000, 16_384, 5_000_000L, 100,
                false, true, true, 5);
        new IngestService().ingest(xlsx, 1L, db, third);
        List<ReviewService.TotalReviewItem> afterWithdraw = review.listPendingTotals(db);
        assertThat(afterWithdraw).isNotEmpty();
        assertThat(afterWithdraw.getFirst().fingerprint()).isNotEqualTo(withManual);
    }

    @Test
    void withdrawPendingManual_doesNotReopenAcceptedTotal() throws Exception {
        Path db = ingestLiteralCivil("pending-withdraw.xlsx");
        ReviewService review = new ReviewService();
        ReviewService.TotalReviewItem pending = review.listPendingTotals(db).getFirst();
        review.acceptTotal(db, pending.reviewQueueId(), "analyst", "ok");
        long manualId = review.addManual(db, "CIVIL", new BigDecimal("25.00"), "rs", "INR",
                "analyst", "maybe later", null);

        review.withdrawManual(db, manualId, "analyst", "not needed");

        assertThat(review.listPendingTotals(db)).isEmpty();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT status FROM review_queue WHERE category = 'cost_head_candidate'"
                                + " ORDER BY review_queue_id DESC LIMIT 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("Accepted");
        }
    }

    @Test
    void acceptedLakhManual_isNormalizedIntoCandidateTotal() throws Exception {
        Path db = tempDir.resolve("manual-lakh.db");
        Path xlsx = writeLiteralCivil("manual-lakh.xlsx", "Civil works", 100.0, 50.0);
        new IngestService().ingest(xlsx, 1L, db);
        ReviewService review = new ReviewService();
        long manualId = review.addManual(db, "CIVIL", BigDecimal.ONE, "lakh", "INR",
                "analyst", "missing package", null);
        review.acceptManual(db, manualId, "analyst", "include package");
        new IngestService().ingest(xlsx, 1L, db, reparseConfig());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT amount FROM cost_head_candidate ORDER BY cost_head_candidate_id DESC LIMIT 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("100150");
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT source_amount, normalized_amount, normalized_unit FROM cost_head_contribution"
                            + " WHERE basis = 'manual'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBigDecimal("source_amount")).isEqualByComparingTo("1");
                assertThat(rs.getBigDecimal("normalized_amount")).isEqualByComparingTo("100000");
                assertThat(rs.getString("normalized_unit")).isEqualTo("rs");
            }
        }
    }

    @Test
    void latestAcceptedTotalDecision_followsRowIdWhenTimestampsSortWrong() throws Exception {
        Path db = ingestLiteralCivil("decision-order.xlsx");
        Path xlsx = tempDir.resolve("decision-order.xlsx");
        ReviewService.TotalReviewItem pending = new ReviewService().listPendingTotals(db).getFirst();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                java.sql.PreparedStatement lookup = c.prepareStatement(
                        "SELECT source_file_id, candidate_fingerprint FROM cost_head_candidate"
                                + " WHERE cost_head_candidate_id = ?")) {
            lookup.setLong(1, pending.candidateId());
            try (ResultSet rs = lookup.executeQuery()) {
                assertThat(rs.next()).isTrue();
                long sourceFileId = rs.getLong(1);
                String fingerprint = rs.getString(2);
                try (java.sql.PreparedStatement insert = c.prepareStatement(
                        "INSERT INTO cost_head_total_decision (source_file_id, cost_head_code,"
                                + " candidate_fingerprint, decision, actor, reason, decided_at)"
                                + " VALUES (?, 'CIVIL', ?, ?, 'analyst', 'order', ?)")) {
                    insert.setLong(1, sourceFileId);
                    insert.setString(2, fingerprint);
                    insert.setString(3, "Rejected");
                    insert.setString(4, "2026-01-01T10:00:00Z");
                    insert.executeUpdate();
                    insert.setString(3, "Accepted");
                    insert.setString(4, "2026-01-01T10:00:00.500Z");
                    insert.executeUpdate();
                }
            }
        }
        new IngestService().ingest(xlsx, 1L, db, reparseConfig());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT status, carried_from_decision_id FROM review_queue"
                                + " WHERE category = 'cost_head_candidate'"
                                + " ORDER BY review_queue_id DESC LIMIT 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("status")).isEqualTo("Accepted");
            assertThat(rs.getLong("carried_from_decision_id")).isPositive();
        }
    }

    @Test
    void rejectThenAccept_preservesImmutableDecisionHistory() throws Exception {
        Path db = ingestLiteralCivil("history.xlsx");
        ReviewService review = new ReviewService();
        ReviewService.TotalReviewItem pending = review.listPendingTotals(db).getFirst();
        review.rejectTotal(db, pending.reviewQueueId(), "analyst", "not yet");
        new IngestService().ingest(
                tempDir.resolve("history.xlsx"), 1L, db, reparseConfig());
        ReviewService.TotalReviewItem again = review.listPendingTotals(db).getFirst();
        review.acceptTotal(db, again.reviewQueueId(), "analyst", "now ok");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT total_decision_id, decision, supersedes_id FROM cost_head_total_decision"
                                + " ORDER BY total_decision_id")) {
            assertThat(rs.next()).isTrue();
            long first = rs.getLong("total_decision_id");
            assertThat(rs.getString("decision")).isEqualTo("Rejected");
            assertThat(rs.getObject("supersedes_id")).isNull();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("decision")).isEqualTo("Accepted");
            assertThat(rs.getLong("supersedes_id")).isEqualTo(first);
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    void reviewCli_totalAndManualActions() throws Exception {
        Path db = ingestLiteralCivil("cli-totals.xlsx");
        long reviewId = new ReviewService().listPendingTotals(db).getFirst().reviewQueueId();
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        int list = Main.commandLine().setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute("review", "list-totals", "--db", db.toString());
        assertThat(list).isZero();
        assertThat(out.toString()).contains(String.valueOf(reviewId));

        int show = Main.commandLine().setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute("review", "show-total", "--db", db.toString(), String.valueOf(reviewId));
        assertThat(show).isZero();

        int accept = Main.commandLine().setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute("review", "accept-total", "--db", db.toString(), "--actor", "analyst",
                        "--reason", "ok", String.valueOf(reviewId));
        assertThat(accept).as(err.toString()).isZero();
        assertThat(out.toString()).contains("Accepted");

        int missing = Main.commandLine().setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute("review", "show-total", "--db", db.toString(), String.valueOf(reviewId));
        assertThat(missing).isEqualTo(1);

        out.getBuffer().setLength(0);
        int add = Main.commandLine().setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute("review", "add-manual", "--db", db.toString(), "--cost-head", "CIVIL",
                        "--amount", "25.00", "--unit", "rs", "--currency", "INR",
                        "--actor", "analyst", "--reason", "contingency");
        assertThat(add).as(err.toString()).isZero();
        String printedId = out.toString().trim().replaceAll("(?s).*?(\\d+)\\s*$", "$1");
        long manualId = Long.parseLong(printedId);

        int acceptManual = Main.commandLine().setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute("review", "accept-manual", "--db", db.toString(), "--actor", "analyst",
                        "--reason", "include contingency", String.valueOf(manualId));
        assertThat(acceptManual).as(err.toString()).isZero();

        int change = Main.commandLine().setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute("review", "change-manual", "--db", db.toString(), "--amount", "40.00",
                        "--unit", "rs", "--currency", "INR", "--actor", "analyst",
                        "--reason", "revised", String.valueOf(manualId));
        assertThat(change).as(err.toString()).isZero();
        assertThat(out.toString()).contains("Changed");

        int withdraw = Main.commandLine().setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute("review", "withdraw-manual", "--db", db.toString(), "--actor", "analyst",
                        "--reason", "reverted", String.valueOf(manualId));
        assertThat(withdraw).as(err.toString()).isZero();

        int rejectMissing = Main.commandLine().setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute("review", "reject-total", "--db", db.toString(), "--actor", "analyst",
                        String.valueOf(reviewId + 999));
        assertThat(rejectMissing).isEqualTo(1);
    }

    private Path ingestLiteralCivil(String name) throws Exception {
        Path db = tempDir.resolve(name.replace(".xlsx", ".db"));
        new IngestService().ingest(writeLiteralCivil(name, "Civil works", 100.0, 50.0), 1L, db);
        return db;
    }

    private static ParserConfig reparseConfig() {
        return new ParserConfig(
                100L * 1024 * 1024, 200, 1_000_000, 16_384, 5_000_000L, 100,
                false, true, true, 4);
    }

    private Path writeLiteralCivil(String name, String label, double foundation, double finishes)
            throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Capex");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue(label);
        header.createCell(1).setCellValue("Amount");
        sheet.createRow(1).createCell(0).setCellValue("Foundation");
        sheet.getRow(1).createCell(1).setCellValue(foundation);
        sheet.createRow(2).createCell(0).setCellValue("Finishes");
        sheet.getRow(2).createCell(1).setCellValue(finishes);
        sheet.createRow(3).createCell(0).setCellValue("Total");
        sheet.getRow(3).createCell(1).setCellValue(foundation + finishes);
        Path file = tempDir.resolve(name);
        try (FileOutputStream out = new FileOutputStream(file.toFile())) {
            workbook.write(out);
        }
        workbook.close();
        return file;
    }

    private record CandidateSnapshot(
            String fingerprint, String amount, String unit, String currency, String reasons) {}

    private static CandidateSnapshot candidateSnapshot(Path db, long candidateId) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT candidate_fingerprint, amount, unit, currency, reasons"
                                + " FROM cost_head_candidate WHERE cost_head_candidate_id = " + candidateId)) {
            assertThat(rs.next()).isTrue();
            return new CandidateSnapshot(
                    rs.getString("candidate_fingerprint"),
                    rs.getString("amount"),
                    rs.getString("unit"),
                    rs.getString("currency"),
                    rs.getString("reasons"));
        }
    }

    private static long contributionId(Path db) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT cost_head_contribution_id FROM cost_head_contribution")) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }
}
