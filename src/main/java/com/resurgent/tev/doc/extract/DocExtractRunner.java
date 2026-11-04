package com.resurgent.tev.doc.extract;

import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.MandatePolicyRepository;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmEnvironment;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.residency.DataResidency;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * CLI facade for extract. Resolves pack + extractor and delegates to {@link ExtractService}.
 *
 * <p>For the live {@link LlamaExtractAdapter}, a LlamaParse run is created first (tier
 * chosen from layout) and its job id is passed as Extract {@code file_input}, so extract
 * reuses the cleaned, correctly-tiered parse instead of uploading blind.
 */
public final class DocExtractRunner {

    private DocExtractRunner() {}

    /**
     * Runs extract for a document version with automatic parse-tier selection.
     *
     * @param mandateId mandate scope
     * @param documentVersionId version to extract
     * @param db workspace database
     * @param blobs blob store
     * @param packCode optional pack override
     * @param extractorId optional extractor override ({@code fixture} | {@code llama-extract})
     * @param pages optional page range ({@code 1-5,8}); blank means all pages
     * @param tier optional Parse tier override ({@code fast|cost_effective|agentic|agentic_plus})
     * @return extract summary
     * @throws Exception on runtime failure
     */
    public static Result run(long mandateId, long documentVersionId, Path db, BlobStore blobs,
            String packCode, String extractorId, String pages, String tier) throws Exception {
        return run(mandateId, documentVersionId, db, blobs, packCode, extractorId, pages, tier,
                false);
    }

    /**
     * Same as {@link #run(long, long, Path, BlobStore, String, String, String, String)}
     * with the option to skip the parse-reuse step.
     *
     * @param noParse true to skip LlamaParse and let LlamaExtract upload the original
     */
    public static Result run(long mandateId, long documentVersionId, Path db, BlobStore blobs,
            String packCode, String extractorId, String pages, String tier, boolean noParse)
            throws Exception {
        return run(mandateId, documentVersionId, db, blobs, packCode, extractorId, pages, tier,
                noParse, true);
    }

    /**
     * Same as {@link #run(long, long, Path, BlobStore, String, String, String, String, boolean)}.
     * When {@code autoInduce} is true and no pack override is given, an unknown version
     * is classified (which induces a pack) before extract, and nomenclature is bound
     * after a completed extract.
     *
     * @param autoInduce false restores the requirement to pass {@code --pack}
     */
    public static Result run(long mandateId, long documentVersionId, Path db, BlobStore blobs,
            String packCode, String extractorId, String pages, String tier, boolean noParse,
            boolean autoInduce) throws Exception {
        return run(mandateId, documentVersionId, db, blobs, packCode, extractorId, pages, tier,
                noParse, autoInduce, null);
    }

    /**
     * Same as {@link #run(long, long, Path, BlobStore, String, String, String, String, boolean, boolean)},
     * and when {@code platform} is set the platform default spend ceiling applies.
     */
    public static Result run(long mandateId, long documentVersionId, Path db, BlobStore blobs,
            String packCode, String extractorId, String pages, String tier, boolean noParse,
            boolean autoInduce, Path platform) throws Exception {
        if (shouldFanOut(packCode, pages)) {
            return runAllDocuments(mandateId, documentVersionId, db, blobs, extractorId, tier,
                    noParse, autoInduce, platform).toAggregateResult();
        }
        return runSingleVersion(mandateId, documentVersionId, db, blobs, packCode, extractorId,
                pages, tier, noParse, autoInduce, platform);
    }

    /**
     * True when extract should fan out to every document on the named file version
     * (no {@code --pack} and no {@code --pages}).
     */
    public static boolean shouldFanOut(String packCode, String pages) {
        boolean noPack = packCode == null || packCode.isBlank();
        boolean noPages = pages == null || pages.isBlank();
        return noPack && noPages;
    }

