package com.resurgent.tev.doc.db;

import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.doc.query.MandateQaQuery;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;

/**
 * CLI facade for mandate QA retrieval. Delegates to
 * {@link AssessmentRepository#readMandateQa}.
 */
public final class MandateQaQueryRunner {

    private MandateQaQueryRunner() {}

    /**
     * Reads a mandate's persisted assessment results.
     *
     * @param db workspace database
     * @param query mandate scope
     * @return the mandate QA read
     * @throws Exception on SQL failure
     */
    public static MandateQa query(Path db, MandateQaQuery query) throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            AssessmentRepository assessment =
                    new AssessmentRepository(workspace.connection());
            return assessment.readMandateQa(query.mandateId());
        }
    }
}
