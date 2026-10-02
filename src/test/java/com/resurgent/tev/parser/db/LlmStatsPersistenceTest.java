package com.resurgent.tev.parser.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.classify.LlmStats;
import com.resurgent.tev.parser.classify.LlmStatsPersistence;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LlmStatsPersistenceTest {

    @TempDir
    Path dir;

    private static long scalar(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    @Test
    void storesUsagePerModelStatsAndTimingsAndRerunReplacesThem() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dir.resolve("w.db"))) {
            Connection c = db.connection();
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA foreign_keys = OFF"); // a parse_run row is not what is under test
            }
            WorkspaceRepository repo = new WorkspaceRepository(c);
            LlmStats stats = new LlmStats();
            stats.enterStage("layer-b");
            stats.recordCall("inception/mercury-2.5", 1000, 100, 0.01, 2000);
            stats.recordCall("liquid/d1", 235, 0, 0.0000094, 900);
            stats.put("layer-b", "cells_to_decision_model", 1092);
            stats.putText("run", "models_chain", "a -> b");
            Instant t = Instant.parse("2026-10-03T00:00:00Z");
            stats.timing("layer-b", t, t.plusSeconds(116), 5345);

            LlmStatsPersistence.write(repo, 7, stats);

            assertThat(scalar(c, "SELECT COUNT(*) FROM llm_usage WHERE parse_run_id = 7")).isEqualTo(2);
            assertThat(scalar(c, "SELECT calls FROM llm_usage WHERE model_id = 'liquid/d1' AND stage = 'layer-b'"))
                    .isEqualTo(1);
            assertThat(scalar(c, "SELECT value_num FROM run_stat WHERE name = 'cells_to_decision_model'"))
                    .isEqualTo(1092);
            assertThat(scalar(c, "SELECT COUNT(*) FROM run_stat WHERE name = 'models_chain' AND value_text = 'a -> b'"))
                    .isEqualTo(1);
            assertThat(scalar(c, "SELECT duration_millis FROM run_timing WHERE stage = 'layer-b'"))
                    .isEqualTo(116_000);

            LlmStats rerun = new LlmStats();
            rerun.enterStage("layer-b");
            rerun.recordCall("liquid/d1", 1, 0, 0.0, 1);
            rerun.put("layer-b", "cells_to_decision_model", 5);
            LlmStatsPersistence.write(repo, 7, rerun);

            assertThat(scalar(c, "SELECT prompt_tokens FROM llm_usage WHERE model_id = 'liquid/d1'")).isEqualTo(1);
            assertThat(scalar(c, "SELECT COUNT(*) FROM llm_usage")).isEqualTo(2); // replaced, not duplicated
            assertThat(scalar(c, "SELECT value_num FROM run_stat WHERE name = 'cells_to_decision_model'")).isEqualTo(5);
        }
    }

    /** The V39 migration must carry V37's lumped rows over as model '*' instead of dropping them. */
    @Test
    void v39KeepsRowsWrittenBeforeItAsModelStar() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("legacy.db"))) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE parse_run (parse_run_id INTEGER PRIMARY KEY)");
                s.execute(read("db/migration/V37__llm_usage.sql").replaceAll("(?m)^--.*$", "").trim().replaceAll(";\\s*$", "")
                        .split(";\\s*\\R")[0]);
                s.execute("INSERT INTO parse_run VALUES (1)");
                s.execute("INSERT INTO llm_usage (parse_run_id, stage, calls, prompt_tokens, completion_tokens, cost_usd)"
                        + " VALUES (1, 'layer-a', 53, 2060771, 114951, 0.22)");
                for (String statement : read("db/migration/V39__llm_stats_context.sql").split(";\\s*\\R")) {
                    String trimmed = statement.replaceAll("(?m)^--.*$", "").trim();
                    if (!trimmed.isEmpty()) {
                        s.execute(trimmed);
                    }
                }
            }
            assertThat(scalar(c, "SELECT COUNT(*) FROM llm_usage WHERE model_id = '*' AND calls = 53")).isEqualTo(1);
            assertThat(scalar(c, "SELECT failed_calls FROM llm_usage")).isZero();
        }
    }

    private static String read(String resource) throws Exception {
        try (var in = LlmStatsPersistenceTest.class.getClassLoader().getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
