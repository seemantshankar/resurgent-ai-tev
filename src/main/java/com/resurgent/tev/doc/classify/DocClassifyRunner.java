package com.resurgent.tev.doc.classify;

import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.induce.PackStore;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmEnvironment;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI facade for classification. Delegates to {@link DocTypeClassifier} and, when a
 * model is configured, wires the live {@link LlmClassifyTieBreak}; otherwise the
 * offline tie-break declines and an ambiguous document stays {@code unknown}.
 */
public final class DocClassifyRunner {

    /** Parser version stamped on the classify {@code parse_run} that carries LLM usage. */
    public static final String PARSER_VERSION = "0.1.0-doc-classify";

    private DocClassifyRunner() {}

    /**
     * Classifies a document version.
     *
     * @param mandateId mandate scope
     * @param documentVersionId version to classify
     * @param db workspace database
     * @param blobs blob store
     * @param packDir optional extra pack directory
     * @param industry auto|tag
     * @param force overwrite existing classification
     * @return classification summary
     * @throws Exception on runtime failure
     */
    public static Result run(long mandateId, long documentVersionId, Path db, BlobStore blobs,
            Path packDir, String industry, boolean force) throws Exception {
        List<DocTypePack> packs = loadCandidatePacks(db, packDir);
        String industryTag = industry == null || "auto".equalsIgnoreCase(industry)
                ? null
                : industry;
        DocLlmClient client = DocLlmEnvironment.clientOrNull();
        List<DocLlmClient.Usage> usage = new ArrayList<>();
        ClassifyTieBreak tieBreak = client == null
                ? ClassifyTieBreak.offline()
                : new LlmClassifyTieBreak(client, usage::add);

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            Connection connection = workspace.connection();
            DocumentRepository docs = new DocumentRepository(connection);
            DocumentRepository.DocumentVersionRow version =
                    docs.findVersion(documentVersionId).orElseThrow(
                            () -> new IllegalArgumentException(
                                    "unknown document_version " + documentVersionId));
            String page1Text = DocTypeClassifier.loadPage1Text(connection, documentVersionId);
            ClassifyRequest request = new ClassifyRequest(
                    documentVersionId,
                    version.fileRole(),
                    version.fileName(),
                    page1Text,
                    industryTag,
                    packs,
                    force);
            connection.setAutoCommit(false);
            try {
                ClassifyResult result = new DocTypeClassifier(tieBreak).classify(request, connection);
                recordUsage(connection, version, mandateId, industryTag, usage);
                connection.commit();
                return new Result(
                        result.docTypeCode(),
                        result.packCode(),
                        result.packVersion(),
                        result.confidence(),
                        result.reasons(),
                        result.classifier(),
                        client != null);
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

    /**
     * Seed packs, any {@code --pack-dir} packs, plus every pack published under
     * {@code {db-parent}/packs} so a pack induced after the last classify is visible.
     * Later layers win on a duplicate code (induced over seed).
     */
    static List<DocTypePack> loadCandidatePacks(Path db, Path packDir) throws Exception {
        Map<String, DocTypePack> byCode = new LinkedHashMap<>();
        for (DocTypePack pack : PackLoader.loadSeedPacks()) {
            byCode.put(pack.code(), pack);
        }
        if (packDir != null) {
            for (DocTypePack pack : PackLoader.loadDirectory(packDir)) {
                byCode.put(pack.code(), pack);
            }
        }
        Path packsRoot = db.toAbsolutePath().getParent() == null
                ? Path.of("packs")
                : db.toAbsolutePath().getParent().resolve("packs");
        try {
            for (DocTypePack pack : new PackStore(packsRoot).loadAll()) {
                byCode.put(pack.code(), pack);
            }
        } catch (RuntimeException e) {
            // A broken induced-pack registry must not block classification.
            System.err.println("[doc-classify] ignoring pack store " + packsRoot + ": "
                    + e.getMessage());
        }
        return List.copyOf(byCode.values());
    }

    /**
     * Persists the tie-break model usage against a classify {@code parse_run} so spend is
     * attributable even though classify works on a {@code document_version}, not an
     * Excel {@code source_file} graph.
     */
    private static void recordUsage(Connection connection,
            DocumentRepository.DocumentVersionRow version, long mandateId, String industryTag,
            List<DocLlmClient.Usage> usage) throws Exception {
        if (usage.isEmpty() || version.sourceFileId() == null) {
            return;
        }
        WorkspaceRepository repo = new WorkspaceRepository(connection);
        String configHash = "classify:" + (industryTag == null ? "-" : industryTag.toLowerCase());
        Long existing = repo.findParseRunId(version.sourceFileId(), PARSER_VERSION, configHash);
        long parseRunId = existing != null
                ? existing
                : repo.insertParseRun(version.sourceFileId(), mandateId, PARSER_VERSION, configHash,
                        Timestamps.now(), Timestamps.now(), "running", null);
        repo.deleteStageStats(parseRunId, "doc-classify");

        Map<String, DocLlmClient.Usage> totals = new LinkedHashMap<>();
        for (DocLlmClient.Usage u : usage) {
            totals.merge(u.modelId(), u, DocLlmClient.Usage::plus);
        }
        for (DocLlmClient.Usage total : totals.values()) {
            repo.recordLlmUsage(parseRunId, "doc-classify", total.modelId(),
                    total.calls(), total.failedCalls(), 0,
                    total.promptTokens(), total.completionTokens(),
                    total.costUsd(), total.costUsd() == null ? 1 : 0,
                    total.latencyMsTotal());
        }
        repo.recordRunStat(parseRunId, "doc-classify", "llm_calls",
                (double) usage.stream().mapToInt(DocLlmClient.Usage::calls).sum(), null);
        repo.updateParseRunResult(parseRunId, Timestamps.now(), "success", null);
    }

    /**
     * Classification summary.
     *
     * @param docTypeCode pack code or unknown
     * @param packCode selected pack
     * @param packVersion selected pack version
     * @param confidence score
     * @param reasons signals used
     * @param classifier deterministic | tie-break | existing
     * @param liveModel true when a live LLM tie-break was available for this run
     */
    public record Result(
            String docTypeCode,
            String packCode,
            String packVersion,
            double confidence,
            List<String> reasons,
            String classifier,
            boolean liveModel) {
    }
}
