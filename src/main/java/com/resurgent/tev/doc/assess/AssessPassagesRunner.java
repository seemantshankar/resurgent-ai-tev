package com.resurgent.tev.doc.assess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.doc.db.AssessmentRepository;
import com.resurgent.tev.doc.db.AssessmentRepository.BlockAnchor;
import com.resurgent.tev.doc.db.AssessmentRepository.VersionRow;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.db.PassageRepository;
import com.resurgent.tev.doc.db.PassageRepository.ParsePage;
import com.resurgent.tev.doc.extract.CandidateNormalizer;
import com.resurgent.tev.doc.extract.ExtractClient;
import com.resurgent.tev.doc.extract.ExtractException;
import com.resurgent.tev.doc.extract.ExtractRequest;
import com.resurgent.tev.doc.extract.ExtractResult;
import com.resurgent.tev.doc.extract.FactCandidate;
import com.resurgent.tev.doc.extract.FixtureExtractClient;
import com.resurgent.tev.doc.extract.LlamaExtractAdapter;
import com.resurgent.tev.doc.induce.PackStore;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.pack.PackPassage;
import com.resurgent.tev.doc.pack.SchemaCompiler;
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
 * {@code assess_passages} (issue #139): splits each mandate document version's stored
 * parse output into chapter passages, assigns each chapter the pack-declared topic whose
 * heading terms match, stores a one-line summary — one LLM call per document with a
 * deterministic first-sentence fallback — and anchors the passage to the layout. Each
 * chapter then yields its topic's declared data points as facts linked to the passage
 * (listed tables become one row fact per entry, M2 quotation-row style), extracted
 * schema-guided from that chapter's stored pages. The stage reads only stored parse
 * pages, never triggering a new parse call. One transaction per run; re-running
 * replaces the previous passages and their facts. LLM usage and stage counters are
 * recorded against the mandate's assess {@code parse_run}.
 */
public final class AssessPassagesRunner implements AssessStage {

    /** Exact M3 stage name. */
    public static final String STAGE = "assess_passages";

    /** Deterministic summaries are capped at this many characters. */
    static final int SUMMARY_LIMIT = 160;

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
        FactRepository facts = new FactRepository(connection);
        PassageRepository passages = new PassageRepository(connection);
        WorkspaceRepository repo = new WorkspaceRepository(connection);
        Map<String, DocTypePack> packsByCode = loadPacks(dbPath);
        List<VersionRow> versions = assessment.versionsForMandate(mandateId);
        ExtractClient extractor = resolveExtractor(dbPath);
        BlobStore blobs = BlobStore.besideDatabase(dbPath);

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

        // Replace semantics: drop this stage's previous facts and every version's previous
        // passages first, so the new inserts can never be deleted by the same run.
        if (parseRunId != null) {
            facts.deleteFactsForParseRun(parseRunId);
        }
        for (VersionRow version : versions) {
            passages.clearForDocumentVersion(version.documentVersionId());
        }

        List<DocLlmClient.Usage> usage = new ArrayList<>();
        int passagesTotal = 0;
        int topicMatched = 0;
        int summarised = 0;
        int summarisedLlm = 0;
        int chapterFacts = 0;
        for (VersionRow version : versions) {
            DocTypePack pack = version.docTypeCode() == null
                    ? null
                    : packsByCode.get(version.docTypeCode());
            if (pack == null || pack.passages().isEmpty()) {
                continue; // no pack passage topics: nothing to assess per chapter
            }
            DocumentRepository.DocumentVersionRow docVersion =
                    docs.findVersion(version.documentVersionId()).orElse(null);
            List<ParsePage> pages = passages.parsePages(version.documentVersionId());
            List<PassageSplitter.Passage> chapters = PassageSplitter.split(pages);
            List<String> digests = new ArrayList<>();
            for (PassageSplitter.Passage chapter : chapters) {
                digests.add(chapter.heading() + "\n" + chapter.text());
            }
            // One summary call per document, never per passage.
            List<String> llmSummaries = LlmPassageSummaries.find(client, digests, usage::add);
            int index = 0;
            for (PassageSplitter.Passage chapter : chapters) {
                index++;
                PackPassage spec = PassageSplitter.specFor(pack, chapter.heading());
                String topic = spec == null ? null : spec.topic();
                if (topic != null) {
                    topicMatched++;
                }
                String summary = null;
                if (index <= llmSummaries.size()) {
                    summary = llmSummaries.get(index - 1);
                }
                if (summary == null || summary.isBlank()) {
                    summary = deterministicSummary(chapter.text());
                } else {
                    summarisedLlm++;
                }
                if (summary != null) {
                    summarised++;
                }
                long passageId = passages.insertPassage(version.documentVersionId(), index,
                        chapter.heading(), topic, chapter.pageFrom(), chapter.pageTo(), summary);
                Long provenanceId = insertPassageProvenance(assessment, docs, version,
                        passageId, chapter.heading(), chapter.text());
                if (provenanceId != null) {
                    passages.linkProvenance(passageId, provenanceId);
                }
                if (spec != null && docVersion != null && extractor != null
                        && parseRunId != null) {
                    chapterFacts += extractChapterFacts(facts, blobs, docVersion, pack, spec,
                            chapter, passageId, parseRunId, extractor);
                }
                passagesTotal++;
            }
        }

        StageOutcome.Evaluation evaluation = StageOutcome.evaluate(usage, passagesTotal);

        recordStats(repo, parseRunId, usage, versions.size(), passagesTotal, topicMatched,
                summarised, summarisedLlm, chapterFacts);
        assessment.upsertAssessmentRun(new AssessmentRepository.AssessmentRunRecord(
                mandateId, STAGE, evaluation.status(), evaluation.failures(), parseRunId,
                startedAt, Timestamps.now(), metricsJson(versions.size(), passagesTotal,
                        topicMatched, summarised, summarisedLlm, chapterFacts, usage)));

        String detail = String.format(Locale.ROOT,
                "%d documents (%d passages, %d topic-matched, %d chapter facts)",
                versions.size(), passagesTotal, topicMatched, chapterFacts);
        return new StageResult(STAGE, passagesTotal, detail, evaluation.statusDb(), evaluation.failures());
    }

    /**
     * Deterministic fallback summary: the chapter's first sentence, capped at
     * {@link #SUMMARY_LIMIT} characters. Null for an empty chapter.
     */
    static String deterministicSummary(String text) {
        String normalized = AssessmentRepository.normalizeWhitespace(text);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        int sentenceEnd = normalized.indexOf(". ");
        String first = sentenceEnd > 0 ? normalized.substring(0, sentenceEnd + 1) : normalized;
        if (first.length() <= SUMMARY_LIMIT) {
            return first;
        }
        return first.substring(0, SUMMARY_LIMIT - 3).trim() + "...";
    }

    /** Pack-code → pack for seed packs plus packs published beside the workspace. */
    private static Map<String, DocTypePack> loadPacks(Path dbPath) throws Exception {
        Map<String, DocTypePack> packs = new LinkedHashMap<>();
        for (DocTypePack pack : PackLoader.loadSeedPacks()) {
            packs.put(pack.code(), pack);
        }
        Path packsRoot = dbPath.toAbsolutePath().getParent() == null
                ? Path.of("packs")
                : dbPath.toAbsolutePath().getParent().resolve("packs");
        try {
            for (DocTypePack pack : new PackStore(packsRoot).loadAll()) {
                packs.put(pack.code(), pack);
            }
        } catch (RuntimeException e) {
            // A broken induced-pack registry must not block the assessment.
            System.err.println("[doc-assess] ignoring pack store " + packsRoot + ": "
                    + e.getMessage());
        }
        return packs;
    }

    /**
     * The extractor chapter facts are read through: the live LlamaExtract adapter when
     * a key is configured, else the offline fixture client loading {@code *.expected.json}
     * staged beside the workspace (mirrors the {@code {db-parent}/packs} convention).
     * Never a parser — chapter facts are extracted from stored pages.
     */
    private static ExtractClient resolveExtractor(Path dbPath) {
        Path dir = dbPath.toAbsolutePath().getParent() == null
                ? Path.of("docfixtures")
                : dbPath.toAbsolutePath().getParent().resolve("docfixtures");
        if (dir != null && java.nio.file.Files.isDirectory(dir)) {
            FixtureExtractClient fixture = new FixtureExtractClient();
            try {
                fixture.loadDirectory(dir);
                return fixture;
            } catch (Exception ignored) {
                // fall through
            }
        }
        if (LlamaExtractAdapter.hasApiKey()) {
            return new LlamaExtractAdapter();
        }
        FixtureExtractClient fixture = new FixtureExtractClient();
        try {
            fixture.loadDirectory(dir);
        } catch (Exception e) {
            // No staged fixtures is a legitimate empty state: chapters yield no facts.
        }
        return fixture;
    }

    /**
     * Reads one chapter's declared data points through the extractor: a schema compiled
     * from just the topic's fields, scoped to the chapter's stored pages. Returns how
     * many facts were written; an extractor failure leaves the passage without facts
     * rather than failing the stage.
     */
    private static int extractChapterFacts(FactRepository facts, BlobStore blobs,
            DocumentRepository.DocumentVersionRow docVersion, DocTypePack pack,
            PackPassage spec, PassageSplitter.Passage chapter, long passageId, long parseRunId,
            ExtractClient extractor) throws Exception {
        List<String> wanted = spec.fields();
        if (wanted.isEmpty()) {
            return 0;
        }
        JsonNode schema = SchemaCompiler.compileForFields(pack, wanted);
        List<Integer> pageIndices = new ArrayList<>();
        for (int page = chapter.pageFrom(); page <= chapter.pageTo(); page++) {
            pageIndices.add(page);
        }
        Path bundleDir = blobs.root().resolve(docVersion.fileHash());
        Path original = blobs.originalPath(docVersion.fileHash(), docVersion.fileExt());
        String instructions = "Extract fields for pack " + pack.identity()
                + " chapter '" + chapter.heading() + "' from pages " + pageIndices
                + ". Return exact quotes from the source.";
        ExtractRequest request = new ExtractRequest(bundleDir, original, pageIndices, null,
                pack.code(), instructions, null);

        ExtractResult result;
        try {
            result = extractor.extractFieldSchema(request, schema);
        } catch (ExtractException e) {
            System.err.println("[doc-assess] " + STAGE + " chapter '" + chapter.heading()
                    + "' of version " + docVersion.documentVersionId()
                    + " yielded no facts: " + e.getMessage());
            return 0;
        }
        int written = 0;
        for (FactCandidate candidate : result.facts()) {
            if (candidate.key() == null || !wanted.contains(candidate.key())) {
                continue;
            }
            CandidateNormalizer.normalize(candidate);
            facts.insertPassageFact(parseRunId, docVersion.documentVersionId(), passageId,
                    chapterFactWrite(pack, candidate, extractor.id()));
            written++;
        }
        return written;
    }

    /** Maps a chapter candidate to a fact write: pack path for fields, table path for rows. */
    private static FactRepository.FactWrite chapterFactWrite(DocTypePack pack,
            FactCandidate candidate, String extractorId) {
        String path = null;
        String pathRoot = null;
        for (Map<String, Object> table : pack.tables()) {
            if (candidate.key().equals(String.valueOf(table.get("name")))) {
                path = String.valueOf(table.get("path"));
                Object root = table.get("path_root");
                pathRoot = root == null ? null : String.valueOf(root).toLowerCase(Locale.ROOT);
                break;
            }
        }
        if (path == null) {
            Optional<PackField> field = pack.field(candidate.key());
            if (field.isPresent()) {
                path = field.get().path();
                pathRoot = field.get().pathRootWire();
            }
        }
        return FactRepository.FactWrite.builder()
                .fieldKey(candidate.key())
                .ontologyPath(path)
                .pathRoot(pathRoot)
                .subjectEntity(candidate.subjectEntity())
                .valueKind(candidate.valueKind().wireName())
                .valueText(candidate.valueText())
                .valueNumber(candidate.valueNumber())
                .valueDate(candidate.valueDate())
                .unit(candidate.unit())
                .currency(candidate.currency())
                .scale(candidate.scale())
                .absoluteAmount(candidate.absoluteAmount())
                .extractionMethod(candidate.extractionMethod())
                .extractorId(extractorId)
                .confidence(candidate.confidence())
                .statedOrDerived(candidate.statedOrDerived())
                .lineKey(candidate.lineKey())
                .citations(candidate.citations())
                .basisEvidence(candidate.basisEvidence())
                .build();
    }

    /**
     * The provenance row anchoring a passage: the heading's layout block when found,
     * with the passage's stored text as the verbatim. Null never — provenance_doc
     * allows an unanchored row.
     */
    private static Long insertPassageProvenance(AssessmentRepository assessment,
            DocumentRepository docs, VersionRow version, long passageId, String heading,
            String text) throws Exception {
        String verbatim = text == null || text.isBlank() ? heading : text;
        Long pageId = null;
        Long blockId = null;
        Double x0 = null;
        Double y0 = null;
        Double x1 = null;
        Double y1 = null;
        Optional<BlockAnchor> anchor =
                assessment.findBlockAnchor(version.documentVersionId(), heading);
        if (anchor.isPresent()) {
            BlockAnchor block = anchor.get();
            pageId = block.pageId();
            blockId = block.blockId();
            x0 = block.bboxX0();
            y0 = block.bboxY0();
            x1 = block.bboxX1();
            y1 = block.bboxY1();
        }
        return docs.insertProvenance(PassageRepository.ENTITY_TYPE, passageId,
                version.documentVersionId(), pageId, blockId, null, null,
                x0, y0, x1, y1, null, null, verbatim, null);
    }

    private static void recordStats(WorkspaceRepository repo, Long parseRunId,
            List<DocLlmClient.Usage> usage, int documents, int passagesTotal,
            int topicMatched, int summarised, int summarisedLlm, int chapterFacts)
            throws Exception {
        if (parseRunId == null) {
            return;
        }
        repo.recordRunStat(parseRunId, STAGE, "documents_total", (double) documents, null);
        repo.recordRunStat(parseRunId, STAGE, "passages_total", (double) passagesTotal, null);
        repo.recordRunStat(parseRunId, STAGE, "passages_topic_matched",
                (double) topicMatched, null);
        repo.recordRunStat(parseRunId, STAGE, "passages_summarised", (double) summarised, null);
        repo.recordRunStat(parseRunId, STAGE, "passages_summaries_llm",
                (double) summarisedLlm, null);
        repo.recordRunStat(parseRunId, STAGE, "chapter_facts_total",
                (double) chapterFacts, null);
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

    private static String metricsJson(int documents, int passagesTotal, int topicMatched,
            int summarised, int summarisedLlm, int chapterFacts,
            List<DocLlmClient.Usage> usage) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("documents_total", documents);
        node.put("passages_total", passagesTotal);
        node.put("passages_topic_matched", topicMatched);
        node.put("passages_summarised", summarised);
        node.put("passages_summaries_llm", summarisedLlm);
        node.put("chapter_facts_total", chapterFacts);
        node.put("llm_calls", usage.stream().mapToInt(DocLlmClient.Usage::calls).sum());
        return node.toString();
    }
}
