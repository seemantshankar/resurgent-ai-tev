package com.resurgent.tev.doc.assess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.doc.assess.ConflictScan.Written;
import com.resurgent.tev.doc.db.AssessmentRepository;
import com.resurgent.tev.doc.db.ConflictRepository;
import com.resurgent.tev.doc.db.ConflictRepository.ConflictWrite;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code assess_conflicts} (issue #145, ADR 0026): finds the disagreements between the
 * mandate's documents, stores one {@code conflict} row per disagreement — both sources
 * and values kept in full — and settles each by precedence rule or AI judgement, or
 * leaves it open. A conflict left open raises a {@code conflicting} review flag (and
 * the checklist shows the item Conflicting); an AI-settled conflict is flagged as
 * settled by AI with no action required. Revisions are stored as {@code versions-differ}
 * with no settlement. One transaction per run; a re-run replaces the previous conflict
 * rows and flags wholesale and never duplicates them.
 */
public final class AssessConflictsRunner implements AssessStage {

    /** Exact M3 stage name. */
    public static final String STAGE = "assess_conflicts";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return STAGE;
    }

    @Override
    public StageResult run(long mandateId, Path dbPath, DocLlmClient client) throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(dbPath)) {
            Connection connection = workspace.connection();
            connection.setAutoCommit(false);
            try {
                StageResult result = assess(connection, mandateId, client);
                connection.commit();
                return result;
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (Exception ignored) {
                    // keep the original failure
                }
                throw e;
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (Exception ignored) {
                    // connection may already be released
                }
            }
        }
    }

    private StageResult assess(Connection connection, long mandateId, DocLlmClient client)
            throws Exception {
        AssessmentRepository assessment = new AssessmentRepository(connection);
        ConflictRepository conflicts = new ConflictRepository(connection);
        FactRepository facts = new FactRepository(connection);
        WorkspaceRepository repo = new WorkspaceRepository(connection);

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
            facts.deleteReviewQueueCategory(parseRunId, ConflictRepository.FLAG_CONFLICTING);
            facts.deleteReviewQueueCategory(parseRunId, ConflictRepository.FLAG_SETTLED_BY_AI);
        }

        // The rows cite this assessment run; record it as running now so the id exists,
        // then flip it to completed with metrics when the run succeeds.
        assessment.upsertAssessmentRun(mandateId, STAGE, "running", parseRunId,
                startedAt, null, null);
        Long runId = assessment.findAssessmentRunId(mandateId, STAGE);
        if (runId == null) {
            throw new SQLException("assessment_run row missing after upsert for stage " + STAGE);
        }

        // Replace semantics: drop the mandate's previous conflict rows first, so the
        // new inserts can never be deleted by the same run.
        conflicts.clearConflicts(mandateId);

        List<DocLlmClient.Usage> usage = new ArrayList<>();
        List<Written> written = ConflictScan.scan(connection, mandateId, runId, client,
                usage::add);

        int docDoc = 0;
        int versionsDiffer = 0;
        int settledRule = 0;
        int settledAi = 0;
        int open = 0;
        for (Written row : written) {
            if (ConflictRepository.KIND_VERSIONS_DIFFER.equals(row.write().kind())) {
                versionsDiffer++;
            } else {
                docDoc++;
            }
            switch (row.write().settlement() == null ? "" : row.write().settlement()) {
                case ConflictRepository.SETTLEMENT_RULE -> settledRule++;
                case ConflictRepository.SETTLEMENT_AI -> settledAi++;
                case ConflictRepository.SETTLEMENT_OPEN -> open++;
                default -> { }
            }
        }

        insertFlags(repo, parseRunId, written);
        recordStats(repo, parseRunId, usage, docDoc, versionsDiffer, settledRule, settledAi, open);
        assessment.upsertAssessmentRun(mandateId, STAGE, "completed", parseRunId, startedAt,
                Timestamps.now(), metricsJson(docDoc, versionsDiffer, settledRule, settledAi,
                        open, usage));

        String detail = String.format(Locale.ROOT,
                "%d conflicts (%d rule, %d ai, %d open), %d versions differ",
                docDoc, settledRule, settledAi, open, versionsDiffer);
        return new StageResult(STAGE, written.size(), detail);
    }

    /**
     * The review flags: one per open conflict ({@code conflicting}, Pending — the
     * checklist shows the item Conflicting) and one per AI-settled conflict
     * ({@code settled_by_ai}, Resolved — recorded so the output shows it, no action
     * follows). Rule-settled rows and revisions raise nothing.
     */
    private static void insertFlags(WorkspaceRepository repo, Long parseRunId,
            List<Written> written) throws Exception {
        if (parseRunId == null) {
            return;
        }
        for (Written row : written) {
            ConflictWrite write = row.write();
            if (ConflictRepository.SETTLEMENT_OPEN.equals(write.settlement())) {
                repo.insertReviewQueue(parseRunId, ConflictRepository.FLAG_CONFLICTING,
                        "Conflict on " + write.dataPoint() + ": " + write.leftValue()
                                + " vs " + write.rightValue(),
                        flagDetail(row), "Pending", false, Timestamps.now(), null);
            } else if (ConflictRepository.SETTLEMENT_AI.equals(write.settlement())) {
                repo.insertReviewQueue(parseRunId, ConflictRepository.FLAG_SETTLED_BY_AI,
                        "Conflict on " + write.dataPoint() + " settled by AI: "
                                + write.chosenValue() + " used, " + write.rejectedValue()
                                + " rejected",
                        flagDetail(row), "Resolved", false, Timestamps.now(), Timestamps.now());
            }
        }
    }

    private static String flagDetail(Written row) {
        ConflictWrite write = row.write();
        ObjectNode node = MAPPER.createObjectNode();
        node.put("conflict_id", row.conflictId());
        node.put("kind", write.kind());
        node.put("data_point", write.dataPoint());
        node.put("verdict", write.verdict());
        node.put("left_value", write.leftValue());
        node.put("right_value", write.rightValue());
        if (write.settlement() != null) {
            node.put("settlement", write.settlement());
        }
        if (write.chosenSide() != null) {
            node.put("chosen_side", write.chosenSide());
        }
        if (write.ruleId() != null) {
            node.put("rule_id", write.ruleId());
        }
        if (write.entityId() != null) {
            node.put("entity_id", write.entityId());
        }
        if (write.siteId() != null) {
            node.put("site_id", write.siteId());
        }
        node.put("reasoning", write.reasoning());
        return node.toString();
    }

    private static void recordStats(WorkspaceRepository repo, Long parseRunId,
            List<DocLlmClient.Usage> usage, int docDoc, int versionsDiffer, int settledRule,
            int settledAi, int open) throws Exception {
        if (parseRunId == null) {
            return;
        }
        repo.recordRunStat(parseRunId, STAGE, "conflicts_total", (double) docDoc, null);
        repo.recordRunStat(parseRunId, STAGE, "versions_differ", (double) versionsDiffer, null);
        repo.recordRunStat(parseRunId, STAGE, "settled_rule", (double) settledRule, null);
        repo.recordRunStat(parseRunId, STAGE, "settled_ai", (double) settledAi, null);
        repo.recordRunStat(parseRunId, STAGE, "open", (double) open, null);
        repo.recordRunStat(parseRunId, STAGE, "llm_calls",
                (double) usage.stream().mapToInt(DocLlmClient.Usage::calls).sum(), null);

        Map<String, DocLlmClient.Usage> totals = new LinkedHashMap<>();
        for (DocLlmClient.Usage u : usage) {
            totals.merge(u.modelId(), u, DocLlmClient.Usage::plus);
        }
        for (DocLlmClient.Usage modelTotal : totals.values()) {
            repo.recordLlmUsage(parseRunId, STAGE, modelTotal.modelId(), modelTotal.calls(),
                    modelTotal.failedCalls(), 0, modelTotal.promptTokens(),
                    modelTotal.completionTokens(), modelTotal.costUsd(),
                    modelTotal.costUsd() == null ? 1 : 0, modelTotal.latencyMsTotal());
        }
        repo.updateParseRunResult(parseRunId, Timestamps.now(), "success", null);
    }

    private static String metricsJson(int docDoc, int versionsDiffer, int settledRule,
            int settledAi, int open, List<DocLlmClient.Usage> usage) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("conflicts_total", docDoc);
        node.put("versions_differ", versionsDiffer);
        node.put("settled_rule", settledRule);
        node.put("settled_ai", settledAi);
        node.put("open", open);
        node.put("llm_calls", usage.stream().mapToInt(DocLlmClient.Usage::calls).sum());
        return node.toString();
    }
}
