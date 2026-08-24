package com.resurgent.tev.parser.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.resurgent.tev.parser.db.Jsonb;
import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
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

    public void acceptMapping(Path db, long reviewId, String actor, String reason)
            throws SQLException, JsonProcessingException {
        decide(db, reviewId, actor, reason == null ? "accepted" : reason, "Accepted");
    }

    public void rejectMapping(Path db, long reviewId, String actor, String reason)
            throws SQLException, JsonProcessingException {
        decide(db, reviewId, actor, reason == null ? "rejected" : reason, "Rejected");
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
