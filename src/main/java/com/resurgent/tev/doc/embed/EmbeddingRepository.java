package com.resurgent.tev.doc.embed;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Idempotent persistence for {@code clause_embedding} and {@code passage_embedding}.
 * The caller owns the transaction. A second upsert for the same parent replaces
 * the vector in place.
 */
public final class EmbeddingRepository {

    /** One stored vector. */
    public record Stored(String model, int dims, float[] vector) {}

    private final Connection connection;

    /**
     * @param connection open workspace connection (required)
     */
    public EmbeddingRepository(Connection connection) {
        this.connection = connection;
    }

    /**
     * Inserts or replaces the embedding of one clause.
     *
     * @throws SQLException if the write fails
     */
    public void upsertClause(long clauseId, String model, float[] vector) throws SQLException {
        upsert("clause_embedding", "clause_id", clauseId, model, vector);
    }

    /**
     * Inserts or replaces the embedding of one passage.
     *
     * @throws SQLException if the write fails
     */
    public void upsertPassage(long passageId, String model, float[] vector) throws SQLException {
        upsert("passage_embedding", "passage_id", passageId, model, vector);
    }

    /** The clause vector, or empty when this clause has not been embedded. */
    public Optional<Stored> findClause(long clauseId) throws SQLException {
        return find("clause_embedding", "clause_id", clauseId);
    }

    /** The passage vector, or empty when this passage has not been embedded. */
    public Optional<Stored> findPassage(long passageId) throws SQLException {
        return find("passage_embedding", "passage_id", passageId);
    }

    /** How many clause embeddings are stored. */
    public long clauseCount() throws SQLException {
        return count("clause_embedding");
    }

    /** How many passage embeddings are stored. */
    public long passageCount() throws SQLException {
        return count("passage_embedding");
    }

    /**
     * Unit centroid of the embeddings of each still-open proposed clause type in
     * the mandate. Codes with no embedding are absent.
     *
     * @throws SQLException if the query fails
     */
    public Map<String, float[]> openProposalCentroids(long mandateId) throws SQLException {
        Map<String, List<float[]>> grouped = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ct.code, ce.vec"
                        + " FROM clause_type ct"
                        + " JOIN clause cl ON cl.clause_type_id = ct.clause_type_id"
                        + " JOIN clause_embedding ce ON ce.clause_id = cl.clause_id"
                        + " JOIN document_version dv"
                        + "  ON dv.document_version_id = cl.document_version_id"
                        + " JOIN document d ON d.document_id = dv.document_id"
                        + " WHERE ct.source = 'proposed' AND ct.status = 'open'"
                        + "  AND cl.status = 'open'"
                        + "  AND d.mandate_id = ? AND d.status != 'deleted_by_analyst'")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    grouped.computeIfAbsent(rs.getString(1), key -> new ArrayList<>())
                            .add(EmbeddingVectors.fromLittleEndian(rs.getBytes(2)));
                }
            }
        }
        Map<String, float[]> centroids = new LinkedHashMap<>();
        for (Map.Entry<String, List<float[]>> entry : grouped.entrySet()) {
            float[] centroid = EmbeddingVectors.meanUnit(entry.getValue());
            if (centroid != null) {
                centroids.put(entry.getKey(), centroid);
            }
        }
        return centroids;
    }

    private void upsert(String table, String idColumn, long id, String model, float[] vector)
            throws SQLException {
        float[] values = vector == null ? new float[0] : vector;
        String sql = "INSERT INTO " + table + " (" + idColumn + ", model, dims, vec)"
                + " VALUES (?, ?, ?, ?)"
                + " ON CONFLICT (" + idColumn + ") DO UPDATE SET"
                + " model = excluded.model, dims = excluded.dims, vec = excluded.vec";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, model == null ? "" : model);
            ps.setInt(3, values.length);
            ps.setBytes(4, EmbeddingVectors.toLittleEndian(values));
            ps.executeUpdate();
        }
    }

    private Optional<Stored> find(String table, String idColumn, long id) throws SQLException {
        String sql = "SELECT model, dims, vec FROM " + table + " WHERE " + idColumn + " = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                byte[] blob = rs.getBytes(3);
                return Optional.of(new Stored(rs.getString(1), rs.getInt(2),
                        EmbeddingVectors.fromLittleEndian(blob)));
            }
        }
    }

    private long count(String table) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table);
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
