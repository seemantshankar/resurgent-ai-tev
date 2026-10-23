package com.resurgent.tev.doc.induce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.doc.db.ClauseRepository;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.InductionDecisionRepository;
import com.resurgent.tev.doc.embed.Cosine;
import com.resurgent.tev.doc.embed.EmbeddingRepository;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.CostUnit;
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
 * Clause-type induction (M3-09, spec "Auto-promotion"): reads the open clause proposals
 * Pass B left behind, groups their labels with {@link ClauseLabelNormalizer}, and lets
 * {@link ClauseTypePromoter} publish the recurring groups as a new pack version — the
 * same induction and quality gates as field induction, and no human approval step
 * (architecture §9 Pass C is superseded). Runs in the induction command family
 * ({@code tev-doc induce-clauses}); a null client keeps the deterministic offline path.
 *
 * <p>Persists like {@link DocInduceRunner}: one {@code parse_run} per document type
 * (stage {@code induce-clauses}), {@code llm_usage}, {@code run_stat} counters, a
 * {@code doc_type_pack} row for a promoted version, an audit entry per promoted type
 * recording the source documents, and the proposal rows flipped to {@code promoted}.
 */
public final class DocInduceClausesRunner {

    /** Exact stage id for run stats and LLM usage of clause promotion. */
    public static final String STAGE = "induce-clauses";

    /** Stage value stored on {@code induction_decision} (underscore, not the run-stat hyphen). */
    static final String DECISION_STAGE = "induce_clauses";

    /** Opaque induction run id stored on the published {@code doc_type_pack} row. */
    static final String RUN_ID_PREFIX = "clause-promotion:";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DocInduceClausesRunner() {}

    /**
     * Promotes recurring open clause proposals for a mandate.
     *
     * @param mandateId mandate scope
     * @param db workspace database
     * @param client document LLM seam for label normalisation, or null for offline
     * @return one summary per document type that had open proposals, in pack-code order
     * @throws Exception on runtime failure
     */
    public static List<Result> run(long mandateId, Path db, DocLlmClient client) throws Exception {
        Path packsRoot = db.toAbsolutePath().getParent() == null
                ? Path.of("packs")
                : db.toAbsolutePath().getParent().resolve("packs");
        PackStore store = new PackStore(packsRoot);
        Map<String, DocTypePack> basePacks = loadBasePacks(packsRoot);

        List<ClauseTypePromoter.Candidate> candidates;
        Map<String, float[]> centroids;
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            candidates = loadCandidates(workspace.connection(), mandateId);
            centroids = new EmbeddingRepository(workspace.connection())
                    .openProposalCentroids(mandateId);
        }

        List<Outcome> outcomes = new ArrayList<>();
        for (ClauseTypePromoter.Candidate candidate : candidates) {
            List<DocLlmClient.Usage> usage = new ArrayList<>();
            ClauseLabelNormalizer base = client == null
                    ? new FixtureClauseLabelNormalizer()
                    : new LlmClauseLabelNormalizer(client, usage::add);
            ClauseLabelNormalizer normalizer = new CosinePreclusterNormalizer(base, centroids);
            System.err.printf("[doc-induce] clause promotion for %s (%d open proposal(s)) via %s%n",
                    candidate.docTypeCode(), candidate.proposals().size(), normalizer.id());
            ClauseTypePromoter.PromotionResult result = new ClauseTypePromoter().promote(
                    new ClauseTypePromoter.PromotionRequest(
                            mandateId, candidate, normalizer, store, basePacks,
                            Generalizer.DEFAULT_CONSENSUS_N,
                            PackQualityGates.DEFAULT_MIN_REQUIRED_COVERAGE));
            outcomes.add(new Outcome(candidate, result, usage));
        }

        persist(db, store, mandateId, outcomes, client != null);

