package com.resurgent.tev.doc.assess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.doc.checklist.ChecklistDefinition;
import com.resurgent.tev.doc.checklist.ChecklistStore;
import com.resurgent.tev.doc.db.AssessmentRepository;
import com.resurgent.tev.doc.db.AssessmentRepository.BlockAnchor;
import com.resurgent.tev.doc.db.ChecklistRepository;
import com.resurgent.tev.doc.db.ConflictRepository;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.EntityRepository;
import com.resurgent.tev.doc.db.SiteRepository;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.query.MandateQa;
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
import java.util.Optional;

/**
 * {@code assess_checklist} (issues #143, #144): instantiates the mandate's checklist
 * from the in-scope versioned definitions (seed spine plus the store beside the
 * workspace), first-drafting an industry checklist with the model for every mandate
 * industry the catalog does not cover — an item whose basis cannot be named is
 * dropped — lets the analyst's recorded overrides add or remove items, and stores
 * one result per item and scope: Provided with page evidence, Unclear when the
 * reading is not trustworthy, Not provided with the ask-the-client wording. A data
 * point the earlier {@code assess_conflicts} stage (M3-12) left with an open
 * conflict is Conflicting — the conflict stage runs first and this stage reads its
 * rows. Not provided and Unclear raise non-blocking review_queue flags. Counts by
 * status are persisted overall, per entity and per site in {@code checklist_summary}.
 * One transaction per run; a re-run replaces the previous checklist wholesale and
 * never duplicates it.
 */
public final class AssessChecklistRunner implements AssessStage {

    /** Exact M3 stage name. */
    public static final String STAGE = "assess_checklist";

    /** Every status a summary row is persisted for, zeros included. */
    static final List<String> STATUS_ORDER = List.of(
            ChecklistAssessor.STATUS_PROVIDED,
            ChecklistAssessor.STATUS_UNCLEAR,
            ChecklistAssessor.STATUS_NOT_PROVIDED,
            ChecklistAssessor.STATUS_CONFLICTING,
            ChecklistAssessor.STATUS_NOT_APPLICABLE);

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
                StageResult result = assess(connection, mandateId, dbPath, client);
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

    private StageResult assess(Connection connection, long mandateId, Path dbPath,
            DocLlmClient client) throws Exception {
        AssessmentRepository assessment = new AssessmentRepository(connection);
        ChecklistRepository checklist = new ChecklistRepository(connection);
        DocumentRepository docs = new DocumentRepository(connection);
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
            checklist.clearFlags(parseRunId);
        }

        // The results cite this assessment run; record it as running now so the ids
        // exist, then flip it to completed with metrics when the run succeeds (a
        // failed run rolls back, leaving the previous run record untouched). The id
        // is read back — on a re-run the upsert updates the existing row and the
        // driver's generated keys are not trustworthy outside the insert branch.
        assessment.upsertAssessmentRun(mandateId, STAGE, "running", parseRunId,
                startedAt, null, null);
        Long runId = assessment.findAssessmentRunId(mandateId, STAGE);
        if (runId == null) {
            throw new SQLException("assessment_run row missing after upsert for stage " + STAGE);
        }

