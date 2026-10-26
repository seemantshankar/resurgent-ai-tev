package com.resurgent.tev.doc.assess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.doc.db.AssessmentRepository;
import com.resurgent.tev.doc.embed.EmbeddingClient;
import com.resurgent.tev.doc.embed.EmbeddingClients;
import com.resurgent.tev.doc.embed.EmbeddingRepository;
import com.resurgent.tev.doc.db.AssessmentRepository.BlockAnchor;
import com.resurgent.tev.doc.db.ClauseRepository;
import com.resurgent.tev.doc.db.ClauseRepository.ClauseWrite;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.induce.PackStore;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackClauseType;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code assess_clauses} (issue #141): reads clauses only for document types whose pack
 * declares them. Text comes from the stored layout blocks / parse markdown — never a
 * re-parse. Pass A binds clauses to the pack's types (aliases, plus model confirmation in
 * Pass B's single call); an unmatched clause is stored open with the proposed type,
 * summary and importance when the model read it. One transaction per run; a re-run
 * replaces the previous clauses and never duplicates them. LLM usage and stage counters
 * are recorded against the mandate's assess {@code parse_run}.
 */
public final class AssessClausesRunner implements AssessStage {

    /** Exact M3 stage name. */
    public static final String STAGE = "assess_clauses";

    /** Extraction method for a clause bound deterministically by alias. */
    static final String METHOD_ALIAS = "alias";

    /** Extraction method for a clause read or proposed by the model (reserved M2 name). */
    static final String METHOD_LLM_OPEN = "llm_open";

    private static final int BODY_ALIAS_WINDOW = 240;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return STAGE;
    }

    @Override
    public StageResult run(long mandateId, Path dbPath, DocLlmClient client) throws Exception {
        return run(mandateId, dbPath, client, null);
    }

    @Override
    public StageResult run(long mandateId, Path dbPath, DocLlmClient client, Path platform)
            throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(dbPath)) {
            Connection connection = workspace.connection();
            connection.setAutoCommit(false);
            try {
                StageResult result = assess(connection, mandateId, dbPath, client, platform);
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
            DocLlmClient client, Path platform) throws Exception {
        AssessmentRepository assessment = new AssessmentRepository(connection);
        ClauseRepository clauses = new ClauseRepository(connection);
        EmbeddingRepository embeddings = new EmbeddingRepository(connection);
        EmbeddingClient embedder = EmbeddingClients.forAssessment(connection, mandateId, platform);
        DocumentRepository docs = new DocumentRepository(connection);
        WorkspaceRepository repo = new WorkspaceRepository(connection);
        Map<String, DocTypePack> packsByCode = loadPacks(dbPath);
        List<AssessmentRepository.VersionRow> versions = assessment.versionsForMandate(mandateId);

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
        int total = 0;
        int bound = 0;
        int open = 0;
        int documentsWithClauses = 0;
        for (AssessmentRepository.VersionRow version : versions) {
            // Replace semantics: drop the previous run's clauses and their provenance
            // first, so the new inserts can never be deleted by the same run.
            clauses.deleteClausesForVersion(version.documentVersionId());
            DocTypePack pack = version.docTypeCode() == null
                    ? null
                    : packsByCode.get(version.docTypeCode());
            if (pack == null || !pack.declaresClauses()) {
                continue;
            }
            List<ClauseSegmenter.Segment> segments = ClauseSegmenter.segment(
                    clauses.pageTextsForClauses(version.documentVersionId()));
            if (segments.isEmpty()) {
                continue;
            }
            documentsWithClauses++;
            Map<Integer, LlmClauseReading.Reading> readings =
                    LlmClauseReading.read(client, pack, segments, usage::add);
            for (int i = 0; i < segments.size(); i++) {
                ClauseSegmenter.Segment segment = segments.get(i);
                LlmClauseReading.Reading reading = readings.get(i + 1);
                PackClauseType matched = aliasMatch(pack.clauseTypes(), segment).orElse(null);
                if (reading != null && reading.typeKey() != null) {
                    matched = pack.clauseTypes().stream()
                            .filter(t -> t.key().equals(reading.typeKey()))
                            .findFirst()
                            .orElse(matched);
                }
                String method = reading != null ? METHOD_LLM_OPEN : null;
                String summary = reading == null ? null : reading.summary();
                String importance = reading == null ? null : reading.importance();
                List<ClauseRepository.SlotWrite> slots = reading == null
                        ? List.of()
                        : reading.slots().stream()
                                .map(s -> ClauseSlots.normalize(s.key(), s.value()))
                                .flatMap(Optional::stream)
                                .toList();
                if (matched != null) {
                    long clauseTypeId = clauses.upsertPackType(pack.code(), pack.version(), matched);
                    storeClause(clauses, docs, assessment, embeddings, embedder, version, parseRunId,
                            segment, clauseTypeId, "bound", "known",
                            method != null ? method : METHOD_ALIAS, summary, importance, slots);
                    bound++;
                    total++;
                    continue;
                }
                if (!pack.clauseOpenExtraction()) {
                    continue;
                }
                Long proposedTypeId = null;
                if (reading != null && reading.proposedCode() != null) {
                    proposedTypeId = clauses.findOrInsertProposedType(
                            reading.proposedCode(),
                            reading.proposedLabel() != null ? reading.proposedLabel()
                                    : reading.proposedCode(),
                            reading.proposedFamily());
                }
                storeClause(clauses, docs, assessment, embeddings, embedder, version, parseRunId,
                        segment, proposedTypeId, "open", "new", method, summary, importance, slots);
                open++;
                total++;
            }
        }

        StageOutcome.Evaluation evaluation = StageOutcome.evaluate(usage, total);

        recordStats(repo, parseRunId, usage, total, bound, open, documentsWithClauses, versions.size());
        assessment.upsertAssessmentRun(new AssessmentRepository.AssessmentRunRecord(
                mandateId, STAGE, evaluation.status(), evaluation.failures(), parseRunId,
                startedAt, Timestamps.now(),
                metricsJson(total, bound, open, documentsWithClauses, usage)));

        String detail = String.format(Locale.ROOT,
                "%d clauses (%d bound, %d open) from %d of %d documents",
                total, bound, open, documentsWithClauses, versions.size());
        return new StageResult(STAGE, versions.size(), detail, evaluation.statusDb(), evaluation.failures());
    }

    /**
     * The type an alias deterministically binds: a heading match wins over the head of
     * the body, and an ambiguous field (several types matching) is left to Pass B.
     */
    static Optional<PackClauseType> aliasMatch(List<PackClauseType> types,
            ClauseSegmenter.Segment segment) {
        List<PackClauseType> headingHits = types.stream()
                .filter(t -> anyAliasIn(t, segment.heading()))
                .toList();
        if (headingHits.size() == 1) {
            return Optional.of(headingHits.get(0));
        }
        if (headingHits.size() > 1) {
            return Optional.empty();
        }
        String body = segment.text().length() <= BODY_ALIAS_WINDOW
                ? segment.text()
                : segment.text().substring(0, BODY_ALIAS_WINDOW);
        List<PackClauseType> bodyHits = types.stream().filter(t -> anyAliasIn(t, body)).toList();
        return bodyHits.size() == 1 ? Optional.of(bodyHits.get(0)) : Optional.empty();
    }

    private static boolean anyAliasIn(PackClauseType type, String text) {
        if (text == null || text.isBlank() || type.aliases().isEmpty()) {
            return false;
        }
        String haystack = " " + normalize(text) + " ";
        for (String alias : type.aliases()) {
            String needle = normalize(alias);
            if (!needle.isEmpty() && haystack.contains(" " + needle + " ")) {
                return true;
            }
        }
        return false;
    }

    /** Lowercase, punctuation to spaces — so "PPA's" never hides an alias. */
    static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Inserts the clause and, when the text is in the layout, its provenance anchor. */
    private static long storeClause(ClauseRepository clauses, DocumentRepository docs,
            AssessmentRepository assessment, EmbeddingRepository embeddings, EmbeddingClient embedder,
            AssessmentRepository.VersionRow version, Long parseRunId, ClauseSegmenter.Segment segment,
            Long clauseTypeId, String status, String novelty, String method, String summary,
            String importance, List<ClauseRepository.SlotWrite> slots) throws Exception {
        long clauseId = clauses.insertClause(new ClauseWrite(
                version.documentVersionId(), parseRunId, clauseTypeId,
                segment.heading(), segment.text(), segment.pageStart(), segment.pageEnd(),
                status, novelty, summary, importance, method));

        String anchorNeedle = segment.heading() == null ? firstLine(segment.text())
                : segment.heading();
        Optional<BlockAnchor> anchor = assessment.findBlockAnchor(
                version.documentVersionId(), anchorNeedle);
        if (anchor.isPresent()) {
            BlockAnchor block = anchor.get();
            long provenanceId = docs.insertProvenance(ClauseRepository.CLAUSE_ENTITY_TYPE,
                    clauseId, version.documentVersionId(), block.pageId(), block.blockId(),
                    null, null, block.bboxX0(), block.bboxY0(), block.bboxX1(), block.bboxY1(),
                    null, null, anchorNeedle, null);
            clauses.attachProvenance(clauseId, provenanceId);
        }
        for (ClauseRepository.SlotWrite slot : slots) {
            clauses.insertSlot(clauseId, slot);
        }
        embeddings.upsertClause(clauseId, embedder.model(), embedder.embed(
                segment.text() == null ? "" : segment.text()));
        return clauseId;
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return (newline < 0 ? text : text.substring(0, newline)).strip();
    }

    /** Pack-code → pack for seed packs plus packs published beside the workspace. */
    private static Map<String, DocTypePack> loadPacks(Path dbPath) throws Exception {
        Map<String, DocTypePack> packs = new LinkedHashMap<>();
        for (DocTypePack pack : PackLoader.loadSeedPacks()) {
            PackStore.keepHighest(packs, pack);
        }
        Path packsRoot = dbPath.toAbsolutePath().getParent() == null
                ? Path.of("packs")
                : dbPath.toAbsolutePath().getParent().resolve("packs");
        try {
            for (DocTypePack pack : new PackStore(packsRoot).loadAll()) {
                PackStore.keepHighest(packs, pack);
            }
        } catch (RuntimeException e) {
            // A broken induced-pack registry must not block the assessment.
            System.err.println("[doc-assess] ignoring pack store " + packsRoot + ": "
                    + e.getMessage());
        }
        return packs;
    }

    private static void recordStats(WorkspaceRepository repo, Long parseRunId,
            List<DocLlmClient.Usage> usage, int total, int bound, int open,
            int documentsWithClauses, int documentsTotal) throws Exception {
        if (parseRunId == null) {
            return;
        }
        repo.recordRunStat(parseRunId, STAGE, "documents_total", (double) documentsTotal, null);
        repo.recordRunStat(parseRunId, STAGE, "documents_with_clauses",
                (double) documentsWithClauses, null);
        repo.recordRunStat(parseRunId, STAGE, "clauses_total", (double) total, null);
        repo.recordRunStat(parseRunId, STAGE, "clauses_bound", (double) bound, null);
        repo.recordRunStat(parseRunId, STAGE, "clauses_open", (double) open, null);
        repo.recordRunStat(parseRunId, STAGE, "llm_calls",
                (double) usage.stream().mapToInt(DocLlmClient.Usage::calls).sum(), null);

        Map<String, DocLlmClient.Usage> totals = new LinkedHashMap<>();
        for (DocLlmClient.Usage u : usage) {
            totals.merge(u.modelId(), u, DocLlmClient.Usage::plus);
        }
        for (DocLlmClient.Usage modelTotal : totals.values()) {
            repo.recordLlmUsage(parseRunId, STAGE, modelTotal.modelId(), modelTotal.calls(),
                    modelTotal.failedCalls(), 0, modelTotal.promptTokens(),
                    modelTotal.completionTokens(), modelTotal.costUsd(), CostUnit.USD,
                    modelTotal.costUsd() == null ? 1 : 0, modelTotal.latencyMsTotal());
        }
        repo.updateParseRunResult(parseRunId, Timestamps.now(), "success", null);
    }

    private static String metricsJson(int total, int bound, int open, int documentsWithClauses,
            List<DocLlmClient.Usage> usage) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("documents_with_clauses", documentsWithClauses);
        node.put("clauses_total", total);
        node.put("clauses_bound", bound);
        node.put("clauses_open", open);
        node.put("llm_calls", usage.stream().mapToInt(DocLlmClient.Usage::calls).sum());
        return node.toString();
    }
}