    /**
     * Extracts every logical document whose pages live on the file version, binding
     * after each successful pass.
     */
    public static FileExtractResult runAllDocuments(long mandateId, long fileVersionId, Path db,
            BlobStore blobs, String extractorId, String tier, boolean noParse, boolean autoInduce,
            Path platform) throws Exception {
        com.resurgent.tev.doc.platform.PlatformRegistry.recordIfNamed(platform, db, mandateId);
        if (autoInduce) {
            String classified = classifiedPackCode(db, fileVersionId);
            if (classified == null || classified.isBlank() || "unknown".equalsIgnoreCase(classified)) {
                com.resurgent.tev.doc.classify.DocClassifyRunner.run(
                        mandateId, fileVersionId, db, blobs, null, "general", true, true,
                        platform);
            }
        }
        ExtractContext context = prepareContext(db, mandateId, fileVersionId, extractorId, tier,
                noParse, platform, null);
        ExtractService service = new ExtractService();
        List<DocumentBlock> blocks = new ArrayList<>();
        try (com.resurgent.tev.parser.db.WorkspaceDatabase workspace =
                com.resurgent.tev.parser.db.WorkspaceDatabase.open(db)) {
            DocumentRepository docs = new DocumentRepository(workspace.connection());
            List<DocumentRepository.FileMemberDocument> members =
                    docs.documentsOnFile(fileVersionId);
            if (members.isEmpty()) {
                members = List.of(new DocumentRepository.FileMemberDocument(
                        docs.findVersion(fileVersionId).orElseThrow().documentId(), fileVersionId));
            }
            List<DocTypePack> packs = loadAllPacks(db, platform, mandateId);
            boolean firstDocument = true;
            for (DocumentRepository.FileMemberDocument member : members) {
                String code = docs.findClassificationCode(member.classificationVersionId())
                        .orElse(null);
                if (code == null || code.isBlank() || "unknown".equalsIgnoreCase(code)) {
                    throw new IllegalArgumentException("--pack is required (classify version "
                            + member.classificationVersionId() + " first)");
                }
                DocTypePack pack = resolvePackFromCode(packs, code);
                List<Integer> pageList = docs.pageIndexesOf(member.documentId());
                ExtractService.ExtractOutcome outcome = service.read(
                        db, member.documentId(), mandateId, pack, context.client(),
                        context.client().id(), context.options());
                if (!firstDocument && context.options() != null) {
                    context.options().remove(LlamaExtractAdapter.KEY_PARSE_CREDITS);
                }
                firstDocument = false;
                if (autoInduce && !"failed".equals(outcome.status())) {
                    com.resurgent.tev.doc.bind.DocBindRunner.run(
                            mandateId, outcome.parseRunId(), db, pack.code(), platform);
                }
                blocks.add(new DocumentBlock(
                        member.documentId(),
                        pack.identity(),
                        context.client().id(),
                        pageList,
                        outcome.factsTotal(),
                        outcome.fieldsMissing(),
                        outcome.fieldsAmbiguous(),
                        outcome.parseRunId(),
                        outcome.status(),
                        outcome.message()));
            }
        }
        return new FileExtractResult(blocks, context.effectiveTier().wireName());
    }

    private static Result runSingleVersion(long mandateId, long documentVersionId, Path db,
            BlobStore blobs, String packCode, String extractorId, String pages, String tier,
            boolean noParse, boolean autoInduce, Path platform) throws Exception {
        com.resurgent.tev.doc.platform.PlatformRegistry.recordIfNamed(platform, db, mandateId);
        if (autoInduce && (packCode == null || packCode.isBlank())) {
            String classified = classifiedPackCode(db, documentVersionId);
            if (classified == null || classified.isBlank() || "unknown".equalsIgnoreCase(classified)) {
                com.resurgent.tev.doc.classify.DocClassifyRunner.run(
                        mandateId, documentVersionId, db, blobs, null, "general", true, true,
                        platform);
            }
        }
        DocTypePack pack = resolvePack(db, documentVersionId, packCode, platform, mandateId);
        ExtractContext context = prepareContext(db, mandateId, documentVersionId, extractorId,
                tier, noParse, platform, packCode);
        PageSelection pageSelection = PageSelection.parseOptional(pages);
        List<Integer> pageIndices = pageSelection == null ? null : pageSelection.indices();

        ExtractService service = new ExtractService();
        ExtractService.ExtractOutcome outcome = service.extract(
                db, documentVersionId, mandateId, pack, context.client(), context.client().id(),
                null, context.options(), pageIndices);
        if (autoInduce && !"failed".equals(outcome.status())) {
            com.resurgent.tev.doc.bind.DocBindRunner.run(
                    mandateId, outcome.parseRunId(), db, pack.code(), platform);
        }
        return new Result(
                pack.identity(),
                context.client().id(),
                outcome.factsTotal(),
                outcome.fieldsMissing(),
                outcome.fieldsAmbiguous(),
                outcome.parseRunId(),
                context.effectiveTier().wireName(),
                outcome.status(),
                outcome.message());
    }

    private record ExtractContext(ExtractClient client, ParseTier effectiveTier,
            Map<String, String> options) {}

