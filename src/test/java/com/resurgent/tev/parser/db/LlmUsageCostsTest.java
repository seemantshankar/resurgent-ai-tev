package com.resurgent.tev.parser.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The read seam for {@code llm_usage} cost (#152): one column holding credits for LlamaCloud rows
 * and USD for the rest, totalled once, in USD.
 */
class LlmUsageCostsTest {

    /** Vendor list price the M3 benchmark ran at: 1,000 credits = $1.20. */
    private static final double USD_PER_CREDIT = 0.0012;

    @TempDir
    Path dir;

    /** The shape the live benchmark reported as {@code TOTAL: $878.001089} when the real spend was ~$1.05. */
    @Test
    void mixedUsageRowsTotalInUsdNotInWhateverEachRowHappensToCount() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dir.resolve("mixed.db"))) {
            Connection c = db.connection();
            recordUsage(c, 1, "doc-extract", "llama-extract-2.5-agentic", 800.0, CostUnit.CREDITS);
            recordUsage(c, 1, "doc-extract", "llama-parse:standard", 78.0, CostUnit.CREDITS);
            recordUsage(c, 1, "assess_profile", "openrouter/mercury-2.5", 1.05, CostUnit.USD);

            List<LlmUsageCosts.StageCost> rows = LlmUsageCosts.forMandate(c, 1, USD_PER_CREDIT);

            assertThat(LlmUsageCosts.totalUsd(rows))
                    .isCloseTo(800.0 * USD_PER_CREDIT + 78.0 * USD_PER_CREDIT + 1.05,
                            org.assertj.core.data.Offset.offset(1e-9));

            // What summing the column says, which is why the column cannot be summed as USD:
            // $879.05 for a mandate that cost about $2.10.
            try (Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(
                            "SELECT SUM(cost_usd) FROM llm_usage u JOIN parse_run r USING (parse_run_id)"
                                    + " WHERE r.mandate_id = 1")) {
                rs.next();
                assertThat(rs.getDouble(1)).isEqualTo(879.05);
            }
        }
    }

    @Test
    void eachRowKeepsTheUnitItWasRecordedInAlongsideItsUsd() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dir.resolve("units.db"))) {
            Connection c = db.connection();
            recordUsage(c, 2, "doc-extract", "fake-extract", 45.0, CostUnit.CREDITS);

            LlmUsageCosts.StageCost row = LlmUsageCosts.forMandate(c, 2, USD_PER_CREDIT).get(0);

            assertThat(row.unit()).isEqualTo(CostUnit.CREDITS);
            assertThat(row.recordedAmount()).isEqualTo(45.0);
            assertThat(row.usd()).isEqualTo(45.0 * USD_PER_CREDIT);
        }
    }

    /** A model id that does not look like a llama one is still credits when the writer said credits. */
    @Test
    void usdRowsAreNotMultipliedByTheCreditPrice() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dir.resolve("usd.db"))) {
            Connection c = db.connection();
            recordUsage(c, 3, "doc-bind", "openrouter/gemini-2.5", 2.5, CostUnit.USD);

            List<LlmUsageCosts.StageCost> rows = LlmUsageCosts.forMandate(c, 3, 99.0);

            assertThat(rows.get(0).usd()).isEqualTo(2.5);
            assertThat(LlmUsageCosts.totalUsd(rows)).isEqualTo(2.5);
        }
    }

    private static void recordUsage(Connection c, long mandateId, String stage, String modelId,
            double recorded, CostUnit unit) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys = OFF");
            s.execute("INSERT OR IGNORE INTO parse_run (parse_run_id, mandate_id, source_file_id,"
                    + " parser_version, started_at, status) VALUES ("
                    + mandateId + ", " + mandateId + ", 1, 't', 'now', 'success')");
        }
        new WorkspaceRepository(c).recordLlmUsage(mandateId, stage, modelId, 1, 0, 0, 10, 2,
                recorded, unit, 0, 5);
    }
}
