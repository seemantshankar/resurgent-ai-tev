package com.resurgent.tev.doc.induce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.doc.assess.ChecklistAssessor;
import com.resurgent.tev.doc.checklist.ChecklistDefinition;
import com.resurgent.tev.doc.checklist.ChecklistLoader;
import com.resurgent.tev.doc.checklist.ChecklistStore;
import com.resurgent.tev.doc.db.AssessmentRepository;
import com.resurgent.tev.doc.db.ChecklistRepository;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checklist learning from client document sets (M3-15, issue #148): reads one or
 * more mandate workspaces — their recorded country and industries and the
 * document types their client document sets provided — and lets {@link
 * ChecklistLearner} reshape the versioned checklist definitions. Each change is
 * published as a NEW definition version in every input workspace's checklist
 * store (the pack-store precedent: never overwrite, what is assessed is exactly
 * what was written), so re-assessing any of the mandates uses it. The
 * project-type independence judgement runs through {@link
 * ProjectTypeIndependenceJudge}; a null client keeps the deterministic offline
 * path, which never promotes anything to the spine.
 *
 * <p>Persists like {@link DocInduceRunner}: one {@code parse_run} per input
 * workspace (stage {@code induce-checklists}), {@code llm_usage}, {@code
 * run_stat} counters, one {@code checklist_learned} audit entry per change with
 * its source mandates and reason, and a summary entry. There is no human
 * approval step: the system decides and records why.
 */
public final class DocInduceChecklistsRunner {

    /** Exact stage id for run stats and LLM usage of checklist learning. */
    public static final String STAGE = "induce-checklists";

    private static final String CONFIG_HASH = "induce-checklists:learning";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DocInduceChecklistsRunner() {}

    /**
     * One mandate workspace the learning reads from.
     *
     * @param mandateId the mandate scope in that database
     * @param dbPath the workspace database path
     */
    public record MandateWorkspace(long mandateId, Path dbPath) {}

    /**
     * The learning outcome, ready for printing.
     *
     * @param mandatesRead number of input workspaces read
     * @param updates one update per definition that changed
     * @param judgements the project-type judgements made (empty when none were needed)
     * @param publishedFiles the versioned files written, one per update per store
     */
    public record Result(
            int mandatesRead,
            List<ChecklistLearner.DefinitionUpdate> updates,
            List<ProjectTypeIndependenceJudge.Judgement> judgements,
            List<Path> publishedFiles) {

        public Result {
            updates = updates == null ? List.of() : List.copyOf(updates);
            judgements = judgements == null ? List.of() : List.copyOf(judgements);
            publishedFiles = publishedFiles == null ? List.of() : List.copyOf(publishedFiles);
        }
    }

    /**
     * Reads the input workspaces, learns, publishes the new versions and persists
     * the run state into every input workspace.
     *
     * @param workspaces the input mandate workspaces (required, non-empty)
     * @param client document LLM seam for the independence judgement, or null for
     *     the offline path (never promotes to the spine)
     * @param config the learning thresholds (required)
     * @return the learning outcome
     * @throws Exception on a failed read, publish or persist
     */
    public static Result run(List<MandateWorkspace> workspaces, DocLlmClient client,
            ChecklistLearner.Config config) throws Exception {
        if (workspaces.isEmpty()) {
            throw new IllegalArgumentException("checklist learning needs at least one workspace");
        }
        Set<MandateWorkspace> distinct = new LinkedHashSet<>();
        for (MandateWorkspace workspace : workspaces) {
            if (workspace.mandateId() <= 0 || !java.nio.file.Files.isRegularFile(workspace.dbPath())) {
                throw new IllegalArgumentException("expected an existing mandate workspace: " + workspace);
            }
            distinct.add(new MandateWorkspace(workspace.mandateId(), workspace.dbPath().toRealPath()));
        }
        workspaces = List.copyOf(distinct);
        List<ChecklistLearner.MandateObservation> observations = readObservations(workspaces);
        Map<String, ChecklistDefinition> catalog = loadCatalog(workspaces);

        List<DocLlmClient.Usage> usage = new ArrayList<>();
        ProjectTypeIndependenceJudge judge = client == null
                ? new FixtureProjectTypeIndependenceJudge()
                : new LlmProjectTypeIndependenceJudge(client, usage::add);
        System.err.printf("[doc-induce] checklist learning from %d mandate(s) via %s%n",
                workspaces.size(), judge.id());
        ChecklistLearner.Outcome outcome = ChecklistLearner.learn(
                new ChecklistLearner.Inputs(observations, List.copyOf(catalog.values())),
                judge, config);
        List<Path> published = publish(workspaces, outcome.updates());

        persist(workspaces, outcome, published, usage, client != null, config);
        return new Result(workspaces.size(), outcome.updates(), outcome.judgements(), published);
    }

    /** One observation per workspace: the country, industries and client-set types. */
    private static List<ChecklistLearner.MandateObservation> readObservations(
            List<MandateWorkspace> workspaces) throws Exception {
        List<ChecklistLearner.MandateObservation> observations = new ArrayList<>();
        for (MandateWorkspace workspace : workspaces) {
            try (WorkspaceDatabase db = WorkspaceDatabase.open(workspace.dbPath())) {
                Connection connection = db.connection();
                AssessmentRepository assessment = new AssessmentRepository(connection);
                ChecklistRepository checklists = new ChecklistRepository(connection);
                String country = ChecklistAssessor.countryCode(
                        assessment.country(workspace.mandateId()));
                List<String> industries = assessment.industries(workspace.mandateId()).stream()
                        .map(MandateQa.IndustryRow::industry)
                        .filter(industry -> industry != null && !industry.isBlank())
                        .toList();
                Set<String> documentTypes = new LinkedHashSet<>();
                for (ChecklistRepository.DocScopeRow document
                        : checklists.documentScopes(workspace.mandateId())) {
                    if (document.docTypeCode() != null && !document.docTypeCode().isBlank()) {
                        documentTypes.add(document.docTypeCode());
                    }
                }
                observations.add(new ChecklistLearner.MandateObservation(
                        new ChecklistLearner.SourceMandate(workspace.dbPath().toString(),
                                workspace.mandateId()), country, industries,
                        List.copyOf(documentTypes)));
            }
        }
        return observations;
    }

    /**
     * The current catalog across the distinct stores beside the input workspaces:
     * the seed spine, then each store's definitions in workspace order (the
     * highest version within a store, later stores override by code — the
     * replacement discipline of {@link ChecklistStore#loadCatalog}).
     */
    private static Map<String, ChecklistDefinition> loadCatalog(List<MandateWorkspace> workspaces)
            throws Exception {
        List<ChecklistDefinition> definitions = new ArrayList<>();
        definitions.add(ChecklistLoader.loadSeedSpine());
        for (Path root : storeRoots(workspaces)) {
            definitions.addAll(new ChecklistStore(root).loadAll());
        }
        return ChecklistStore.latestPerCode(definitions);
    }

    /**
     * Publishes every update as a new version into every distinct store, so each
     * input workspace can assess against it. What was written is loaded back — a
     * version that fails validation never counts as published.
     */
    private static List<Path> publish(List<MandateWorkspace> workspaces,
            List<ChecklistLearner.DefinitionUpdate> updates) throws Exception {
        List<Path> published = new ArrayList<>();
        for (ChecklistLearner.DefinitionUpdate update : updates) {
            for (Path root : storeRoots(workspaces)) {
                Path file = new ChecklistStore(root).publishVersioned(update.definition());
                ChecklistLoader.load(file);
                published.add(file);
                System.err.printf("[doc-induce] learned checklist %s %s -> %s (%d change(s))%n",
                        update.code(), update.previousVersion(), update.newVersion(),
                        update.changes().size());
            }
        }
        return List.copyOf(published);
    }

    /** The distinct checklist store roots beside the input workspaces, in order. */
    private static List<Path> storeRoots(List<MandateWorkspace> workspaces) {
        Set<Path> roots = new LinkedHashSet<>();
        for (MandateWorkspace workspace : workspaces) {
            roots.add(ChecklistStore.storeRoot(workspace.dbPath()));
        }
        return List.copyOf(roots);
    }

    /**
     * Persists the outcome into every input workspace: its {@code induce-checklists}
     * parse run, the usage/stat rows, one {@code checklist_learned} audit entry per
     * change and the summary entry. Re-running replaces the stage rows.
     */
    private static void persist(List<MandateWorkspace> workspaces,
            ChecklistLearner.Outcome outcome, List<Path> published, List<DocLlmClient.Usage> usage,
            boolean liveModel, ChecklistLearner.Config config) throws Exception {
        Map<String, DocLlmClient.Usage> totals = new LinkedHashMap<>();
        for (DocLlmClient.Usage total : usage) {
            totals.merge(total.modelId(), total, DocLlmClient.Usage::plus);
        }
        for (MandateWorkspace workspace : workspaces) {
            try (WorkspaceDatabase db = WorkspaceDatabase.open(workspace.dbPath())) {
                Connection connection = db.connection();
                connection.setAutoCommit(false);
                try {
                    persistWorkspace(connection, workspace, outcome, published, totals.values(),
                            liveModel, workspaces.size(), config);
                    connection.commit();
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
    }

    private static void persistWorkspace(Connection connection, MandateWorkspace workspace,
            ChecklistLearner.Outcome outcome, List<Path> published,
            Iterable<DocLlmClient.Usage> totals, boolean liveModel, int mandatesRead,
            ChecklistLearner.Config config) throws Exception {
        WorkspaceRepository repo = new WorkspaceRepository(connection);
        AssessmentRepository assessment = new AssessmentRepository(connection);
        Long sourceFileId = assessment.anchorSourceFileId(workspace.mandateId());
        if (sourceFileId == null || sourceFileId <= 0) {
            return;
        }
        Long existing = repo.findParseRunId(sourceFileId, DocInduceRunner.PARSER_VERSION,
                CONFIG_HASH);
        long parseRunId = existing != null ? existing
                : repo.insertParseRun(sourceFileId, workspace.mandateId(),
                        DocInduceRunner.PARSER_VERSION, CONFIG_HASH,
                        Timestamps.now(), Timestamps.now(), "running", null);
        repo.deleteStageStats(parseRunId, STAGE);

        for (DocLlmClient.Usage total : totals) {
            repo.recordLlmUsage(parseRunId, STAGE, total.modelId(), total.calls(),
                    total.failedCalls(), 0, total.promptTokens(), total.completionTokens(),
                    total.costUsd(), total.costUsd() == null ? 1 : 0, total.latencyMsTotal());
        }
        int added = 0;
        int madeOptional = 0;
        int promoted = 0;
        for (ChecklistLearner.DefinitionUpdate update : outcome.updates()) {
            for (ChecklistLearner.ItemChange change : update.changes()) {
                switch (change.kind()) {
                    case "added" -> added++;
                    case "made_optional" -> madeOptional++;
                    case "promoted" -> promoted++;
                    default -> {
                        // future kinds still get their audit entry
                    }
                }
                repo.insertAuditLog(parseRunId, "checklist_learned", Timestamps.now(),
                        learnedPayload(update, change), "info");
            }
        }
        boolean judged = !outcome.judgements().isEmpty();
        repo.recordRunStat(parseRunId, STAGE, "mandates_read", 1.0, null);
        repo.recordRunStat(parseRunId, STAGE, "definitions_updated",
                (double) outcome.updates().size(), null);
        repo.recordRunStat(parseRunId, STAGE, "items_added", (double) added, null);
        repo.recordRunStat(parseRunId, STAGE, "items_made_optional", (double) madeOptional, null);
        repo.recordRunStat(parseRunId, STAGE, "items_spine_promoted", (double) promoted, null);
        repo.recordRunStat(parseRunId, STAGE, "llm_calls",
                (double) (liveModel && judged ? 1 : 0), null);
        repo.insertAuditLog(parseRunId, "checklist_learning_summary", Timestamps.now(),
                summaryPayload(workspace, outcome, published, liveModel, mandatesRead,
                        config), "info");
        repo.updateParseRunResult(parseRunId, Timestamps.now(),
                outcome.updates().isEmpty() ? "partial" : "success",
                "{\"mandate\":" + workspace.mandateId()
                        + ",\"live_model\":" + liveModel
                        + ",\"updates\":" + outcome.updates().size()
                        + ",\"changes\":" + totalChanges(outcome) + "}");
    }

    /** Audit payload: what changed, into which version, from which mandates, why. */
    private static String learnedPayload(ChecklistLearner.DefinitionUpdate update,
            ChecklistLearner.ItemChange change) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("definition", update.code());
        node.put("definition_kind", update.kind());
        node.put("previous_version", update.previousVersion());
        node.put("new_version", update.newVersion());
        node.put("change", change.kind());
        node.put("item_key", change.itemKey());
        node.put("document_type", change.documentType());
        ArrayNode mandates = node.putArray("source_mandates");
        for (ChecklistLearner.SourceMandate source : change.sourceMandates()) {
            mandates.addObject().put("workspace", source.workspace())
                    .put("mandate_id", source.mandateId());
        }
        node.put("reason", change.reason());
        return node.toString();
    }

    /** Audit payload: the run in one line — counts, judgements and published files. */
    private static String summaryPayload(MandateWorkspace workspace,
            ChecklistLearner.Outcome outcome, List<Path> published, boolean liveModel,
            int mandatesRead, ChecklistLearner.Config config) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("mandate", workspace.mandateId());
        node.put("mandates_read", mandatesRead);
        ObjectNode thresholds = node.putObject("thresholds");
        thresholds.put("min_observed_mandates", config.minObservedMandates());
        thresholds.put("min_missing_mandates", config.minMissingMandates());
        thresholds.put("min_industries_for_spine", config.minIndustriesForSpine());
        node.put("live_model", liveModel);
        node.put("definitions_updated", outcome.updates().size());
        node.put("changes", totalChanges(outcome));
        ArrayNode judgements = node.putArray("judgements");
        for (ProjectTypeIndependenceJudge.Judgement judgement : outcome.judgements()) {
            judgements.add(judgement.documentType() + ": "
                    + (judgement.independent() ? "independent" : "not independent")
                    + " — " + judgement.reason());
        }
        ArrayNode files = node.putArray("published_files");
        for (Path file : published) {
            files.add(file.toAbsolutePath().toString());
        }
        return node.toString();
    }

    private static int totalChanges(ChecklistLearner.Outcome outcome) {
        int changes = 0;
        for (ChecklistLearner.DefinitionUpdate update : outcome.updates()) {
            changes += update.changes().size();
        }
        return changes;
    }
}
