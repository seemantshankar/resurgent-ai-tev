package com.resurgent.tev.doc.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes and reads {@code induction_decision}. Clause induction (#174) inserts
 * rows; pack and checklist induction reuse the same table later. The caller
 * owns the transaction.
 */
public final class InductionDecisionRepository {

    /** One recorded induction outcome. */
    public record Decision(
            Long decisionId,
            long mandateId,
            Long parseRunId,
            String stage,
            String targetCode,
            String candidateKey,
            String outcome,
            String reasonCode,
            String detailJson) {}

    private final Connection connection;

    /**
     * @param connection open workspace connection (required)
     */
    public InductionDecisionRepository(Connection connection) {
        this.connection = connection;
    }

    /**
     * Drops this run's previous rows for a stage so a re-run replaces them.
     *
     * @throws SQLException if the delete fails
     */
    public void deleteForRun(long parseRunId, String stage) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM induction_decision WHERE parse_run_id = ? AND stage = ?")) {
            ps.setLong(1, parseRunId);
            ps.setString(2, stage);
            ps.executeUpdate();
        }
    }

    /**
     * Inserts one decision.
     *
     * @return the generated {@code decision_id}
     * @throws SQLException if the insert fails
     */
    public long insert(Decision decision) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO induction_decision (mandate_id, parse_run_id, stage, target_code,"
                        + " candidate_key, outcome, reason_code, detail_json)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, decision.mandateId());
            if (decision.parseRunId() == null) {
                ps.setNull(2, Types.INTEGER);
            } else {
                ps.setLong(2, decision.parseRunId());
            }
            ps.setString(3, decision.stage());
            setString(ps, 4, decision.targetCode());
            setString(ps, 5, decision.candidateKey());
            ps.setString(6, decision.outcome());
            setString(ps, 7, decision.reasonCode());
            setString(ps, 8, decision.detailJson());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("induction_decision insert returned no id");
    }

    /**
     * Every induction decision for a mandate, oldest first, across pack, clause,
     * and checklist stages.
     *
     * @throws SQLException if the query fails
     */
    public List<Decision> forMandate(long mandateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT decision_id, mandate_id, parse_run_id, stage, target_code,"
                        + " candidate_key, outcome, reason_code, detail_json"
                        + " FROM induction_decision"
                        + " WHERE mandate_id = ?"
                        + " ORDER BY decision_id")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                return read(rs);
            }
        }
    }

    public List<Decision> forMandate(long mandateId, String stage) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT decision_id, mandate_id, parse_run_id, stage, target_code,"
                        + " candidate_key, outcome, reason_code, detail_json"
                        + " FROM induction_decision"
                        + " WHERE mandate_id = ? AND stage = ?"
                        + " ORDER BY decision_id")) {
            ps.setLong(1, mandateId);
            ps.setString(2, stage);
            try (ResultSet rs = ps.executeQuery()) {
                return read(rs);
            }
        }
    }

    private static List<Decision> read(ResultSet rs) throws SQLException {
        List<Decision> rows = new ArrayList<>();
        while (rs.next()) {
            long parseRunId = rs.getLong("parse_run_id");
            Long parseRun = rs.wasNull() ? null : parseRunId;
            rows.add(new Decision(
                    rs.getLong("decision_id"),
                    rs.getLong("mandate_id"),
                    parseRun,
                    rs.getString("stage"),
                    rs.getString("target_code"),
                    rs.getString("candidate_key"),
                    rs.getString("outcome"),
                    rs.getString("reason_code"),
                    rs.getString("detail_json")));
        }
        return rows;
    }

    private static void setString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }
}
