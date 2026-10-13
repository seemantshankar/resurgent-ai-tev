package com.resurgent.tev.doc.db;

import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;
import java.util.List;

/**
 * CLI facade for fact retrieval. Delegates to {@link FactRepository#query}.
 */
public final class FactQueryRunner {

    private FactQueryRunner() {}

    /**
     * Queries facts for a mandate scope.
     *
     * @param db workspace database
     * @param query filters
     * @return sorted fact rows with evidence
     * @throws Exception on SQL failure
     */
    public static List<FactRow> query(Path db, FactQuery query) throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            FactRepository facts = new FactRepository(workspace.connection());
            return facts.query(query);
        }
    }
}
