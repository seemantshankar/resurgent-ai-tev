package com.resurgent.tev.parser.cli;

import com.resurgent.tev.parser.classify.LlmStats;
import com.resurgent.tev.parser.classify.LlmStatsPersistence;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.io.PrintWriter;
import java.nio.file.Path;

/** Saves {@link LlmStats} for a parse run; a failure is reported but never fails the command. */
final class StatsRecorder {

    private StatsRecorder() {}

    static void persist(PrintWriter out, Path db, long parseRunId) {
        try (WorkspaceDatabase wdb = WorkspaceDatabase.open(db.toAbsolutePath().normalize())) {
            LlmStatsPersistence.write(new WorkspaceRepository(wdb.connection()), parseRunId, LlmStats.GLOBAL);
        } catch (Exception e) {
            out.println("warning: failed to persist run stats: " + e.getMessage());
        }
    }
}