        Map<String, ChecklistDefinition> catalog = ChecklistStore.loadCatalogLenient(dbPath);
        List<DocLlmClient.Usage> usage = new ArrayList<>();
        List<MandateQa.IndustryRow> industries = assessment.industries(mandateId);
        MandateQa.CountryRow country = assessment.country(mandateId);
        if (client != null) {
            // M3-11: first-draft an industry checklist for every mandate industry the
            // in-scope catalog does not cover. The draft is published to the store, so
            // every later run of the mandate reuses it and asks the model nothing.
            for (ChecklistDefinition drafted : IndustryChecklistDrafter.draftMissing(
                    industries, country, dbPath, catalog.values(), client, usage::add)) {
                catalog.put(drafted.code(), drafted);
            }
        }
        ChecklistAssessor.Inputs inputs = new ChecklistAssessor.Inputs(
                new EntityRepository(connection).entities(mandateId),
                new SiteRepository(connection).sitesForMandate(mandateId),
                checklist.documentScopes(mandateId),
                checklist.facts(mandateId),
                industries,
                country);
        List<MandateQa.ChecklistOverrideRow> overrides = checklist.overrides(mandateId);
        // The conflicts stage ran first (M3-12): a data point it left open — no rule,
        // no AI settlement — is what makes its checklist item Conflicting.
        List<MandateQa.ConflictRow> openConflicts = new ConflictRepository(connection)
                .conflicts(mandateId).stream()
                .filter(row -> ConflictRepository.SETTLEMENT_OPEN.equals(row.settlement()))
                .toList();
        List<ChecklistAssessor.Result> plan = ChecklistAssessor.applyOpenConflicts(
                ChecklistAssessor.plan(inputs, catalog.values(), overrides), openConflicts);
        if (client != null) {
            // M3-11: the model decides Not applicable from the documents, always with
            // the reason and the page — nobody marks it by hand. Conflicting items are
            // never targets: the open conflict is the stronger signal.
            plan = ChecklistNotApplicableScan.apply(plan, mandateId, assessment, client,
                    usage::add);
        }

        // Replace semantics: drop the previous items, results, their provenance and the
        // summaries first, so the new inserts can never be deleted by the same run.
        // Overrides survive — they are analysis input, not results.
        checklist.clearChecklist(mandateId);

        Map<String, Long> itemIds = new LinkedHashMap<>();
        List<Long> resultIds = new ArrayList<>();
        int provided = 0;
        int unclear = 0;
        int notProvided = 0;
        int notApplicable = 0;
        int conflicting = 0;
        for (ChecklistAssessor.Result result : plan) {
            Long itemId = itemIds.get(result.item().key());
            if (itemId == null) {
                itemId = checklist.insertItem(mandateId,
                        result.item().key(), result.item().label(), result.checklistCode(),
                        result.checklistVersion(), result.definitionScope(), result.country(),
                        result.industry(), result.item().level(), result.item().granularity(),
                        result.item().entityRole(), result.item().documentType(),
                        result.item().fieldKey(), result.item().ontologyPath(),
                        result.item().mandatory(), result.item().basisKind(),
                        result.item().basisText(), result.item().origin(),
                        result.item().clientRequest(), result.note());
                itemIds.put(result.item().key(), itemId);
            }
            long resultId = checklist.insertResult(itemId, runId, result.entityId(),
                    result.siteId(), result.status(), result.reason(),
                    result.documentVersionId(), result.pageIndex(), result.confidence(),
                    result.clientRequest());
            resultIds.add(resultId);
            switch (result.status()) {
                case ChecklistAssessor.STATUS_PROVIDED -> {
                    provided++;
                    insertProvidedProvenance(assessment, docs, checklist, result, resultId);
                }
                case ChecklistAssessor.STATUS_UNCLEAR -> unclear++;
                case ChecklistAssessor.STATUS_NOT_PROVIDED -> notProvided++;
                case ChecklistAssessor.STATUS_NOT_APPLICABLE -> notApplicable++;
                case ChecklistAssessor.STATUS_CONFLICTING -> conflicting++;
                default -> notProvided++;
            }
        }

        insertSummaries(checklist, mandateId, runId, plan);
        int[] flags = insertFlags(repo, checklist, parseRunId, plan);

        recordStats(repo, parseRunId, itemIds.size(), plan.size(), provided, unclear,
                notProvided, notApplicable, conflicting, flags, usage);
        assessment.upsertAssessmentRun(mandateId, STAGE, "completed", parseRunId, startedAt,
                Timestamps.now(), metricsJson(itemIds.size(), plan.size(), provided, unclear,
                        notProvided, notApplicable, conflicting, flags, usage));

