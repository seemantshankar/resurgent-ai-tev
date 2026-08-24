package com.resurgent.tev.parser.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.resurgent.tev.parser.db.Jsonb;
import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.math.BigDecimal;
import java.nio.file.Path;
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

    public List<MappingReviewItem> listPendingMappings(Path db) throws SQLException {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            return listPending(new WorkspaceRepository(workspace.connection()));
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

    public long addManual(Path db, String costHeadCode, BigDecimal amount, String unit, String currency,
            String actor, String reason, Long adjustsContributionId)
            throws SQLException, JsonProcessingException {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
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
        }
    }

    public void acceptManual(Path db, long manualId, String actor)
            throws SQLException, JsonProcessingException {
        updateManual(db, manualId, actor, null, "Accepted", "Pending");
    }

    public void withdrawManual(Path db, long manualId, String actor, String reason)
            throws SQLException, JsonProcessingException {
        updateManual(db, manualId, actor, reason, "Withdrawn", "Accepted", "Pending");
    }

    public void addCostHead(Path db, long mandateId, String code) throws SQLException {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            new WorkspaceRepository(workspace.connection()).ensureCostHead(mandateId, code);
        }
    }

    private void decide(Path db, long reviewId, String actor, String reason, String decision)
            throws SQLException, JsonProcessingException {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            MappingReviewItem item = listPending(repo).stream()
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
        }
    }

    private void updateManual(Path db, long manualId, String actor, String reason, String status,
            String... allowedCurrent) throws SQLException, JsonProcessingException {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
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
                            "reason", reason == null ? "" : reason,
                            "status", status)),
                    "info");
        }
    }

    private void decideTotal(Path db, long reviewId, String actor, String reason, String decision)
            throws SQLException, JsonProcessingException {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
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
        }
    }

    private List<TotalReviewItem> listPendingTotals(WorkspaceRepository repo) throws SQLException {
        long parseRunId = repo.findLatestParseRunId();
        List<TotalReviewItem> items = new ArrayList<>();
        for (WorkspaceRepository.MappingReviewRow row : repo.findPendingTotalReviews(parseRunId)) {
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

    private List<MappingReviewItem> listPending(WorkspaceRepository repo) throws SQLException {
        long parseRunId = repo.findLatestParseRunId();
        List<MappingReviewItem> items = new ArrayList<>();
        for (WorkspaceRepository.MappingReviewRow row : repo.findPendingMappingReviews(parseRunId)) {
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