    private static ExtractContext prepareContext(Path db, long mandateId, long documentVersionId,
            String extractorId, String tier, boolean noParse, Path platform, String packCode)
            throws Exception {
        ExtractClient client = resolveClient(extractorId,
                com.resurgent.tev.doc.platform.SpendCeiling.forbidsCloud(db, mandateId));
        ParseTier override = tier == null || tier.isBlank() ? null : ParseTier.fromWire(tier);
        boolean localOnly = localOnly(db, mandateId);
        double estimate = com.resurgent.tev.doc.platform.SpendCeiling.estimateUsd(1, override);
        boolean ceilingBlocks = com.resurgent.tev.doc.platform.SpendCeiling.blocks(
                db, mandateId, platform, estimate);
        if (ceilingBlocks && client instanceof LlamaExtractAdapter) {
            client = new DocLlmExtractClient();
        }
        Map<String, String> options = null;
        ParseTier effectiveTier;
        boolean mayParse = !noParse && !localOnly && !ceilingBlocks
                && LlamaParseClient.hasApiKey()
                && !(client instanceof FixtureExtractClient);
        if (mayParse) {
            ParseService.ParseOutcome parsed = ParseService.parse(
                    db, documentVersionId, override, new LlamaParseClient(), BlobStore.besideDatabase(db),
                    System.getenv(), platform);
            effectiveTier = parsed.tier();
            if (client instanceof LlamaExtractAdapter && parsed.jobId() != null
                    && parsed.fullCloudJob()) {
                options = new LinkedHashMap<>();
                options.put(LlamaExtractAdapter.KEY_PARSE_JOB_ID, parsed.jobId());
                options.put(LlamaExtractAdapter.KEY_PARSE_TIER, parsed.tier().wireName());
                if (parsed.credits() != null) {
                    options.put(LlamaExtractAdapter.KEY_PARSE_CREDITS,
                            String.valueOf(parsed.credits()));
                }
                options.put(LlamaExtractAdapter.KEY_PARSE_LATENCY_MS,
                        String.valueOf(parsed.latencyMs()));
            } else if (client instanceof LlamaExtractAdapter) {
                client = new DocLlmExtractClient();
            }
        } else {
            effectiveTier = override != null ? override
                    : ParseService.selectTier(db, documentVersionId);
            if (client instanceof LlamaExtractAdapter) {
                client = new DocLlmExtractClient();
            }
        }
        if (platform != null) {
            if (options == null) {
                options = new LinkedHashMap<>();
            }
            options.put(com.resurgent.tev.doc.platform.SpendCeiling.PLATFORM_OPTION,
                    platform.toAbsolutePath().normalize().toString());
        }
        client = withStatedClaimLlm(client, db, mandateId, platform, estimate,
                localOnly || ceilingBlocks);
        return new ExtractContext(client, effectiveTier, options);
    }

    /**
     * Resolves the pack to extract: the explicit {@code --pack}, or the version's
     * classified pack. Searches seed packs and every pack published under
     * {@code {db-parent}/packs}, so an induced pack is usable right after classify.
     */
    private static boolean localOnly(Path db, long mandateId) throws Exception {
        try (com.resurgent.tev.parser.db.WorkspaceDatabase workspace =
                com.resurgent.tev.parser.db.WorkspaceDatabase.open(db)) {
            String row = new MandatePolicyRepository(workspace.connection()).dataResidency(mandateId);
            return DataResidency.isLocalOnly(
                    DataResidency.effective(System.getenv(DataResidency.ENV), row));
        }
    }

    private static DocTypePack resolvePack(Path db, long documentVersionId, String packCode,
            Path platform, long mandateId) throws Exception {
        List<DocTypePack> packs = loadAllPacks(db, platform, mandateId);
        String code = packCode == null ? null : packCode.trim();
        if (code == null || code.isBlank()) {
            code = classifiedPackCode(db, documentVersionId);
            if (code == null || code.isBlank() || "unknown".equalsIgnoreCase(code)) {
                throw new IllegalArgumentException("--pack is required (classify version "
                        + documentVersionId + " first)");
            }
        }
        return resolvePackFromCode(packs, code);
    }

    private static DocTypePack resolvePackFromCode(List<DocTypePack> packs, String code) {
        for (DocTypePack pack : packs) {
            if (com.resurgent.tev.doc.pack.PackCodes.same(pack.code(), code)) {
                return pack;
            }
        }
        throw new IllegalArgumentException("unknown pack: " + code + " (known: "
                + packs.stream().map(DocTypePack::code).sorted().toList() + ")");
    }