        String detail = String.format(Locale.ROOT,
                "%d items, %d results (%d provided, %d unclear, %d not provided,"
                        + " %d not applicable, %d conflicting), %d flags",
                itemIds.size(), plan.size(), provided, unclear, notProvided, notApplicable,
                conflicting, flags[0] + flags[1]);
        return new StageResult(STAGE, plan.size(), detail);
    }

    /** The provenance row of a Provided result — the cite behind the status. */
    private static void insertProvidedProvenance(AssessmentRepository assessment,
            DocumentRepository docs, ChecklistRepository checklist,
            ChecklistAssessor.Result result, long resultId) throws Exception {
        Optional<BlockAnchor> anchor = assessment.findBlockAnchor(
                result.documentVersionId(), result.evidenceVerbatim());
        long provenanceId;
        if (anchor.isPresent()) {
            BlockAnchor block = anchor.get();
            provenanceId = docs.insertProvenance(ChecklistRepository.PROVENANCE_ENTITY_TYPE,
                    resultId, result.documentVersionId(), block.pageId(), block.blockId(),
                    null, null, block.bboxX0(), block.bboxY0(), block.bboxX1(), block.bboxY1(),
                    null, null, result.evidenceVerbatim(), result.confidence());
        } else {
            Long pageId = result.pageIndex() == null || result.documentVersionId() == null
                    ? null
                    : checklist.pageId(result.documentVersionId(), result.pageIndex());
            provenanceId = docs.insertProvenance(ChecklistRepository.PROVENANCE_ENTITY_TYPE,
                    resultId, result.documentVersionId(), pageId, null, null, null,
                    null, null, null, null, null, null,
                    result.evidenceVerbatim(), result.confidence());
        }
        checklist.attachProvenance(resultId, provenanceId);
    }

    /** Persists the counts by status: overall, per entity and per site, zeros included. */
    private static void insertSummaries(ChecklistRepository checklist, long mandateId,
            long runId, List<ChecklistAssessor.Result> plan) throws SQLException {
        Map<String, Integer> overall = counts(plan, null, null, false);
        for (String status : STATUS_ORDER) {
            checklist.insertSummary(mandateId, runId, "overall", null, null, status,
                    overall.getOrDefault(status, 0));
        }
        Map<Long, String> entityIds = new LinkedHashMap<>();
        Map<Long, String> siteIds = new LinkedHashMap<>();
        for (ChecklistAssessor.Result result : plan) {
            if (result.entityId() != null) {
                entityIds.putIfAbsent(result.entityId(), result.entityName());
            }
            if (result.siteId() != null) {
                siteIds.putIfAbsent(result.siteId(), result.siteName());
            }
        }
        for (Long entityId : entityIds.keySet()) {
            Map<String, Integer> byStatus = counts(plan, entityId, null, true);
            for (String status : STATUS_ORDER) {
                checklist.insertSummary(mandateId, runId, "entity", entityId, null, status,
                        byStatus.getOrDefault(status, 0));
            }
        }
        for (Long siteId : siteIds.keySet()) {
            Map<String, Integer> byStatus = counts(plan, null, siteId, false);
            for (String status : STATUS_ORDER) {
                checklist.insertSummary(mandateId, runId, "site", null, siteId, status,
                        byStatus.getOrDefault(status, 0));
            }
        }
    }

    private static Map<String, Integer> counts(List<ChecklistAssessor.Result> plan,
            Long entityId, Long siteId, boolean byEntity) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ChecklistAssessor.Result result : plan) {
            boolean match = byEntity
                    ? entityId.equals(result.entityId())
                    : siteId == null || siteId.equals(result.siteId());
            if (match) {
                counts.merge(result.status(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * The non-blocking review_queue flags: one per Not provided result (each unmet
     * item is one ask), one per unreliable (document version, scope) group for
     * Unclear results — the analyst re-checks the reading, not every item on it.
     *
     * @return {@code [notProvidedFlags, unclearFlags]}
     */
    private static int[] insertFlags(WorkspaceRepository repo, ChecklistRepository checklist,
            Long parseRunId, List<ChecklistAssessor.Result> plan) throws Exception {
        if (parseRunId == null) {
            return new int[] {0, 0};
        }
        int notProvided = 0;
        for (ChecklistAssessor.Result result : plan) {
            if (!ChecklistAssessor.STATUS_NOT_PROVIDED.equals(result.status())) {
                continue;
            }
            repo.insertReviewQueue(parseRunId, ChecklistRepository.FLAG_NOT_PROVIDED,
                    "Checklist item not provided: " + result.item().label() + flagScope(result),
                    flagDetail(result), "Pending", false, Timestamps.now(), null);
            notProvided++;
        }
        Map<String, List<ChecklistAssessor.Result>> unclearGroups = new LinkedHashMap<>();
        for (ChecklistAssessor.Result result : plan) {
            if (ChecklistAssessor.STATUS_UNCLEAR.equals(result.status())) {
                String key = result.documentVersionId() + "|" + result.entityId() + "|"
                        + result.siteId();
                unclearGroups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(result);
            }
        }
        for (List<ChecklistAssessor.Result> group : unclearGroups.values()) {
            ChecklistAssessor.Result first = group.get(0);
            StringBuilder summary = new StringBuilder("Checklist evidence unclear")
                    .append(flagScope(first));
            if (first.documentFileName() != null) {
                summary.append(" in ").append(first.documentFileName());
            }
            summary.append(": ");
            for (int i = 0; i < group.size(); i++) {
                if (i > 0) {
                    summary.append(", ");
                }
                summary.append(group.get(i).item().label());
            }
            repo.insertReviewQueue(parseRunId, ChecklistRepository.FLAG_UNCLEAR,
                    summary.toString(), flagDetail(first), "Pending", false,
                    Timestamps.now(), null);
        }
        return new int[] {notProvided, unclearGroups.size()};
    }

    private static String flagScope(ChecklistAssessor.Result result) {
        if (result.entityName() != null) {
            return " (" + result.entityName() + ")";
        }
        return result.siteName() != null ? " (site " + result.siteName() + ")" : "";
    }

    private static String flagDetail(ChecklistAssessor.Result result) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("item_key", result.item().key());
        node.put("level", result.item().level());
        node.put("granularity", result.item().granularity());
        node.put("document_type", result.item().documentType());
        if (result.entityId() != null) {
            node.put("entity_id", result.entityId());
        }
        if (result.siteId() != null) {
            node.put("site_id", result.siteId());
        }
        if (result.documentVersionId() != null) {
            node.put("document_version_id", result.documentVersionId());
        }
        node.put("status", result.status());
        node.put("reason", result.reason());
        if (result.clientRequest() != null) {
            node.put("client_request", result.clientRequest());
        }
        return node.toString();
    }

    private static void recordStats(WorkspaceRepository repo, Long parseRunId, int items,
            int results, int provided, int unclear, int notProvided, int notApplicable,
            int conflicting, int[] flags, List<DocLlmClient.Usage> usage) throws Exception {
        if (parseRunId == null) {
            return;
        }
        repo.recordRunStat(parseRunId, STAGE, "checklist_items", (double) items, null);
        repo.recordRunStat(parseRunId, STAGE, "checklist_results", (double) results, null);
        repo.recordRunStat(parseRunId, STAGE, "checklist_provided", (double) provided, null);
        repo.recordRunStat(parseRunId, STAGE, "checklist_unclear", (double) unclear, null);
        repo.recordRunStat(parseRunId, STAGE, "checklist_not_provided", (double) notProvided, null);
        repo.recordRunStat(parseRunId, STAGE, "checklist_conflicting", (double) conflicting, null);
        repo.recordRunStat(parseRunId, STAGE, "checklist_not_applicable",
                (double) notApplicable, null);
        repo.recordRunStat(parseRunId, STAGE, "flags_not_provided", (double) flags[0], null);
        repo.recordRunStat(parseRunId, STAGE, "flags_unclear", (double) flags[1], null);
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

    private static String metricsJson(int items, int results, int provided, int unclear,
            int notProvided, int notApplicable, int conflicting, int[] flags,
            List<DocLlmClient.Usage> usage) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("items", items);
        node.put("results", results);
        node.put("provided", provided);
        node.put("unclear", unclear);
        node.put("not_provided", notProvided);
        node.put("not_applicable", notApplicable);
        node.put("conflicting", conflicting);
        node.put("flags_not_provided", flags[0]);
        node.put("flags_unclear", flags[1]);
        node.put("llm_calls", usage.stream().mapToInt(DocLlmClient.Usage::calls).sum());
        return node.toString();
    }
}
