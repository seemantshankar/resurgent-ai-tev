package com.resurgent.tev.parser.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code llm_usage} cost as the money it represents. The cost column holds LlamaCloud credits
 * on rows written by the extract path and USD on every other row, so summing the column without
 * consulting {@code cost_unit} adds two different things together (#152). Every USD cost reported
 * anywhere comes through here.
 */
public final class LlmUsageCosts {

    private LlmUsageCosts() {}

    /**
     * One (stage, model) total.
     *
     * @param recordedAmount the amount as stored, in {@code unit}
     * @param usd the same spend in dollars: credits converted at the rate the caller supplied
     */
    public record StageCost(
            String stage, String modelId, long calls, long promptTokens, long completionTokens,
            double recordedAmount, CostUnit unit, double usd) {}

    /**
     * Usage recorded for one mandate across its parse runs.
     *
     * @param usdPerCredit configured LlamaCloud credit price, used only on credit rows
     */
    public static List<StageCost> forMandate(Connection connection, long mandateId, double usdPerCredit)
            throws SQLException {
        List<StageCost> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT u.stage, u.model_id, u.cost_unit, SUM(u.calls) calls,"
                        + " SUM(u.prompt_tokens) prompt_tokens, SUM(u.completion_tokens) completion_tokens,"
                        + " SUM(COALESCE(u.cost_usd, 0)) recorded"
                        + " FROM llm_usage u JOIN parse_run r USING (parse_run_id)"
                        + " WHERE r.mandate_id = ?"
                        + " GROUP BY u.stage, u.model_id, u.cost_unit"
                        + " ORDER BY u.stage, u.model_id")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CostUnit unit = CostUnit.fromWire(rs.getString("cost_unit"));
                    double recorded = rs.getDouble("recorded");
                    rows.add(new StageCost(rs.getString("stage"), rs.getString("model_id"),
                            rs.getLong("calls"), rs.getLong("prompt_tokens"),
                            rs.getLong("completion_tokens"), recorded, unit,
                            unit.toUsd(recorded, usdPerCredit)));
                }
            }
        }
        return rows;
    }

    /** The mandate's spend in USD. */
    public static double totalUsd(List<StageCost> rows) {
        double total = 0;
        for (StageCost row : rows) {
            total += row.usd();
        }
        return total;
    }
}