    private static List<DocTypePack> loadAllPacks(Path db, Path platform, long mandateId)
            throws Exception {
        return com.resurgent.tev.doc.classify.DocClassifyRunner.loadCandidatePacks(
                db, null, platform, mandateId);
    }

    /** Classified pack code for a version, or null when not classified. */
    public static String classifiedPackCode(Path db, long documentVersionId) throws Exception {
        try (com.resurgent.tev.parser.db.WorkspaceDatabase workspace =
                com.resurgent.tev.parser.db.WorkspaceDatabase.open(db)) {
            return new DocumentRepository(workspace.connection())
                    .findClassificationCode(documentVersionId)
                    .orElse(null);
        }
    }

    /** Resolves the extractor adapter for CLI commands (cloud allowed). */
    public static ExtractClient resolveExtractor(String extractorId) {
        return resolveClient(extractorId, false);
    }

    private static ExtractClient withStatedClaimLlm(ExtractClient client, Path db, long mandateId,
            Path platform, double estimate, boolean blocked) {
        if (blocked || !(client instanceof DocLlmExtractClient)) {
            return client;
        }
        DocLlmClient llm = DocLlmEnvironment.clientOrNull(db, mandateId, platform, estimate);
        return llm == null ? client : new DocLlmExtractClient(llm);
    }

    private static ExtractClient resolveClient(String extractorId, boolean cloudForbidden) {
        String id = extractorId == null || extractorId.isBlank()
                ? defaultExtractor()
                : extractorId.trim().toLowerCase(Locale.ROOT);
        return switch (id) {
            case "fixture", "fixture-extract" -> new FixtureExtractClient();
            case "doc-llm-extract", "doc-llm", "page" -> new DocLlmExtractClient();
            case "llama-extract", "llama", "llama-extract-2.5-agentic" -> cloudForbidden
                    ? new DocLlmExtractClient()
                    : new LlamaExtractAdapter();
            default -> throw new IllegalArgumentException(
                    "unknown extractor: " + extractorId
                            + " (use doc-llm-extract|fixture|llama-extract)");
        };
    }

    /** Page assembly. LlamaExtract is only used when the caller names it. */
    private static String defaultExtractor() {
        return DocLlmExtractClient.ID;
    }

    /**
     * Extract summary printed by the CLI.
     *
     * @param packIdentity pack code@version
     * @param extractorId adapter id
     * @param factsWritten fact rows written
     * @param missingRequired required fields not found
     * @param ambiguousCount ambiguous evidence count
     * @param parseRunId parse_run id
     * @param parseTier Parse tier used (selector result, or {@code --tier} override)
     * @param status extract outcome status: {@code success} | {@code partial} | {@code failed}
     * @param message human-readable outcome detail (warning text for partial/failed)
     */
    public record Result(
            String packIdentity,
            String extractorId,
            int factsWritten,
            int missingRequired,
            int ambiguousCount,
            long parseRunId,
            String parseTier,
            String status,
            String message) {
    }

    /** One document's extract within a file-level fan-out. */
    public record DocumentBlock(
            long documentId,
            String packIdentity,
            String extractorId,
            List<Integer> pages,
            int factsWritten,
            int missingRequired,
            int ambiguousCount,
            long parseRunId,
            String status,
            String message) {
    }

    /** Outcome of extracting every document on one file version. */
    public record FileExtractResult(List<DocumentBlock> documents, String parseTier) {

        /** Backward-compatible aggregate for callers of {@link #run}. */
        public Result toAggregateResult() {
            if (documents.isEmpty()) {
                throw new IllegalStateException("no documents extracted");
            }
            int facts = 0;
            int missing = 0;
            int ambiguous = 0;
            String status = "success";
            String message = "ok";
            for (DocumentBlock block : documents) {
                facts += block.factsWritten();
                missing += block.missingRequired();
                ambiguous += block.ambiguousCount();
                if ("failed".equals(block.status())) {
                    status = "failed";
                    message = block.message();
                } else if ("partial".equals(block.status()) && !"failed".equals(status)) {
                    status = "partial";
                    message = block.message();
                }
            }
            DocumentBlock last = documents.get(documents.size() - 1);
            String packs = documents.stream()
                    .map(DocumentBlock::packIdentity)
                    .distinct()
                    .collect(Collectors.joining(", "));
            return new Result(
                    documents.size() == 1 ? last.packIdentity() : packs,
                    last.extractorId(),
                    facts,
                    missing,
                    ambiguous,
                    last.parseRunId(),
                    parseTier,
                    status,
                    message);
        }
    }
}
