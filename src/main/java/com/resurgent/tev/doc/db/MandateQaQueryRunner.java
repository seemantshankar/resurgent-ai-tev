package com.resurgent.tev.doc.db;

import com.resurgent.tev.doc.embed.EmbeddingClients;
import com.resurgent.tev.doc.embed.SimilaritySearch;
import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.doc.query.MandateQaQuery;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;
import java.util.List;

/**
 * CLI facade for mandate QA retrieval. Delegates to
 * {@link AssessmentRepository#readMandateQa}, and attaches top-K similarity hits
 * when the query asks for them.
 */
public final class MandateQaQueryRunner {

    private MandateQaQueryRunner() {}

    /**
     * Reads a mandate's persisted assessment results.
     *
     * @param db workspace database
     * @param query mandate scope, optionally with a similarity request
     * @return the mandate QA read
     * @throws Exception on SQL failure
     */
    public static MandateQa query(Path db, MandateQaQuery query) throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            AssessmentRepository assessment =
                    new AssessmentRepository(workspace.connection());
            MandateQa qa = assessment.readMandateQa(query.mandateId());
            if (query.similarKind() == null || query.similarKind().isBlank()) {
                return qa;
            }
            List<MandateQa.SimilarHit> hits = SimilaritySearch.topK(
                    workspace.connection(), query.mandateId(), query.similarKind(),
                    query.similarText(), query.similarId(), query.topK(),
                    EmbeddingClients.forAssessment(workspace.connection(), query.mandateId()));
            return qa.withSimilar(hits);
        }
    }
}
