package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.sql.SQLException;

/** Writes what {@link LlmStats} collected for one parse run into the workspace database. */
public final class LlmStatsPersistence {

    private LlmStatsPersistence() {}

    /** Rows for stages that did not run in this process are left as they were. */
    public static void write(WorkspaceRepository repo, long parseRunId, LlmStats stats) throws SQLException {
        for (LlmStats.UsageRow row : stats.usageRows()) {
            repo.recordLlmUsage(parseRunId, row.stage(), row.modelId(), row.calls(), row.failedCalls(),
                    row.failovers(), row.promptTokens(), row.completionTokens(), row.costUsd(),
                    row.costMissing(), row.latencyMsTotal());
        }
        for (LlmStats.Stat stat : stats.stats()) {
            repo.recordRunStat(parseRunId, stat.stage(), stat.name(), stat.num(), stat.text());
        }
        for (LlmStats.Timing timing : stats.timings()) {
            repo.recordRunTiming(parseRunId, timing.stage(), timing.startedAt().toString(),
                    timing.finishedAt().toString(), timing.durationMillis(), timing.items());
        }
    }
}
