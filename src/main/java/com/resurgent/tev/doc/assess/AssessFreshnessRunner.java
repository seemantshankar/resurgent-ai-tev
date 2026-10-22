package com.resurgent.tev.doc.assess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.doc.db.AssessmentRepository;
import com.resurgent.tev.doc.db.FactSnapshotRepository;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.version.FactSupersessionService;
import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Locale;

/**
 * {@code assess_freshness} (spec #162, issue #169, ADR 0028): runs fact supersession
 * resolution and evaluates output fact snapshots. When a snapshot references an older fact
 * that has been superseded (is_current = 0), flags the snapshot as stale (is_stale = 1)
 * with an explanatory reason identifying the superseded and superseding facts.
 */
public final class AssessFreshnessRunner implements AssessStage {

    /** Exact M4 stage name. */
    public static final String STAGE = "assess_freshness";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    /**
     * Returns the unique stage identifier for freshness assessment.
     */
    public String name() {
        return STAGE;
    }

    @Override
    /**
     * Executes the freshness assessment stage for the specified mandate.
     */
    public StageResult run(long mandateId, Path dbPath, DocLlmClient client) throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(dbPath)) {
            Connection connection = workspace.connection();
            connection.setAutoCommit(false);
            try {
                StageResult result = assess(connection, mandateId);
                connection.commit();
                return result;
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (Exception ignored) {
                }
                throw e;
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Evaluates fact supersession and marks affected fact snapshots as stale.
     */
    private StageResult assess(Connection connection, long mandateId) throws Exception {
        AssessmentRepository assessment = new AssessmentRepository(connection);
        FactSnapshotRepository snapshots = new FactSnapshotRepository(connection);
        WorkspaceRepository repo = new WorkspaceRepository(connection);

        // 1. Resolve supersession so fact is_current flags and pointers are current
        FactSupersessionService.resolveSupersession(connection, mandateId);

        String startedAt = Timestamps.now();
        Long parseRunId = null;
        Long sourceFileId = assessment.anchorSourceFileId(mandateId);
        if (sourceFileId != null) {
            String configHash = "assess:" + STAGE;
            parseRunId = repo.findParseRunId(sourceFileId, DocAssessRunner.PARSER_VERSION, configHash);
            if (parseRunId == null) {
                parseRunId = repo.insertParseRun(sourceFileId, mandateId,
                        DocAssessRunner.PARSER_VERSION, configHash, startedAt, startedAt,
                        "running", null);
            }
            repo.deleteStageStats(parseRunId, STAGE);
        }

        assessment.upsertAssessmentRun(new AssessmentRepository.AssessmentRunRecord(
                mandateId, STAGE, StageStatus.RUNNING, 0L, parseRunId,
                startedAt, null, null));

        int invalidated = snapshots.evaluateFreshness(mandateId);
        int totalSnapshots = snapshots.countSnapshots(mandateId);
        int staleSnapshots = snapshots.countStaleSnapshots(mandateId);
        int freshSnapshots = totalSnapshots - staleSnapshots;

        if (parseRunId != null) {
            repo.recordRunStat(parseRunId, STAGE, "snapshots_total", (double) totalSnapshots, null);
            repo.recordRunStat(parseRunId, STAGE, "snapshots_stale", (double) staleSnapshots, null);
            repo.recordRunStat(parseRunId, STAGE, "snapshots_fresh", (double) freshSnapshots, null);
            repo.recordRunStat(parseRunId, STAGE, "snapshots_invalidated", (double) invalidated, null);
            repo.updateParseRunResult(parseRunId, Timestamps.now(), "success", null);
        }

        ObjectNode metrics = MAPPER.createObjectNode();
        metrics.put("snapshots_total", totalSnapshots);
        metrics.put("snapshots_stale", staleSnapshots);
        metrics.put("snapshots_fresh", freshSnapshots);
        metrics.put("snapshots_invalidated", invalidated);

        assessment.upsertAssessmentRun(new AssessmentRepository.AssessmentRunRecord(
                mandateId, STAGE, StageOutcome.COMPLETED, 0L, parseRunId,
                startedAt, Timestamps.now(), metrics.toString()));

        String detail = String.format(Locale.ROOT,
                "%d snapshots evaluated (%d stale, %d fresh)",
                totalSnapshots, staleSnapshots, freshSnapshots);
        return new StageResult(STAGE, totalSnapshots, detail, StageOutcome.COMPLETED, 0L);
    }
}
