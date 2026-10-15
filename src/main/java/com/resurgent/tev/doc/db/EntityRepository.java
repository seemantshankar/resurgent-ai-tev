package com.resurgent.tev.doc.db;

import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.parser.db.Timestamps;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistence and retrieval for mandate entities (M3-03, issue #136): the
 * {@code entity} / {@code entity_alias} rows and the {@code fact.entity_id} /
 * {@code fact.entity_reason} tags written by the profile stage's entity scan.
 *
 * <p>The repository never commits or rolls back — the caller owns the transaction,
 * the same discipline as {@link AssessmentRepository} and {@link FactRepository}.
 */
public final class EntityRepository {

    /** Provenance entity type of an entity identification or alias. */
    public static final String PROVENANCE_ENTITY_TYPE = "entity";

    private final Connection connection;

    /**
     * Uses the supplied connection for all operations.
     */
    public EntityRepository(Connection connection) {
        this.connection = connection;
    }

    public Connection connection() {
        return connection;
    }

    /**
     * Drops the mandate's previous entity set for a re-run: facts detach first (the
     * {@code fact.entity_id} reference is enforced immediately by SQLite), then the
     * aliases (they reference the identifications' provenance), the identifications'
     * provenance and the entity rows. Re-running must never duplicate entities.
     *
     * @throws SQLException if any delete fails
     */
    public void clearEntities(long mandateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE fact SET entity_id = NULL, entity_reason = NULL WHERE fact_id IN"
                        + " (SELECT f.fact_id FROM fact f"
                        + " JOIN document_version dv"
                        + "  ON dv.document_version_id = f.document_version_id"
                        + " JOIN document d ON d.document_id = dv.document_id"
                        + " WHERE d.mandate_id = ?)")) {
            ps.setLong(1, mandateId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM entity_alias WHERE entity_id IN"
                        + " (SELECT entity_id FROM entity WHERE mandate_id = ?)")) {
            ps.setLong(1, mandateId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM provenance_doc WHERE entity_type = ? AND entity_id IN"
                        + " (SELECT entity_id FROM entity WHERE mandate_id = ?)")) {
            ps.setString(1, PROVENANCE_ENTITY_TYPE);
            ps.setLong(2, mandateId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM entity WHERE mandate_id = ?")) {
            ps.setLong(1, mandateId);
            ps.executeUpdate();
        }
    }

    /**
     * Writes one fact's entity tag: {@code entityId} when the fact's entity was
     * determined, null when not — the reason is recorded either way.
     *
     * @throws SQLException if the update fails
     */
    public void updateFactEntity(long factId, Long entityId, String reason) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE fact SET entity_id = ?, entity_reason = ? WHERE fact_id = ?")) {
            setLong(ps, 1, entityId);
            if (reason == null) {
                ps.setNull(2, Types.VARCHAR);
            } else {
                ps.setString(2, reason);
            }
            ps.setLong(3, factId);
            ps.executeUpdate();
        }
    }

    /**
     * Inserts one entity of the mandate.
     *
     * @return the {@code entity_id}
     * @throws SQLException if the insert fails
     */
    public long insertEntity(long mandateId, String role, String name, String reason)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO entity (mandate_id, role, name, reason, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, mandateId);
            ps.setString(2, role);
            ps.setString(3, name);
            if (reason == null) {
                ps.setNull(4, Types.VARCHAR);
            } else {
                ps.setString(4, reason);
            }
            ps.setString(5, Timestamps.now());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /**
     * Inserts one spelling variant of an entity's name. {@code provenanceId} anchors the
     * alias to the page it was read from and may be null when the text was not anchored.
     *
     * @throws SQLException if the insert fails
     */
    public void insertAlias(long entityId, String alias, Long provenanceId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO entity_alias (entity_id, alias, provenance_id) VALUES (?, ?, ?)")) {
            ps.setLong(1, entityId);
            ps.setString(2, alias);
            if (provenanceId == null) {
                ps.setNull(3, Types.INTEGER);
            } else {
                ps.setLong(3, provenanceId);
            }
            ps.executeUpdate();
        }
    }

    /**
     * Inserts the provenance row of one entity identification. Page and block are
     * whatever the deterministic anchoring found — never guessed.
     *
     * @return the {@code provenance_id}
     * @throws SQLException if the insert fails
     */
    public long insertProvenance(long entityId, long documentVersionId, Long pageId,
            Long blockId, Double bboxX0, Double bboxY0, Double bboxX1, Double bboxY1,
            String verbatim) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO provenance_doc (entity_type, entity_id, document_version_id,"
                        + " page_id, block_id, span_id, table_cell_id, bbox_x0, bbox_y0,"
                        + " bbox_x1, bbox_y1, char_start, char_end, verbatim, quote_hash,"
                        + " confidence, is_derived)"
                        + " VALUES (?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?, NULL, NULL, ?,"
                        + " NULL, NULL, 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, PROVENANCE_ENTITY_TYPE);
            ps.setLong(2, entityId);
            ps.setLong(3, documentVersionId);
            setLong(ps, 4, pageId);
            setLong(ps, 5, blockId);
            setDouble(ps, 6, bboxX0);
            setDouble(ps, 7, bboxY0);
            setDouble(ps, 8, bboxX1);
            setDouble(ps, 9, bboxY1);
            ps.setString(10, verbatim);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /**
     * The page row id of one document version's page.
     *
     * @return the page id, or null when the version has no such page
     * @throws SQLException if the query fails
     */
    public Long findPageId(long documentVersionId, int pageIndex) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT page_id FROM page WHERE document_version_id = ? AND page_index = ?")) {
            ps.setLong(1, documentVersionId);
            ps.setInt(2, pageIndex);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /**
     * The mandate's entities in identification order, each with its aliases and the
     * distinct source pages of its identifications — section of {@link MandateQa}.
     *
     * @throws SQLException if any query fails
     */
    public List<MandateQa.EntityRow> entities(long mandateId) throws SQLException {
        List<Long> entityIds = new ArrayList<>();
        List<String> roles = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT entity_id, role, name, reason FROM entity WHERE mandate_id = ?"
                        + " ORDER BY entity_id")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entityIds.add(rs.getLong("entity_id"));
                    roles.add(rs.getString("role"));
                    names.add(rs.getString("name"));
                    reasons.add(rs.getString("reason"));
                }
            }
        }
        Map<Long, List<MandateQa.AliasRow>> aliases = new LinkedHashMap<>();
        Map<Long, List<Integer>> pages = new LinkedHashMap<>();
        if (!entityIds.isEmpty()) {
            String idList = idList(entityIds);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT a.entity_id, a.alias, p.page_index"
                            + " FROM entity_alias a"
                            + " LEFT JOIN provenance_doc pd"
                            + "  ON pd.provenance_id = a.provenance_id"
                            + " LEFT JOIN page p ON p.page_id = pd.page_id"
                            + " WHERE a.entity_id IN (" + idList + ")"
                            + " ORDER BY a.entity_alias_id")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int pageIndex = rs.getInt("page_index");
                        aliases.computeIfAbsent(rs.getLong("entity_id"), k -> new ArrayList<>())
                                .add(new MandateQa.AliasRow(rs.getString("alias"),
                                        rs.wasNull() ? null : pageIndex));
                    }
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT DISTINCT pd.entity_id, p.page_index"
                            + " FROM provenance_doc pd"
                            + " JOIN page p ON p.page_id = pd.page_id"
                            + " WHERE pd.entity_type = '" + PROVENANCE_ENTITY_TYPE + "'"
                            + " AND pd.entity_id IN (" + idList + ")"
                            + " ORDER BY pd.entity_id, p.page_index")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        pages.computeIfAbsent(rs.getLong("entity_id"), k -> new ArrayList<>())
                                .add(rs.getInt("page_index"));
                    }
                }
            }
        }
        List<MandateQa.EntityRow> rows = new ArrayList<>(entityIds.size());
        for (int i = 0; i < entityIds.size(); i++) {
            long id = entityIds.get(i);
            rows.add(new MandateQa.EntityRow(id, roles.get(i), names.get(i), reasons.get(i),
                    aliases.getOrDefault(id, List.of()), pages.getOrDefault(id, List.of())));
        }
        return rows;
    }

    private static String idList(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    private static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setDouble(PreparedStatement ps, int index, Double value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.REAL);
        } else {
            ps.setDouble(index, value);
        }
    }
}
