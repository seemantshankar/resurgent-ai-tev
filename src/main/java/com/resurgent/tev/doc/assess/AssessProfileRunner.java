package com.resurgent.tev.doc.assess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.doc.db.AssessmentRepository;
import com.resurgent.tev.doc.db.AssessmentRepository.BlockAnchor;
import com.resurgent.tev.doc.db.AssessmentRepository.PageText;
import com.resurgent.tev.doc.db.AssessmentRepository.VersionRow;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.induce.PackStore;
import com.resurgent.tev.doc.llm.DocLlmClient;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code assess_profile} (issue #134): stores each mandate document version's date —
 * the stated date with page provenance when the document states one, else the file
 * metadata date marked as such, else none. One transaction per run; re-running replaces
 * the previous results and never duplicates them. LLM usage and stage counters are
 * recorded against the mandate's assess {@code parse_run}.
 */
public final class AssessProfileRunner implements AssessStage {

    /** Exact M3 stage name. */
    public static final String STAGE = "assess_profile";

    /** The kind stored when the version is unclassified and its pack declares none. */
    static final String DEFAULT_KIND = "date";

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
        DocumentRepository docs = new DocumentRepository(connection);
        WorkspaceRepository repo = new WorkspaceRepository(connection);
        Map<String, String> kindsByPack = loadDateKinds(dbPath);
        List<VersionRow> versions = assessment.versionsForMandate(mandateId);

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

        List<DocLlmClient.Usage> usage = new ArrayList<>();
        int statedCount = 0;
        int metadataCount = 0;
        int noneCount = 0;
        for (VersionRow version : versions) {
            // Replace semantics: drop the previous run's date and provenance first, so
            // the new provenance insert can never be deleted by the same run.
            assessment.clearDocumentDate(version.documentVersionId());
            String kind = kindFor(version, kindsByPack);
            List<PageText> pages = assessment.pageTexts(version.documentVersionId());
            Optional<StatedDate> stated = DocumentDateScanner.scan(kind, pages);
            if (stated.isEmpty()) {
                stated = LlmDocumentDate.find(client, kind, pages, usage::add);
            }
            if (stated.isPresent()) {
                DocumentDates.Canonical canonical =
                        DocumentDates.canonical(stated.get().dateText());
                if (canonical != null) {
                    Long provenanceId = insertDateProvenance(assessment, docs, version,
                            stated.get());
                    assessment.storeDocumentDate(version.documentVersionId(), kind,
                            canonical.documentDate(), canonical.precision(), "stated",
                            provenanceId);
                    statedCount++;
                    continue;
                }
            }
            DocumentDates.Canonical fromMetadata =
                    DocumentDates.fromIsoTimestamp(fileModifiedAt(version.rawMetadata()));
            if (fromMetadata != null) {
                assessment.storeDocumentDate(version.documentVersionId(), kind,
                        fromMetadata.documentDate(), fromMetadata.precision(),
                        "file_metadata", null);
                metadataCount++;
            } else {
                assessment.storeDocumentDate(version.documentVersionId(), kind,
                        null, null, "none", null);
                noneCount++;
            }
        }

        // M3-04 sites: after the document dates, identify the mandate's sites from the
        // facts and texts (appended step; see SiteScan).
        SiteScan.Result siteResult = SiteScan.scan(connection, mandateId, client, usage::add);

        recordStats(repo, parseRunId, usage, versions.size(), statedCount, metadataCount,
                noneCount, siteResult);
        assessment.upsertAssessmentRun(mandateId, STAGE, "completed", parseRunId,
                startedAt, Timestamps.now(),
                metricsJson(versions.size(), statedCount, metadataCount, noneCount, usage,
                        siteResult));

        String detail = String.format(Locale.ROOT,
                "%d documents (%d stated, %d file_metadata, %d none); %d sites (%d aliases)",
                versions.size(), statedCount, metadataCount, noneCount,
                siteResult.sites(), siteResult.aliases());
        return new StageResult(STAGE, versions.size(), detail);
    }

    private static String kindFor(VersionRow version, Map<String, String> kindsByPack) {
        if (version.docTypeCode() == null) {
            return DEFAULT_KIND;
        }
        return kindsByPack.getOrDefault(version.docTypeCode(), DEFAULT_KIND);
    }

    /** Pack-code → date kind for seed packs plus packs published beside the workspace. */
    private static Map<String, String> loadDateKinds(Path dbPath) throws Exception {
        Map<String, String> kinds = new LinkedHashMap<>();
        for (DocTypePack pack : PackLoader.loadSeedPacks()) {
            if (pack.documentDateKind() != null) {
                kinds.put(pack.code(), pack.documentDateKind());
            }
        }
        Path packsRoot = dbPath.toAbsolutePath().getParent() == null
                ? Path.of("packs")
                : dbPath.toAbsolutePath().getParent().resolve("packs");
        try {
            for (DocTypePack pack : new PackStore(packsRoot).loadAll()) {
                if (pack.documentDateKind() != null) {
                    kinds.put(pack.code(), pack.documentDateKind());
                }
            }
        } catch (RuntimeException e) {
            // A broken induced-pack registry must not block the assessment.
            System.err.println("[doc-assess] ignoring pack store " + packsRoot + ": "
                    + e.getMessage());
        }
        return kinds;
    }

    /** The provenance row for a stated date; null when the quote is not in the layout. */
    private static Long insertDateProvenance(AssessmentRepository assessment,
            DocumentRepository docs, VersionRow version, StatedDate stated) throws Exception {
        Optional<BlockAnchor> anchor = assessment.findBlockAnchor(
                version.documentVersionId(), stated.quote());
        if (anchor.isEmpty()) {
            return null;
        }
        BlockAnchor block = anchor.get();
        return docs.insertProvenance(AssessmentRepository.DATE_ENTITY_TYPE,
                version.documentVersionId(), version.documentVersionId(),
                block.pageId(), block.blockId(), null, null,
                block.bboxX0(), block.bboxY0(), block.bboxX1(), block.bboxY1(),
                null, null, stated.quote(), null);
    }

    /** {@code file_modified_at} recorded by ingest in the version's raw metadata. */
    private static String fileModifiedAt(String rawMetadata) {
        if (rawMetadata == null || rawMetadata.isBlank()) {
            return null;
        }
        try {
            var node = MAPPER.readTree(rawMetadata).get("file_modified_at");
            return node == null || node.isNull() ? null : node.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static void recordStats(WorkspaceRepository repo, Long parseRunId,
            List<DocLlmClient.Usage> usage, int total, int stated, int metadata, int none,
            SiteScan.Result sites) throws Exception {
        if (parseRunId == null) {
            return;
        }
        repo.recordRunStat(parseRunId, STAGE, "documents_total", (double) total, null);
        repo.recordRunStat(parseRunId, STAGE, "dates_stated", (double) stated, null);
        repo.recordRunStat(parseRunId, STAGE, "dates_file_metadata", (double) metadata, null);
        repo.recordRunStat(parseRunId, STAGE, "dates_none", (double) none, null);
        repo.recordRunStat(parseRunId, STAGE, "sites_total", (double) sites.sites(), null);
        repo.recordRunStat(parseRunId, STAGE, "site_aliases", (double) sites.aliases(), null);
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

    private static String metricsJson(int total, int stated, int metadata, int none,
            List<DocLlmClient.Usage> usage, SiteScan.Result sites) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("documents_total", total);
        node.put("dates_stated", stated);
        node.put("dates_file_metadata", metadata);
        node.put("dates_none", none);
        node.put("sites_total", sites.sites());
        node.put("site_aliases", sites.aliases());
        node.put("llm_calls", usage.stream().mapToInt(DocLlmClient.Usage::calls).sum());
        return node.toString();
    }
}
