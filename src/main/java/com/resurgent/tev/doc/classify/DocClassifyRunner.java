package com.resurgent.tev.doc.classify;

import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.induce.DocInduceRunner;
import com.resurgent.tev.doc.induce.PackStore;
import com.resurgent.tev.doc.induce.SpineCatalog;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmEnvironment;
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
        return run(mandateId, documentVersionId, db, blobs, packDir, industry, force, true);
    }

    /**
     * Same as {@link #run(long, long, Path, BlobStore, Path, String, boolean)}.
     * When {@code autoInduce} is true and scoring leaves the version {@code unknown},
     * the unclassified documents in the mandate are induced, published, and this
     * version is classified again onto the new pack.
     *
     * @param autoInduce false keeps an unknown document unknown ({@code --no-auto-induce})
     */
    public static Result run(long mandateId, long documentVersionId, Path db, BlobStore blobs,
            Path packDir, String industry, boolean force, boolean autoInduce) throws Exception {
        List<DocTypePack> packs = loadCandidatePacks(db, packDir);
        String industryTag = industry == null || "auto".equalsIgnoreCase(industry)
                ? null
                : industry;
        DocLlmClient client = DocLlmEnvironment.clientOrNull(db, mandateId);
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
            String pageText = DocTypeClassifier.loadContents(connection, documentVersionId);
            ClassifyRequest request = new ClassifyRequest(
                    documentVersionId,
                    version.fileRole(),
                    version.fileName(),
                    pageText,
                    industryTag,
                    packs,
                    force);
            connection.setAutoCommit(false);
            try {
                DocTypeClassifier classifier = new DocTypeClassifier(tieBreak);
                ClassifyResult result = classifier.classify(request, connection);
                if (autoInduce && unknown(result)
                        && statesInducibleContents(connection, documentVersionId)) {
                    // Release the write lock before induction opens its own connection.
                    // The induced type is kept. It is not sent back through a heading contest.
                    connection.commit();
                    connection.setAutoCommit(true);
                    DocInduceRunner.Result kept = publishedPack(
                            induceUnknown(mandateId, db, industry));
                    connection.setAutoCommit(false);
                    if (kept != null) {
                        String packVersion = kept.packVersion() == null
                                || kept.packVersion().isBlank()
                                || "-".equals(kept.packVersion())
                                ? "1.0.0" : kept.packVersion();
                        result = new ClassifyResult(
                                kept.packCode(), kept.packCode(), packVersion, 0.8,
                                List.of("induced from the page contents and kept"),
                                ClassifyResult.Status.CLASSIFIED, "induced");
                        classifier.record(connection, new ClassifyRequest(
                                documentVersionId,
                                version.fileRole(),
                                version.fileName(),
                                DocTypeClassifier.loadContents(connection, documentVersionId),
                                industryTag,
                                List.of(),
                                true), result);
                    }
                }
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

    private static boolean unknown(ClassifyResult result) {
        String code = result.docTypeCode();
        return code == null || code.isBlank() || "unknown".equalsIgnoreCase(code);
    }

    /** Induces every still-unknown document in the mandate and publishes the packs. */
    private static List<DocInduceRunner.Result> induceUnknown(long mandateId, Path db,
            String industry) throws Exception {
        String tag = industry == null || industry.isBlank() || "auto".equalsIgnoreCase(industry)
                ? "general"
                : industry;
        try {
            return DocInduceRunner.run(mandateId, db, tag, null, null, null, false);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() == null || !e.getMessage().contains("no matching documents")) {
                throw e;
            }
            return List.of();
        }
    }

    private static DocInduceRunner.Result publishedPack(List<DocInduceRunner.Result> induced) {
        if (induced == null) {
            return null;
        }
        for (DocInduceRunner.Result result : induced) {
            if (result.published() && result.packCode() != null && !result.packCode().isBlank()
                    && !result.packCode().startsWith("(")) {
                return result;
            }
        }
        return null;
    }

    /**
     * True when the body states at least two labeled values a new type could be
     * learned from. A heading alone, or one borrowed figure, does not.
     */
    private static boolean statesInducibleContents(Connection connection, long versionId)
            throws Exception {
        int stated = 0;
        try (var ps = connection.prepareStatement(
                "SELECT b.block_kind, COALESCE(NULLIF(b.text_norm, ''), b.text_raw)"
                        + " FROM block b"
                        + " JOIN page p ON p.page_id = b.page_id"
                        + " WHERE p.document_version_id = ?"
                        + " ORDER BY p.page_index, b.block_index")) {
            ps.setLong(1, versionId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    String kind = rs.getString(1);
                    String text = rs.getString(2);
                    if (text == null || text.isBlank() || "heading".equalsIgnoreCase(kind)) {
                        continue;
                    }
                    String trimmed = text.trim();
                    if (!trimmed.contains(":") && !trimmed.chars().anyMatch(Character::isDigit)) {
                        continue;
                    }
                    String label = trimmed;
                    int colon = trimmed.indexOf(':');
                    if (colon > 0) {
                        label = trimmed.substring(0, colon).trim();
                    }
                    if (SpineCatalog.matchLabel(label).isPresent()
                            || SpineCatalog.matchLabel(trimmed).isPresent()) {
                        stated++;
                    }
                }
            }
        }
        return stated >= 2;
    }

    /**
     * Seed packs, any {@code --pack-dir} packs, plus every pack published under
     * {@code {db-parent}/packs} so a pack induced after the last classify is visible.
     * Later layers win on a duplicate code (induced over seed). Public so on-demand
     * harnesses (the live benchmark) reuse production candidate assembly instead of
     * re-implementing it.
     */
    public static List<DocTypePack> loadCandidatePacks(Path db, Path packDir) throws Exception {
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
                    total.costUsd(), CostUnit.USD, total.costUsd() == null ? 1 : 0,
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
