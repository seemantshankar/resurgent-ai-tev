package com.resurgent.tev.doc.db;

import com.resurgent.tev.parser.db.Timestamps;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Persists two-run extraction drift (#157). */
public final class UnstableReadingRepository {

    private final Connection connection;

    public UnstableReadingRepository(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    public record UnstableReadingRow(
            long unstableReadingId,
            long mandateId,
            long documentVersionId,
            String fieldKey,
            String lineKey,
            long parseRunIdA,
            long parseRunIdB,
            Long factIdA,
            Long factIdB,
            String valueA,
            String valueB,
            Integer pageA,
            Integer pageB,
            String instructionText,
            String reason) {}

    public record Write(
            long mandateId,
            long documentVersionId,
            String fieldKey,
            String lineKey,
            long parseRunIdA,
            long parseRunIdB,
            Long factIdA,
            Long factIdB,
            String valueA,
            String valueB,
            Integer pageA,
            Integer pageB,
            String instructionText,
            String reason) {}

    public void clearForVersion(long documentVersionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM unstable_reading WHERE document_version_id = ?")) {
            ps.setLong(1, documentVersionId);
            ps.executeUpdate();
        }
    }

    public void insert(Write row) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO unstable_reading (mandate_id, document_version_id, field_key,"
                        + " line_key, parse_run_id_a, parse_run_id_b, fact_id_a, fact_id_b,"
                        + " value_a, value_b, page_a, page_b, instruction_text, reason, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, row.mandateId());
            ps.setLong(2, row.documentVersionId());
            ps.setString(3, row.fieldKey());
            setNullableString(ps, 4, row.lineKey());
            ps.setLong(5, row.parseRunIdA());
            ps.setLong(6, row.parseRunIdB());
            setNullableLong(ps, 7, row.factIdA());
            setNullableLong(ps, 8, row.factIdB());
            ps.setString(9, row.valueA());
            ps.setString(10, row.valueB());
            setNullableInt(ps, 11, row.pageA());
            setNullableInt(ps, 12, row.pageB());
            setNullableString(ps, 13, row.instructionText());
            ps.setString(14, row.reason() == null ? "unstable_across_runs" : row.reason());
            ps.setString(15, Timestamps.now());
            ps.executeUpdate();
        }
    }

    public List<UnstableReadingRow> listForVersion(long documentVersionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT unstable_reading_id, mandate_id, document_version_id, field_key, line_key,"
                        + " parse_run_id_a, parse_run_id_b, fact_id_a, fact_id_b, value_a, value_b,"
                        + " page_a, page_b, instruction_text, reason"
                        + " FROM unstable_reading WHERE document_version_id = ?"
                        + " ORDER BY unstable_reading_id")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<UnstableReadingRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new UnstableReadingRow(
                            rs.getLong("unstable_reading_id"),
                            rs.getLong("mandate_id"),
                            rs.getLong("document_version_id"),
                            rs.getString("field_key"),
                            rs.getString("line_key"),
                            rs.getLong("parse_run_id_a"),
                            rs.getLong("parse_run_id_b"),
                            asLong(rs.getObject("fact_id_a")),
                            asLong(rs.getObject("fact_id_b")),
                            rs.getString("value_a"),
                            rs.getString("value_b"),
                            asInt(rs.getObject("page_a")),
                            asInt(rs.getObject("page_b")),
                            rs.getString("instruction_text"),
                            rs.getString("reason")));
                }
                return rows;
            }
        }
    }

    private static void setNullableString(PreparedStatement ps, int index, String value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static Long asLong(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        return ((Number) value).longValue();
    }

    private static Integer asInt(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        return ((Number) value).intValue();
    }
}