        List<Result> results = new ArrayList<>();
        for (Outcome outcome : outcomes) {
            ClauseTypePromoter.PromotionResult result = outcome.result();
            results.add(new Result(result.docTypeCode(), result.packVersion(), result.promoted(),
                    result.skipped(), result.proposalsRead()));
        }
        return results;
    }

    private record Outcome(
            ClauseTypePromoter.Candidate candidate,
            ClauseTypePromoter.PromotionResult result,
            List<DocLlmClient.Usage> usage) {}

    /**
     * Persists each outcome: the document type's {@code induce-clauses} parse run, its
     * usage/stat rows, the {@code doc_type_pack} row for a promoted version, one
     * {@code clause_type_promoted} audit entry per learned type with its source
     * documents, and the proposal flips. Re-running replaces the stage rows.
     */
    private static void persist(Path db, PackStore store, long mandateId, List<Outcome> outcomes,
            boolean liveModel) throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            Connection connection = workspace.connection();
            WorkspaceRepository repo = new WorkspaceRepository(connection);
            DocumentRepository docs = new DocumentRepository(connection);
            ClauseRepository clauses = new ClauseRepository(connection);
            EmbeddingRepository embeddings = new EmbeddingRepository(connection);
            InductionDecisionRepository decisions = new InductionDecisionRepository(connection);
            Map<String, float[]> centroids = embeddings.openProposalCentroids(mandateId);
            connection.setAutoCommit(false);
            try {
                for (Outcome outcome : outcomes) {
                    Long sourceFileId = anchorSourceFileId(docs, outcome.candidate());
                    if (sourceFileId == null || sourceFileId <= 0) {
                        continue;
                    }
                    ClauseTypePromoter.PromotionResult result = outcome.result();
                    String docTypeCode = result.docTypeCode();
                    String configHash = "induce-clauses:" + docTypeCode;
                    Long existing = repo.findParseRunId(sourceFileId, DocInduceRunner.PARSER_VERSION,
                            configHash);
                    long parseRunId = existing != null ? existing
                            : repo.insertParseRun(sourceFileId, mandateId,
                                    DocInduceRunner.PARSER_VERSION, configHash,
                                    Timestamps.now(), Timestamps.now(), "running", null);
                    repo.deleteStageStats(parseRunId, STAGE);

                    Map<String, DocLlmClient.Usage> totals = new LinkedHashMap<>();
                    for (DocLlmClient.Usage usage : outcome.usage()) {
                        totals.merge(usage.modelId(), usage, DocLlmClient.Usage::plus);
                    }
                    for (DocLlmClient.Usage total : totals.values()) {
                        repo.recordLlmUsage(parseRunId, STAGE, total.modelId(), total.calls(),
                                total.failedCalls(), 0, total.promptTokens(),
                                total.completionTokens(), total.costUsd(), CostUnit.USD,
                                total.costUsd() == null ? 1 : 0, total.latencyMsTotal());
                    }
                    repo.recordRunStat(parseRunId, STAGE, "documents_considered",
                            (double) distinctDocuments(outcome.candidate()).size(), null);
                    repo.recordRunStat(parseRunId, STAGE, "types_proposed",
                            (double) result.proposalsRead(), null);
                    repo.recordRunStat(parseRunId, STAGE, "clause_types_promoted",
                            (double) result.promoted().size(), null);
                    repo.recordRunStat(parseRunId, STAGE, "clause_types_kept_open",
                            (double) result.skipped().size(), null);

                    for (ClauseTypePromoter.PromotedType promoted : result.promoted()) {
                        for (String memberCode : promoted.memberCodes()) {
                            clauses.markPromoted(memberCode, promoted.packCode(),
                                    promoted.packVersion());
                        }
                        repo.insertAuditLog(parseRunId, "clause_type_promoted", Timestamps.now(),
                                promotionPayload(promoted), "info");
                    }
                    if (result.packVersion() != null) {
                        recordPackRow(store, docs, mandateId,
                                result.docTypeCode(), result.packVersion());
                    }
                    recordDecisions(decisions, mandateId, parseRunId, result, centroids);
                    repo.insertAuditLog(parseRunId, "clause_promotion_summary", Timestamps.now(),
                            summaryPayload(result, liveModel), "info");
                    repo.updateParseRunResult(parseRunId, Timestamps.now(),
                            result.promoted().isEmpty() ? "partial" : "success",
                            "{\"doc_type\":\"" + docTypeCode + "\",\"live_model\":" + liveModel
                                    + ",\"promoted\":" + result.promoted().size()
                                    + ",\"kept_open\":" + result.skipped().size() + "}");
                }
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

    /** The {@code doc_type_pack} row for a promoted version, with its published YAML. */
    private static void recordPackRow(PackStore store, DocumentRepository docs, long mandateId,
            String code, String version) throws Exception {
        PackStore.PackRegistryEntry entry = null;
        for (PackStore.PackRegistryEntry candidate : store.entries()) {
            if (candidate.code().equals(code) && candidate.version().equals(version)
                    && "published".equals(candidate.status())) {
                entry = candidate;
            }
        }
        if (entry == null) {
            return;
        }
        DocTypePack pack = store.load(code, version).orElse(null);
        if (pack == null) {
            return;
        }
        docs.upsertDocTypePack(pack.code(), pack.version(), pack.family(), pack.fileRole(),
                entry.industryTag(), entry.fileHash(), entry.yamlPath(),
                "[" + mandateId + "]", RUN_ID_PREFIX + code, "published");
    }

    /** Audit payload: what was learned, into which pack version, from which documents. */
    private static String promotionPayload(ClauseTypePromoter.PromotedType promoted) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", promoted.key());
        node.put("label", promoted.label());
        node.put("pack", promoted.packCode());
        node.put("version", promoted.packVersion());
        ArrayNode documents = node.putArray("documents");
        for (String documentId : promoted.sourceDocumentIds()) {
            documents.add(documentId);
        }
        ArrayNode members = node.putArray("member_codes");
        for (String memberCode : promoted.memberCodes()) {
            members.add(memberCode);
        }
        node.put("consensus", promoted.sourceDocumentIds().size());
        return node.toString();
    }

    private static String summaryPayload(ClauseTypePromoter.PromotionResult result,
            boolean liveModel) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("doc_type", result.docTypeCode());
        node.put("proposals_read", result.proposalsRead());
        node.put("promoted", result.promoted().size());
        node.put("kept_open", result.skipped().size());
        node.put("live_model", liveModel);
        ArrayNode keptOpen = node.putArray("kept_open_types");
        for (ClauseTypePromoter.SkippedGroup skipped : result.skipped()) {
            keptOpen.add(skipped.key() + ": " + skipped.reason());
        }
        return node.toString();
    }

    private static void recordDecisions(InductionDecisionRepository decisions, long mandateId,
            long parseRunId, ClauseTypePromoter.PromotionResult result,
            Map<String, float[]> centroids) throws Exception {
        decisions.deleteForRun(parseRunId, DECISION_STAGE);
        for (ClauseTypePromoter.PromotedType promoted : result.promoted()) {
            decisions.insert(new InductionDecisionRepository.Decision(
                    null, mandateId, parseRunId, DECISION_STAGE,
                    promoted.key(), String.join(",", promoted.memberCodes()),
                    "promoted", "cluster_promoted",
                    decisionDetail(promoted.memberCodes(), promoted.sourceDocumentIds(),
                            pairSimilarity(promoted.memberCodes(), centroids),
                            "Open clauses clustered by cosine similarity and passed consensus.")));
        }
        for (ClauseTypePromoter.SkippedGroup skipped : result.skipped()) {
            decisions.insert(new InductionDecisionRepository.Decision(
                    null, mandateId, parseRunId, DECISION_STAGE,
                    skipped.key(), String.join(",", skipped.memberCodes()),
                    "dropped", reasonCode(skipped.reason()),
                    decisionDetail(skipped.memberCodes(), skipped.sourceDocumentIds(),
                            pairSimilarity(skipped.memberCodes(), centroids),
                            skipped.reason())));
        }
    }

    /** Maps a promoter skip reason onto the closed induction reason codes. */
    private static String reasonCode(String promoterReason) {
        if (promoterReason == null) {
            return "below_threshold";
        }
        String reason = promoterReason.toLowerCase(java.util.Locale.ROOT);
        if (reason.contains("entity")) {
            return "entity_token_rejected";
        }
        if (reason.contains("quality") || reason.contains("coverage")) {
            return "low_coverage";
        }
        return "below_threshold";
    }

    private static Double pairSimilarity(List<String> codes, Map<String, float[]> centroids) {
        double best = -1;
        boolean any = false;
        for (int i = 0; i < codes.size(); i++) {
            float[] left = centroids.get(codes.get(i));
            if (left == null) {
                continue;
            }
            for (int j = i + 1; j < codes.size(); j++) {
                float[] right = centroids.get(codes.get(j));
                if (right == null) {
                    continue;
                }
                any = true;
                best = Math.max(best, Cosine.similarity(left, right));
            }
        }
        return any ? best : null;
    }

    private static String decisionDetail(List<String> memberCodes, List<String> documents,
            Double similarity, String rationale) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("rationale", rationale == null ? "" : rationale);
        node.putNull("sanitized_query");
        if (similarity == null) {
            node.putNull("similarity");
        } else {
            node.put("similarity", similarity);
        }
        ArrayNode docs = node.putArray("documents");
        for (String document : documents) {
            docs.add(document);
        }
        ArrayNode members = node.putArray("member_codes");
        for (String code : memberCodes) {
            members.add(code);
        }
        return node.toString();
    }

    /**
     * Reads the open proposals, grouped into one candidate per classified document type.
     * Evidence is the mandate's open clause rows referencing each proposal, so a
     * proposal's documents are the ones its clauses were actually read from.
     */
    private static List<ClauseTypePromoter.Candidate> loadCandidates(Connection connection,
            long mandateId) throws Exception {
        Map<String, Map<String, Accum>> byDocType = new LinkedHashMap<>();
        try (var ps = connection.prepareStatement(
                "SELECT dc.doc_type_code, ct.code, ct.label, ct.parent_family,"
                        + " dv.document_version_id, COALESCE(d.logical_name, dv.file_name),"
                        + " dv.file_name"
                        + " FROM clause_type ct"
                        + " JOIN clause cl ON cl.clause_type_id = ct.clause_type_id"
                        + " JOIN document_version dv"
                        + "  ON dv.document_version_id = cl.document_version_id"
                        + " JOIN document d ON d.document_id = dv.document_id"
                        + " JOIN doc_classification dc"
                        + "  ON dc.document_version_id = dv.document_version_id"
                        + " WHERE ct.source = 'proposed' AND ct.status = 'open'"
                        + "  AND cl.status = 'open'"
                        + "  AND dc.doc_type_code IS NOT NULL"
                        + "  AND d.mandate_id = ? AND d.status != 'deleted_by_analyst'"
                        + " ORDER BY dc.doc_type_code, ct.clause_type_id, dv.document_version_id")) {
            ps.setLong(1, mandateId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    String docTypeCode = rs.getString(1);
                    String code = rs.getString(2);
                    String label = rs.getString(3);
                    String parentFamily = rs.getString(4);
                    Accum accum = byDocType
                            .computeIfAbsent(docTypeCode, k -> new LinkedHashMap<>())
                            .computeIfAbsent(code, k -> new Accum(label, parentFamily));
                    long versionId = rs.getLong(5);
                    accum.documents.putIfAbsent(versionId, new ClauseTypePromoter.SourceDocument(
                            "dv:" + versionId, rs.getString(6), rs.getString(7)));
                }
            }
        }
        List<ClauseTypePromoter.Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, Map<String, Accum>> entry : byDocType.entrySet()) {
            List<ClauseTypePromoter.OpenProposal> proposals = new ArrayList<>();
            for (Map.Entry<String, Accum> proposal : entry.getValue().entrySet()) {
                proposals.add(new ClauseTypePromoter.OpenProposal(
                        proposal.getKey(), proposal.getValue().label,
                        proposal.getValue().parentFamily,
                        List.copyOf(proposal.getValue().documents.values())));
            }
            candidates.add(new ClauseTypePromoter.Candidate(entry.getKey(), proposals));
        }
        return candidates;
    }

    /** One proposal's read state: its label/family and its distinct source documents. */
    private static final class Accum {
        private final String label;
        private final String parentFamily;
        private final Map<Long, ClauseTypePromoter.SourceDocument> documents =
                new LinkedHashMap<>();

        private Accum(String label, String parentFamily) {
            this.label = label;
            this.parentFamily = parentFamily;
        }
    }

    /** The source file the promotion stats hang on: the first document of the candidate. */
    private static Long anchorSourceFileId(DocumentRepository docs,
            ClauseTypePromoter.Candidate candidate) throws Exception {
        for (ClauseTypePromoter.OpenProposal proposal : candidate.proposals()) {
            for (ClauseTypePromoter.SourceDocument document : proposal.documents()) {
                Long versionId = versionIdOf(document.id());
                if (versionId == null) {
                    continue;
                }
                var row = docs.findVersion(versionId);
                if (row.isPresent() && row.get().sourceFileId() != null) {
                    return row.get().sourceFileId();
                }
            }
        }
        return null;
    }

    private static Long versionIdOf(String documentId) {
        if (documentId == null || !documentId.startsWith("dv:")) {
            return null;
        }
        try {
            return Long.parseLong(documentId.substring(3).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Set<String> distinctDocuments(ClauseTypePromoter.Candidate candidate) {
        Set<String> ids = new LinkedHashSet<>();
        for (ClauseTypePromoter.OpenProposal proposal : candidate.proposals()) {
            for (ClauseTypePromoter.SourceDocument document : proposal.documents()) {
                ids.add(document.id());
            }
        }
        return ids;
    }

    /** Pack-code → pack for seed packs plus packs published beside the workspace. */
    private static Map<String, DocTypePack> loadBasePacks(Path packsRoot) throws Exception {
        Map<String, DocTypePack> packs = new LinkedHashMap<>();
        for (DocTypePack pack : PackLoader.loadSeedPacks()) {
            packs.put(pack.code(), pack);
        }
        try {
            for (DocTypePack pack : new PackStore(packsRoot).loadAll()) {
                packs.put(pack.code(), pack);
            }
        } catch (RuntimeException e) {
            // A broken induced-pack registry must not block promotion.
            System.err.println("[doc-induce] ignoring pack store " + packsRoot + ": "
                    + e.getMessage());
        }
        return packs;
    }

    /**
     * Clause-type induction summary for one document type.
     *
     * @param docTypeCode the document type's pack code
     * @param packVersion the new pack version the types were promoted into, or null
     * @param promoted types learned in this run
     * @param skipped groups kept open, each with its reason
     * @param proposalsRead open proposals considered
     */
    public record Result(
            String docTypeCode,
            String packVersion,
            List<ClauseTypePromoter.PromotedType> promoted,
            List<ClauseTypePromoter.SkippedGroup> skipped,
            int proposalsRead) {

        public Result {
            promoted = promoted == null ? List.of() : List.copyOf(promoted);
            skipped = skipped == null ? List.of() : List.copyOf(skipped);
        }
    }
}
