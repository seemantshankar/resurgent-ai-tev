package com.resurgent.tev.doc.induce;

import com.resurgent.tev.doc.db.CloudParsePageRepository;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmEnvironment;
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
 * CLI facade for pack induction. Loads the mandate corpus and delegates to
 * {@link PackInducer}.
 *
 * <p>Default target is the <em>unknown</em> documents: the ones classification could not
 * place. Every such cluster is induced separately, so one command can mint a pack per
 * newly discovered document type. A live LLM miner is used when a model is configured;
 * otherwise the deterministic offline miner still produces real packs from printed
 * labels. Quality gates run in {@link PackInducer}; only all-gates-pass packs publish to
 * {@code {db-parent}/packs}, and each outcome is recorded in {@code llm_usage},
 * {@code run_stat}, {@code doc_type_pack}, and {@code audit_log}.
 */
public final class DocInduceRunner {

    /** Parser version stamped on the induction {@code parse_run}s. */
    public static final String PARSER_VERSION = "0.1.0-doc-induce";

    private DocInduceRunner() {}

    /**
     * Runs pack induction over a mandate corpus.
     *
     * @param mandateId mandate scope
     * @param db workspace database
     * @param industry auto|tag
     * @param fileRole optional file_role filter (switches off the unknown-only default)
     * @param docType optional pack code filter (single targeted induction)
     * @param versions optional document_version_id allow-list (scopes a live run to samples)
     * @param dryRun when true, do not publish
     * @return one summary per induced cluster
     * @throws Exception on runtime failure
     */
    public static List<Result> run(long mandateId, Path db, String industry, String fileRole,
            String docType, List<Long> versions, boolean dryRun) throws Exception {
        String industryTag = industry == null || "auto".equalsIgnoreCase(industry)
                ? "general"
                : industry;
        Path packsRoot = db.toAbsolutePath().getParent() == null
                ? Path.of("packs")
                : db.toAbsolutePath().getParent().resolve("packs");
        PackStore store = new PackStore(packsRoot);

        List<PackInducer.InduceDocument> documents;
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            documents = loadCorpus(workspace.connection(), mandateId, fileRole, docType, versions);
        }
        if (documents.isEmpty()) {
            throw new IllegalArgumentException(
                    "no matching documents found for mandate " + mandateId
                            + (fileRole == null ? " (unknown/unclassified only)" : ""));
        }

        DocLlmClient live = DocLlmEnvironment.clientOrNull();
        List<List<PackInducer.InduceDocument>> clusters;
        if (docType != null && !docType.isBlank()) {
            clusters = List.of(documents);
        } else {
            clusters = PackInducer.clusterByTypeSignals(documents);
        }

        List<Outcome> outcomes = new ArrayList<>();
        List<Result> failures = new ArrayList<>();
        for (List<PackInducer.InduceDocument> cluster : clusters) {
            if (cluster.isEmpty()) {
                continue;
            }
            List<DocLlmClient.Usage> clusterUsage = new ArrayList<>();
            java.util.Map<String, String> env = System.getenv();
            WebSearchClient web = PackMiningClients.webSearchAllowed(env)
                    ? new HttpWebSearchClient(env.get(PackMiningClients.WEB_SEARCH_URL))
                    : null;
            PackMiningClient mining = PackMiningClients.select(env, live, web, clusterUsage::add);
            PackInducer.InduceRequest request = new PackInducer.InduceRequest(
                    cluster, industryTag, fileRole, docType, null,
                    mining, store,
                    PackQualityGates.DEFAULT_MIN_REQUIRED_COVERAGE,
                    Generalizer.DEFAULT_CONSENSUS_N,
                    dryRun);
            System.err.printf("[doc-induce] mining cluster of %d doc(s) starting at %s via %s%n",
                    cluster.size(), cluster.get(0).id(), mining.id());
            try {
                PackInducer.InduceResult result = new PackInducer().induce(request);
                outcomes.add(new Outcome(cluster, result, clusterUsage));
            } catch (Exception e) {
                System.err.printf("[doc-induce] cluster at %s skipped: %s%n",
                        cluster.get(0).id(), e.getMessage());
                failures.add(new Result("(mining failed)", "-", industryTag, null,
                        0, 0, false, List.of(), List.of(),
                        List.of("error: " + e.getMessage())));
            }
        }

