package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.sql.SQLException;

/** Writes what {@link LlmStats} collected for one parse run into the workspace database. */
public final class LlmStatsPersistence {

    private LlmStatsPersistence() {}

    /**
     * A stage that ran in this process is replaced as a whole: its old rows are removed first, so
     * a model or stat the new run did not produce does not linger. Stages that did not run in this
     * process (e.g. region layout when classify resumes at Layer B) are left as they were.
     */
    public static void write(WorkspaceRepository repo, long parseRunId, LlmStats stats) throws SQLException {
        java.util.Set<String> stages = new java.util.LinkedHashSet<>();
        stats.usageRows().forEach(row -> stages.add(row.stage()));
        stats.stats().forEach(stat -> stages.add(stat.stage()));
        stats.timings().forEach(timing -> stages.add(timing.stage()));
        for (String stage : stages) {
            repo.deleteStageStats(parseRunId, stage);
        }
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
