package com.resurgent.tev.parser.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.resurgent.tev.parser.db.Jsonb;
import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Command-style review seam. The CLI is a thin adapter over this service.
 */
public final class ReviewService {

    public record MappingReviewItem(
            long reviewQueueId,
            String summary,
            String detail,
            String regionKey,
            String code,
            long mappingId) {}

    public record TotalReviewItem(
            long reviewQueueId,
            String summary,
            String detail,
            String costHeadCode,
            String fingerprint,
            long candidateId) {}

    public record DuplicateReviewItem(
            long reviewQueueId,
            String summary,
            String detail,
            String leftRegionKey,
            String rightRegionKey,
            String method,
            long proposalId) {}

    public List<MappingReviewItem> listPendingMappings(Path db) throws SQLException {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            return listPendingMappings(new WorkspaceRepository(workspace.connection()));
        }
    }

    public Optional<MappingReviewItem> show(Path db, long reviewId) throws SQLException {
        return listPendingMappings(db).stream()
                .filter(item -> item.reviewQueueId() == reviewId)
                .findFirst();
    }

    public List<TotalReviewItem> listPendingTotals(Path db) throws SQLException {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            return listPendingTotals(new WorkspaceRepository(workspace.connection()));
        }
    }

    public Optional<TotalReviewItem> showTotal(Path db, long reviewId) throws SQLException {
        return listPendingTotals(db).stream()
                .filter(item -> item.reviewQueueId() == reviewId)
                .findFirst();
    }

    public void acceptMapping(Path db, long reviewId, String actor, String reason)
            throws SQLException, JsonProcessingException {
        decide(db, reviewId, actor, reason == null ? "accepted" : reason, "Accepted");
    }

    public void rejectMapping(Path db, long reviewId, String actor, String reason)
            throws SQLException, JsonProcessingException {
        decide(db, reviewId, actor, reason == null ? "rejected" : reason, "Rejected");
    }

    public void acceptTotal(Path db, long reviewId, String actor, String reason)
            throws SQLException, JsonProcessingException {
        decideTotal(db, reviewId, actor, reason == null ? "accepted" : reason, "Accepted");
    }

    public void rejectTotal(Path db, long reviewId, String actor, String reason)
            throws SQLException, JsonProcessingException {
        decideTotal(db, reviewId, actor, reason == null ? "rejected" : reason, "Rejected");
    }

    public List<DuplicateReviewItem> listPendingDuplicates(Path db) throws SQLException {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            return listPendingDuplicates(new WorkspaceRepository(workspace.connection()));
        }
    }

    public Optional<DuplicateReviewItem> showDuplicate(Path db, long reviewId) throws SQLException {
        return listPendingDuplicates(db).stream()
                .filter(item -> item.reviewQueueId() == reviewId)
                .findFirst();
    }

    public void markDuplicate(Path db, long reviewId, String actor, String reason, String supersededRegionKey)
            throws SQLException, JsonProcessingException {
        decideDuplicate(db, reviewId, actor, requiredReason(reason), "Duplicate", supersededRegionKey);
    }

    public void markDistinct(Path db, long reviewId, String actor, String reason)
            throws SQLException, JsonProcessingException {
        decideDuplicate(db, reviewId, actor, requiredReason(reason), "Distinct", null);
    }

    public long addManual(Path db, String costHeadCode, BigDecimal amount, String unit, String currency,
            String actor, String reason, Long adjustsContributionId)
            throws SQLException, JsonProcessingException {
        return transact(db, repo -> {
            WorkspaceRepository.ParseContext parse = repo.findLatestParseContext();
            long costHeadId = repo.findCostHeadId(parse.mandateId(), costHeadCode);
            String regionKey = "";
            if (adjustsContributionId != null) {
                regionKey = repo.findContributionRegionKey(adjustsContributionId);
            }
            String now = Timestamps.now();
            long manualId = repo.insertManualContribution(parse.sourceFileId(), costHeadId,
                    adjustsContributionId, amount, currency, unit, reason, actor, "Pending", now);
            repo.insertProvenance("manual_contribution", manualId, parse.sourceFileId(),
                    parse.parseRunId(), regionKey, amount.toPlainString(), 1.0, true,
                    Jsonb.toJson(Map.of(
                            "costHeadCode", costHeadCode,
                            "reason", reason,
                            "adjustsContributionId",
                            adjustsContributionId == null ? "" : String.valueOf(adjustsContributionId),
                            "regionKey", regionKey)));
            repo.insertAuditLog(parse.parseRunId(), "manual_added", now, Jsonb.toJson(Map.of(
                    "manualId", manualId,
                    "costHeadCode", costHeadCode,
                    "actor", actor,
                    "reason", reason)), "info");
            return manualId;
        });
    }

    public void acceptManual(Path db, long manualId, String actor, String reason)
            throws SQLException, JsonProcessingException {
        updateManual(db, manualId, actor, requiredReason(reason), "Accepted", "Pending");
    }

    public void changeManual(Path db, long manualId, BigDecimal amount, String unit, String currency,
            String actor, String reason) throws SQLException, JsonProcessingException {
        transact(db, repo -> {
            WorkspaceRepository.ManualContributionRow manual = repo.findManualContribution(manualId);
            if (!"Pending".equals(manual.status()) && !"Accepted".equals(manual.status())) {
                throw new SQLException("manual contribution " + manualId + " is " + manual.status());
            }
            String now = Timestamps.now();
            repo.updateManualValues(manualId, amount, unit, currency);
            repo.insertAuditLog(repo.findLatestParseRunId(), "manual_changed",
                    now, Jsonb.toJson(Map.of(
                            "manualId", manualId,
                            "actor", actor,
                            "reason", requiredReason(reason),
                            "amount", amount.toPlainString(),
                            "unit", unit,
                            "currency", currency,
                            "status", manual.status())),
                    "info");
            if ("Accepted".equals(manual.status())) {
                reopenCostHeadReviews(repo, manual.costHeadId());
            }
            return null;
        });
    }

    public void withdrawManual(Path db, long manualId, String actor, String reason)
            throws SQLException, JsonProcessingException {
        updateManual(db, manualId, actor, requiredReason(reason), "Withdrawn",
                "Accepted", "Pending");
    }

    public void addCostHead(Path db, long mandateId, String code) throws SQLException {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            new WorkspaceRepository(workspace.connection()).ensureCostHead(mandateId, code);
        }
    }

    private void decide(Path db, long reviewId, String actor, String reason, String decision)
            throws SQLException, JsonProcessingException {
        transact(db, repo -> {
            MappingReviewItem item = listPendingMappings(repo).stream()
                    .filter(candidate -> candidate.reviewQueueId() == reviewId)
                    .findFirst()
                    .orElseThrow(() -> new SQLException("review item not found: " + reviewId));
            WorkspaceRepository.MappingIdentity identity = repo.findMappingIdentity(item.mappingId());
            repo.insertMappingDecision(identity.sourceFileId(), identity.regionKey(), item.code(),
                    identity.sourceLabel(), decision, actor, reason, Timestamps.now());
            repo.resolveReviewQueue(reviewId, decision);
            repo.insertAuditLog(repo.findLatestParseRunId(), "mapping_" + decision.toLowerCase(),
                    Timestamps.now(), Jsonb.toJson(Map.of(
                            "reviewId", reviewId,
                            "code", item.code(),
                            "actor", actor,
                            "sourceLabel", identity.sourceLabel() == null ? "" : identity.sourceLabel())),
                    "info");
            return null;
        });
    }

    private void updateManual(Path db, long manualId, String actor, String reason, String status,
            String... allowedCurrent) throws SQLException, JsonProcessingException {
        transact(db, repo -> {
            WorkspaceRepository.ManualContributionRow manual = repo.findManualContribution(manualId);
            boolean allowed = false;
            for (String current : allowedCurrent) {
                if (current.equals(manual.status())) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                throw new SQLException("manual contribution " + manualId + " is " + manual.status());
            }
            String now = Timestamps.now();
            repo.updateManualStatus(manualId, status, now);
            repo.insertAuditLog(repo.findLatestParseRunId(), "manual_" + status.toLowerCase(),
                    now, Jsonb.toJson(Map.of(
                            "manualId", manualId,
                            "actor", actor,
                            "reason", reason,
                            "status", status)),
                    "info");
            boolean reopen = "Accepted".equals(status)
                    || ("Withdrawn".equals(status) && "Accepted".equals(manual.status()));
            if (reopen) {
                reopenCostHeadReviews(repo, manual.costHeadId());
            }
            return null;
        });
    }

    private static void reopenCostHeadReviews(WorkspaceRepository repo, long costHeadId)
            throws SQLException {
        repo.reopenCostHeadCandidateReviews(
                repo.findLatestParseRunId(), repo.findCostHeadCode(costHeadId));
    }

    private static String requiredReason(String reason) throws SQLException {
        if (reason == null || reason.isBlank()) {
            throw new SQLException("reason is required");
        }
        return reason;
    }

    private void decideTotal(Path db, long reviewId, String actor, String reason, String decision)
            throws SQLException, JsonProcessingException {
        transact(db, repo -> {
            TotalReviewItem item = listPendingTotals(repo).stream()
                    .filter(candidate -> candidate.reviewQueueId() == reviewId)
                    .findFirst()
                    .orElseThrow(() -> new SQLException("review item not found: " + reviewId));
            WorkspaceRepository.CandidateIdentity identity = repo.findCandidateIdentity(item.candidateId());
            Long supersedesId = repo.findLatestTotalDecisionId(
                    identity.sourceFileId(), identity.costHeadCode(), identity.fingerprint());
            repo.insertTotalDecision(identity.sourceFileId(), identity.costHeadCode(),
                    identity.fingerprint(), decision, actor, reason, Timestamps.now(), supersedesId);
            repo.resolveReviewQueue(reviewId, decision);
            repo.insertAuditLog(repo.findLatestParseRunId(), "total_" + decision.toLowerCase(),
                    Timestamps.now(), Jsonb.toJson(Map.of(
                            "reviewId", reviewId,
                            "code", identity.costHeadCode(),
                            "fingerprint", identity.fingerprint(),
                            "actor", actor)),
                    "info");
            return null;
        });
    }

    private void decideDuplicate(Path db, long reviewId, String actor, String reason, String decision,
            String supersededRegionKey) throws SQLException, JsonProcessingException {
        transact(db, repo -> {
            DuplicateReviewItem item = listPendingDuplicates(repo).stream()
                    .filter(candidate -> candidate.reviewQueueId() == reviewId)
                    .findFirst()
                    .orElseThrow(() -> new SQLException("review item not found: " + reviewId));
            WorkspaceRepository.ParseContext parse = repo.findLatestParseContext();
            Long supersedesId = repo.findLatestDuplicateDecisionId(
                    parse.sourceFileId(), item.leftRegionKey(), item.rightRegionKey());
            repo.insertDuplicateDecision(parse.sourceFileId(), item.leftRegionKey(), item.rightRegionKey(),
                    decision, supersededRegionKey, actor, reason, Timestamps.now(), supersedesId);
            repo.resolveReviewQueue(reviewId, decision);
            repo.insertAuditLog(parse.parseRunId(), "duplicate_" + decision.toLowerCase(),
                    Timestamps.now(), Jsonb.toJson(Map.of(
                            "reviewId", reviewId,
                            "leftRegionKey", item.leftRegionKey(),
                            "rightRegionKey", item.rightRegionKey(),
                            "decision", decision,
                            "supersededRegionKey", supersededRegionKey == null ? "" : supersededRegionKey,
                            "actor", actor,
                            "reason", reason)),
                    "info");
            for (String code : repo.findCostHeadCodesForRegionKeys(
                    parse.sourceFileId(), item.leftRegionKey(), item.rightRegionKey())) {
                repo.reopenCostHeadCandidateReviews(parse.parseRunId(), code);
            }
            return null;
        });
    }

    @FunctionalInterface
    private interface Write<T> {
        T run(WorkspaceRepository repo) throws SQLException, JsonProcessingException;
    }

    private static <T> T transact(Path db, Write<T> write) throws SQLException, JsonProcessingException {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            Connection connection = workspace.connection();
            connection.setAutoCommit(false);
            try {
                T result = write.run(new WorkspaceRepository(connection));
                connection.commit();
                return result;
            } catch (SQLException | JsonProcessingException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private List<TotalReviewItem> listPendingTotals(WorkspaceRepository repo) throws SQLException {
        long parseRunId = repo.findLatestParseRunId();
        List<TotalReviewItem> items = new ArrayList<>();
        for (WorkspaceRepository.ReviewQueueRow row : repo.findPendingTotalReviews(parseRunId)) {
            Map<String, Object> detail = parseDetail(row.detail());
            items.add(new TotalReviewItem(
                    row.reviewQueueId(),
                    row.summary(),
                    row.detail(),
                    string(detail, "costHeadCode"),
                    string(detail, "fingerprint"),
                    number(detail, "candidateId")));
        }
        return items;
    }

    private List<DuplicateReviewItem> listPendingDuplicates(WorkspaceRepository repo) throws SQLException {
        long parseRunId = repo.findLatestParseRunId();
        List<DuplicateReviewItem> items = new ArrayList<>();
        for (WorkspaceRepository.ReviewQueueRow row : repo.findPendingDuplicateReviews(parseRunId)) {
            Map<String, Object> detail = parseDetail(row.detail());
            items.add(new DuplicateReviewItem(
                    row.reviewQueueId(),
                    row.summary(),
                    row.detail(),
                    string(detail, "leftRegionKey"),
                    string(detail, "rightRegionKey"),
                    string(detail, "method"),
                    number(detail, "proposalId")));
        }
        return items;
    }

    private List<MappingReviewItem> listPendingMappings(WorkspaceRepository repo) throws SQLException {
        long parseRunId = repo.findLatestParseRunId();
        List<MappingReviewItem> items = new ArrayList<>();
        for (WorkspaceRepository.ReviewQueueRow row : repo.findPendingMappingReviews(parseRunId)) {
            Map<String, Object> detail = parseDetail(row.detail());
            items.add(new MappingReviewItem(
                    row.reviewQueueId(),
                    row.summary(),
                    row.detail(),
                    string(detail, "regionKey"),
                    string(detail, "code"),
                    number(detail, "mappingId")));
        }
        return items;
    }

    private static Map<String, Object> parseDetail(String json) {
        try {
            return Jsonb.fromJson(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String string(Map<String, Object> detail, String key) {
        Object value = detail.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Map<String, Object> detail, String key) {
        Object value = detail.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0L : Long.parseLong(String.valueOf(value));
    }
}