        persist(db, packsRoot, mandateId, industryTag, outcomes, live != null);
        List<Result> results = new ArrayList<>();
        for (Outcome outcome : outcomes) {
            results.add(toResult(outcome.result()));
        }
        results.addAll(failures);
        return results;
    }

    private record Outcome(
            List<PackInducer.InduceDocument> cluster,
            PackInducer.InduceResult result,
            List<DocLlmClient.Usage> usage) {}

    private static Result toResult(PackInducer.InduceResult result) {
        List<String> gateMessages = new ArrayList<>();
        int passed = 0;
        for (PackQualityGates.GateResult gate : result.gates()) {
            gateMessages.add(gate.name() + ": " + gate.detail());
            if (gate.passed()) {
                passed++;
            }
        }
        gateMessages.addAll(result.rejectReasons());
        String packCode = result.pack() != null ? result.pack().code() : "(none)";
        String packVersion = result.pack() != null ? result.pack().version() : result.version();
        return new Result(
                packCode,
                packVersion,
                result.industryTag(),
                result.proposedYaml(),
                passed,
                result.gates().size(),
                result.published(),
                result.droppedFields(),
                result.rewrittenFields(),
                gateMessages);
    }

    /**
     * Records each induction outcome: a {@code parse_run} (anchored to the cluster's first
     * document), {@code llm_usage}, {@code run_stat}, a {@code doc_type_pack} row for a
     * published pack, and an {@code audit_log} entry. Re-induction replaces the stage's
     * usage/stat rows so totals never accumulate.
     */
    private static void persist(Path db, Path packsRoot, long mandateId, String industryTag,
            List<Outcome> outcomes, boolean liveModel) throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            Connection connection = workspace.connection();
            WorkspaceRepository repo = new WorkspaceRepository(connection);
            DocumentRepository docs = new DocumentRepository(connection);
            connection.setAutoCommit(false);
            try {
                for (Outcome outcome : outcomes) {
                    long sourceFileId = anchorSourceFileId(docs, outcome.cluster());
                    if (sourceFileId <= 0) {
                        continue;
                    }
                    PackInducer.InduceResult result = outcome.result();
                    String code = result.pack() != null ? result.pack().code() : "unknown";
                    String configHash = "induce:" + industryTag.toLowerCase() + ":" + code;
                    Long existing = repo.findParseRunId(sourceFileId, PARSER_VERSION, configHash);
                    long parseRunId = existing != null ? existing
                            : repo.insertParseRun(sourceFileId, mandateId, PARSER_VERSION,
                                    configHash, Timestamps.now(), Timestamps.now(), "running", null);
                    repo.deleteStageStats(parseRunId, "doc-induce");

                    Map<String, DocLlmClient.Usage> totals = new LinkedHashMap<>();
                    for (DocLlmClient.Usage usage : outcome.usage()) {
                        totals.merge(usage.modelId(), usage, DocLlmClient.Usage::plus);
                    }
                    for (DocLlmClient.Usage total : totals.values()) {
                        repo.recordLlmUsage(parseRunId, "doc-induce", total.modelId(),
                                total.calls(), total.failedCalls(), 0,
                                total.promptTokens(), total.completionTokens(),
                                total.costUsd(), CostUnit.USD, total.costUsd() == null ? 1 : 0,
                                total.latencyMsTotal());
                    }
                    repo.recordRunStat(parseRunId, "doc-induce", "documents_in_cluster",
                            (double) outcome.cluster().size(), null);
                    repo.recordRunStat(parseRunId, "doc-induce", "gates_passed",
                            (double) result.gates().stream().filter(PackQualityGates.GateResult::passed)
                                    .count(), null);
                    repo.recordRunStat(parseRunId, "doc-induce", "fields_generalized",
                            (double) (result.pack() == null ? 0 : result.pack().fields().size()), null);
                    if (result.published()) {
                        repo.recordRunStat(parseRunId, "doc-induce", "published_version",
                                null, result.version());
                        if (result.pack() != null && result.publishedPath() != null) {
                            Path yamlPath = Path.of(result.publishedPath()).toAbsolutePath().normalize();
                            String yamlRel;
                            try {
                                yamlRel = packsRoot.relativize(yamlPath).toString().replace('\\', '/');
                            } catch (IllegalArgumentException e) {
                                yamlRel = result.pack().code() + "@" + result.version() + ".yaml";
                            }
                            String fileHash = null;
                            try {
                                fileHash = PackStore.sha256Hex(java.nio.file.Files.readAllBytes(yamlPath));
                            } catch (Exception e) {
                                fileHash = result.pack().identity();
                            }
                            docs.upsertDocTypePack(
                                    result.pack().code(), result.version(),
                                    result.pack().family(), result.pack().fileRole(),
                                    industryTag, fileHash, yamlRel, "[" + mandateId + "]",
                                    "induce:" + parseRunId, "published");
                        }
                    }
                    repo.updateParseRunResult(parseRunId, Timestamps.now(),
                            result.published() ? "success" : "partial",
                            "{\"pack\":\"" + code + "\",\"live_model\":" + liveModel
                                    + ",\"published\":" + result.published() + "}");
                    InductionDecisionLog.recordPack(
                            connection, mandateId, parseRunId, code, result.decisions());
                    repo.insertAuditLog(parseRunId, "induce_completed", Timestamps.now(),
                            "{\"pack\":\"" + code + "\",\"published\":" + result.published()
                                    + ",\"gates_passed\":" + result.allGatesPassed()
                                    + ",\"rejects\":" + result.rejectReasons().size() + "}",
                            result.published() ? "info" : "warn");
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

    private static long anchorSourceFileId(DocumentRepository docs,
            List<PackInducer.InduceDocument> cluster) throws Exception {
        for (PackInducer.InduceDocument document : cluster) {
            Long versionId = versionIdOf(document.id());
            if (versionId == null) {
                continue;
            }
            var row = docs.findVersion(versionId);
            if (row.isPresent() && row.get().sourceFileId() != null) {
                return row.get().sourceFileId();
            }
        }
        return -1;
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

    /**
     * Loads the induction corpus. With no filters this is the <em>unknown</em> or
     * unclassified documents only, so {@code induce} grows packs for the types classify
     * could not place. A {@code --file-role} or {@code --doc-type} filter widens it.
     */
    private static List<PackInducer.InduceDocument> loadCorpus(Connection connection,
            long mandateId, String fileRole, String docType, List<Long> versions) throws Exception {
        boolean roleFilter = fileRole != null && !fileRole.isBlank();
        boolean typeFilter = docType != null && !docType.isBlank();
        boolean versionFilter = versions != null && !versions.isEmpty();
        StringBuilder sql = new StringBuilder("""
                SELECT dv.document_version_id, dv.file_role, dv.file_name,
                       COALESCE(d.logical_name, dv.file_name) AS title
                FROM document_version dv
                JOIN document d ON d.document_id = dv.document_id
                LEFT JOIN doc_classification c ON c.document_version_id = dv.document_version_id
                WHERE d.mandate_id = ?
                """);
        if (roleFilter) {
            sql.append(" AND dv.file_role = ? ");
        }
        if (typeFilter) {
            sql.append(" AND (c.doc_type_code = ? OR c.doc_type_code IS NULL) ");
        }
        if (!roleFilter && !typeFilter && !versionFilter) {
            sql.append(" AND (c.doc_type_code IS NULL OR c.doc_type_code = 'unknown') ");
        }
        if (versionFilter) {
            sql.append(" AND dv.document_version_id IN (")
                    .append("?,".repeat(versions.size() - 1))
                    .append("?) ");
        }
        sql.append(" ORDER BY dv.document_version_id ");

        List<PackInducer.InduceDocument> docs = new ArrayList<>();
        try (var ps = connection.prepareStatement(sql.toString())) {
            int index = 1;
            ps.setLong(index++, mandateId);
            if (roleFilter) {
                ps.setString(index++, fileRole);
            }
            if (typeFilter) {
                ps.setString(index++, docType);
            }
            if (versionFilter) {
                for (Long version : versions) {
                    ps.setLong(index++, version);
                }
            }
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    long versionId = rs.getLong(1);
                    String role = rs.getString(2);
                    String fileName = rs.getString(3);
                    String title = rs.getString(4);
                    List<String> layout = loadLayoutText(connection, versionId);
                    docs.add(new PackInducer.InduceDocument(
                            "dv:" + versionId,
                            role,
                            title,
                            layout,
                            entityTokens(title, fileName, layout)));
                }
            }
        }
        return docs;
    }

    /**
     * Entity tokens for label-not-value stripping: the logical title, the file name, and
     * every PAN/CIN/GSTIN/amount token seen in the layout.
     */
    private static Set<String> entityTokens(String title, String fileName, List<String> layout) {
        return EntityTokenFilter.sampleTokens(title, fileName, layout);
    }

    private static List<String> loadLayoutText(Connection connection, long documentVersionId)
            throws Exception {
        List<String> lines = new ArrayList<>();
        try (var ps = connection.prepareStatement(
                "SELECT COALESCE(NULLIF(b.text_norm, ''), b.text_raw) FROM block b"
                        + " JOIN page p ON p.page_id = b.page_id"
                        + " WHERE p.document_version_id = ?"
                        + " ORDER BY p.page_index, b.block_index LIMIT 120")) {
            ps.setLong(1, documentVersionId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    String text = rs.getString(1);
                    if (text != null && !text.isBlank()) {
                        lines.add(text.trim());
                    }
                }
            }
        }
        if (lines.isEmpty()) {
            lines.addAll(new CloudParsePageRepository(connection).loadTextPages(documentVersionId));
        }
        return lines.stream().limit(120).toList();
    }

    /**
     * Induction summary for one cluster.
     *
     * @param packCode induced or proposed pack code
     * @param packVersion version (bumped on re-induct)
     * @param industryTag industry used
     * @param packYaml proposed YAML (dry-run)
     * @param gatesPassed gates that passed
     * @param gatesTotal gates evaluated
     * @param published true when a pack was written to the store
     * @param droppedFields fields dropped by generalization (one-off/entity)
     * @param rewrittenFields keys/paths rewritten to labels
     * @param gateMessages per-gate reasons
     */
    public record Result(
            String packCode,
            String packVersion,
            String industryTag,
            String packYaml,
            int gatesPassed,
            int gatesTotal,
            boolean published,
            List<String> droppedFields,
            List<String> rewrittenFields,
            List<String> gateMessages) {
    }
}
