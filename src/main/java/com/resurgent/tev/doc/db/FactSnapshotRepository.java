package com.resurgent.tev.doc.db;

import com.resurgent.tev.parser.db.Timestamps;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persistence and retrieval for output fact snapshots (Architecture §6.5, spec #162, issue #163).
 * Captures an immutable record of facts consumed by downstream outputs (TEV sections, CMA extracts,
 * FM comparisons) to enable automated stale detection upon fact supersession.
 *
 * <p>Connection is owned by the caller; methods never commit or roll back.
 */
public final class FactSnapshotRepository {

    public record FactSnapshot(
            long snapshotId,
            long mandateId,
            String outputType,
            String outputKey,
            boolean isStale,
            String staleAt,
            String staleReason,
            String createdAt) {
    }

    public record SnapshotFactLink(
            long snapshotId,
            long factId,
            String recordedValue,
            String recordedAt) {
    }

    public record FactLinkWrite(
            long factId,
            String recordedValue) {
    }

    private final Connection connection;

    public FactSnapshotRepository(Connection connection) {
        this.connection = connection;
    }

    public Connection connection() {
        return connection;
    }

    /**
     * Atomically saves or replaces a fact snapshot and its linked fact values.
     */
    public FactSnapshot saveSnapshot(
            long mandateId,
            String outputType,
            String outputKey,
            List<FactLinkWrite> links) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        if (originalAutoCommit) {
            connection.setAutoCommit(false);
        }
        Savepoint sp = connection.setSavepoint();
        try {
            // If a snapshot already exists for this (mandate, outputType, outputKey), replace it
            Optional<FactSnapshot> existing = findSnapshot(mandateId, outputType, outputKey);
            if (existing.isPresent()) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM fact_snapshot WHERE snapshot_id = ?")) {
                    ps.setLong(1, existing.get().snapshotId());
                    ps.executeUpdate();
                }
            }

            String now = Timestamps.now();
            long snapshotId;
            String insertSql = "INSERT INTO fact_snapshot (mandate_id, output_type, output_key, is_stale, created_at)"
                    + " VALUES (?, ?, ?, 0, ?)";
            try (PreparedStatement ps = connection.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, mandateId);
                ps.setString(2, outputType);
                ps.setString(3, outputKey);
                ps.setString(4, now);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (!rs.next()) {
                        throw new SQLException("failed to retrieve generated snapshot_id");
                    }
                    snapshotId = rs.getLong(1);
                }
            }

            if (links != null && !links.isEmpty()) {
                String linkSql = "INSERT INTO snapshot_fact_link (snapshot_id, fact_id, recorded_value, recorded_at)"
                        + " VALUES (?, ?, ?, ?)";
                try (PreparedStatement ps = connection.prepareStatement(linkSql)) {
                    for (FactLinkWrite link : links) {
                        ps.setLong(1, snapshotId);
                        ps.setLong(2, link.factId());
                        ps.setString(3, link.recordedValue());
                        ps.setString(4, now);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }

            connection.releaseSavepoint(sp);
            if (originalAutoCommit) {
                connection.commit();
            }

            return new FactSnapshot(snapshotId, mandateId, outputType, outputKey, false, null, null, now);
        } catch (SQLException | RuntimeException e) {
            try {
                connection.rollback(sp);
            } catch (SQLException ignore) {
            }
            if (originalAutoCommit) {
                try {
                    connection.rollback();
                } catch (SQLException ignore) {
                }
            }
            throw e;
        } finally {
            if (originalAutoCommit) {
                connection.setAutoCommit(true);
            }
        }
    }

    /**
     * Finds a snapshot by (mandateId, outputType, outputKey).
     */
    public Optional<FactSnapshot> findSnapshot(
            long mandateId,
            String outputType,
            String outputKey) throws SQLException {
        String sql = "SELECT snapshot_id, mandate_id, output_type, output_key, is_stale, stale_at, stale_reason, created_at"
                + " FROM fact_snapshot WHERE mandate_id = ? AND output_type = ? AND output_key = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, mandateId);
            ps.setString(2, outputType);
            ps.setString(3, outputKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapSnapshot(rs));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Finds a snapshot by its primary key.
     */
    public Optional<FactSnapshot> findSnapshotById(long snapshotId) throws SQLException {
        String sql = "SELECT snapshot_id, mandate_id, output_type, output_key, is_stale, stale_at, stale_reason, created_at"
                + " FROM fact_snapshot WHERE snapshot_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, snapshotId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapSnapshot(rs));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Finds all facts linked to a snapshot.
     */
    public List<SnapshotFactLink> findLinkedFacts(long snapshotId) throws SQLException {
        String sql = "SELECT snapshot_id, fact_id, recorded_value, recorded_at"
                + " FROM snapshot_fact_link WHERE snapshot_id = ? ORDER BY fact_id ASC";
        List<SnapshotFactLink> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, snapshotId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new SnapshotFactLink(
                            rs.getLong("snapshot_id"),
                            rs.getLong("fact_id"),
                            rs.getString("recorded_value"),
                            rs.getString("recorded_at")));
                }
            }
        }
        return result;
    }

    /**
     * Finds all snapshots in a mandate flagged as stale.
     */
    public List<FactSnapshot> findStaleSnapshots(long mandateId) throws SQLException {
        String sql = "SELECT snapshot_id, mandate_id, output_type, output_key, is_stale, stale_at, stale_reason, created_at"
                + " FROM fact_snapshot WHERE mandate_id = ? AND is_stale = 1 ORDER BY snapshot_id ASC";
        List<FactSnapshot> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapSnapshot(rs));
                }
            }
        }
        return result;
    }

    /**
     * Marks a snapshot as stale with a given reason.
     */
    public void markStale(long snapshotId, String staleReason) throws SQLException {
        String now = Timestamps.now();
        String sql = "UPDATE fact_snapshot SET is_stale = 1, stale_at = ?, stale_reason = ? WHERE snapshot_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, now);
            ps.setString(2, staleReason);
            ps.setLong(3, snapshotId);
            ps.executeUpdate();
        }
    }

    /**
     * Finds snapshot IDs that link to a specific fact ID.
     */
    public List<Long> findSnapshotIdsReferencingFact(long factId) throws SQLException {
        String sql = "SELECT snapshot_id FROM snapshot_fact_link WHERE fact_id = ? ORDER BY snapshot_id ASC";
        List<Long> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, factId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getLong(1));
                }
            }
        }
        return result;
    }

    /**
     * Total number of snapshots for a mandate.
     */
    public int countSnapshots(long mandateId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM fact_snapshot WHERE mandate_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Total number of stale snapshots for a mandate.
     */
    public int countStaleSnapshots(long mandateId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM fact_snapshot WHERE mandate_id = ? AND is_stale = 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Performs a freshness evaluation pass: finds snapshots referencing superseded facts
     * (is_current = 0), marks them is_stale = 1, and records an explanatory stale_reason.
     *
     * @param mandateId mandate scope
     * @return number of newly invalidated snapshots
     */
    public int evaluateFreshness(long mandateId) throws SQLException {
        String sql = """
            SELECT fs.snapshot_id,
                   sfl.fact_id AS superseded_fact_id,
                   dv.document_id AS superseded_doc_id,
                   f.superseded_by_fact_id,
                   sdv.document_id AS superseding_doc_id
            FROM fact_snapshot fs
            JOIN snapshot_fact_link sfl ON fs.snapshot_id = sfl.snapshot_id
            JOIN fact f ON sfl.fact_id = f.fact_id
            LEFT JOIN document_version dv ON f.document_version_id = dv.document_version_id
            LEFT JOIN fact sf ON f.superseded_by_fact_id = sf.fact_id
            LEFT JOIN document_version sdv ON sf.document_version_id = sdv.document_version_id
            WHERE fs.mandate_id = ? AND f.is_current = 0 AND fs.is_stale = 0
            ORDER BY fs.snapshot_id ASC, sfl.fact_id ASC
            """;
        java.util.Map<Long, List<String>> reasonsBySnapshot = new java.util.LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long snapshotId = rs.getLong("snapshot_id");
                    long supersededFactId = rs.getLong("superseded_fact_id");
                    Object supersededDocObj = rs.getObject("superseded_doc_id");
                    Long supersededDocId = supersededDocObj != null ? ((Number) supersededDocObj).longValue() : null;
                    Object supersedingFactObj = rs.getObject("superseded_by_fact_id");
                    Long supersedingFactId = supersedingFactObj != null ? ((Number) supersedingFactObj).longValue() : null;
                    Object supersedingDocObj = rs.getObject("superseding_doc_id");
                    Long supersedingDocId = supersedingDocObj != null ? ((Number) supersedingDocObj).longValue() : null;

                    String reason;
                    if (supersedingFactId != null) {
                        if (supersedingDocId != null) {
                            reason = String.format("fact #%d was superseded by fact #%d (doc #%d)",
                                    supersededFactId, supersedingFactId, supersedingDocId);
                        } else {
                            reason = String.format("fact #%d was superseded by fact #%d",
                                    supersededFactId, supersedingFactId);
                        }
                    } else {
                        reason = String.format("fact #%d is no longer current", supersededFactId);
                    }
                    reasonsBySnapshot.computeIfAbsent(snapshotId, k -> new ArrayList<>()).add(reason);
                }
            }
        }

        for (java.util.Map.Entry<Long, List<String>> entry : reasonsBySnapshot.entrySet()) {
            markStale(entry.getKey(), String.join("; ", entry.getValue()));
        }
        return reasonsBySnapshot.size();
    }

    private static FactSnapshot mapSnapshot(ResultSet rs) throws SQLException {
        return new FactSnapshot(
                rs.getLong("snapshot_id"),
                rs.getLong("mandate_id"),
                rs.getString("output_type"),
                rs.getString("output_key"),
                rs.getInt("is_stale") == 1,
                rs.getString("stale_at"),
                rs.getString("stale_reason"),
                rs.getString("created_at"));
    }
}
