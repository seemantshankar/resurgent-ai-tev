package com.resurgent.tev.doc.extract;

import com.resurgent.tev.doc.db.MandatePolicyRepository;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.residency.DataResidency;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        if (autoInduce && (packCode == null || packCode.isBlank())) {
            String classified = classifiedPackCode(db, documentVersionId);
            if (classified == null || classified.isBlank() || "unknown".equalsIgnoreCase(classified)) {
                com.resurgent.tev.doc.classify.DocClassifyRunner.run(
                        mandateId, documentVersionId, db, blobs, null, "general", true, true);
            }
        }
        DocTypePack pack = resolvePack(db, documentVersionId, packCode);
        ExtractClient client = resolveClient(extractorId);
        PageSelection pageSelection = PageSelection.parseOptional(pages);
        List<Integer> pageIndices = pageSelection == null ? null : pageSelection.indices();

        ParseTier override = tier == null || tier.isBlank() ? null : ParseTier.fromWire(tier);
        boolean localOnly = localOnly(db, mandateId);
        if (localOnly && client instanceof LlamaExtractAdapter) {
            throw new ExtractException(
                    "LlamaExtractAdapter blocked: data residency is local_only");
        }
        ParseTier effectiveTier;
        Map<String, String> options = null;
        if (client instanceof LlamaExtractAdapter && !noParse && !localOnly
                && LlamaParseClient.hasApiKey()) {
            ParseService.ParseOutcome parsed = ParseService.parse(
                    db, documentVersionId, override, new LlamaParseClient(), blobs);
            effectiveTier = parsed.tier();
            if (parsed.jobId() != null && parsed.fullCloudJob()) {
                options = new LinkedHashMap<>();
                options.put(LlamaExtractAdapter.KEY_PARSE_JOB_ID, parsed.jobId());
                options.put(LlamaExtractAdapter.KEY_PARSE_TIER, parsed.tier().wireName());
                if (parsed.credits() != null) {
                    options.put(LlamaExtractAdapter.KEY_PARSE_CREDITS,
                            String.valueOf(parsed.credits()));
                }
                options.put(LlamaExtractAdapter.KEY_PARSE_LATENCY_MS,
                        String.valueOf(parsed.latencyMs()));
            } else {
                client = new DocLlmExtractClient();
            }
        } else {
            effectiveTier = override != null ? override
                    : ParseService.selectTier(db, documentVersionId);
            if (client instanceof LlamaExtractAdapter) {
                client = new DocLlmExtractClient();
            }
        }

        ExtractService service = new ExtractService();
        ExtractService.ExtractOutcome outcome = service.extract(
                db, documentVersionId, mandateId, pack, client, client.id(), null, options,
                pageIndices);
        if (autoInduce && !"failed".equals(outcome.status())) {
            com.resurgent.tev.doc.bind.DocBindRunner.run(
                    mandateId, outcome.parseRunId(), db, pack.code());
        }
        int ambiguous = outcome.fieldsAmbiguous();
        return new Result(
                pack.identity(),
                client.id(),
                outcome.factsTotal(),
                outcome.fieldsMissing(),
                ambiguous,
                outcome.parseRunId(),
                effectiveTier.wireName(),
                outcome.status(),
                outcome.message());
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

    private static DocTypePack resolvePack(Path db, long documentVersionId, String packCode)
            throws Exception {
        List<DocTypePack> packs = loadAllPacks(db);
        String code = packCode == null ? null : packCode.trim();
        if (code == null || code.isBlank()) {
            code = classifiedPackCode(db, documentVersionId);
            if (code == null || code.isBlank() || "unknown".equalsIgnoreCase(code)) {
                throw new IllegalArgumentException("--pack is required (classify version "
                        + documentVersionId + " first)");
            }
        }
        for (DocTypePack pack : packs) {
            if (com.resurgent.tev.doc.pack.PackCodes.same(pack.code(), code)) {
                return pack;
            }
        }
        throw new IllegalArgumentException("unknown pack: " + code + " (known: "
                + packs.stream().map(DocTypePack::code).sorted().toList() + ")");
    }

    private static List<DocTypePack> loadAllPacks(Path db) throws Exception {
        Map<String, DocTypePack> byCode = new LinkedHashMap<>();
        for (DocTypePack pack : PackLoader.loadSeedPacks()) {
            byCode.put(pack.code(), pack);
        }
        Path root = db.toAbsolutePath().getParent() == null
                ? Path.of("packs")
                : db.toAbsolutePath().getParent().resolve("packs");
        try {
            for (DocTypePack pack : new com.resurgent.tev.doc.induce.PackStore(root).loadAll()) {
                byCode.put(pack.code(), pack);
            }
        } catch (RuntimeException e) {
            System.err.println("[doc-extract] ignoring pack store " + root + ": "
                    + e.getMessage());
        }
        return List.copyOf(byCode.values());
    }

    /** Classified pack code for a version, or null when not classified. */
    public static String classifiedPackCode(Path db, long documentVersionId) throws Exception {
        try (com.resurgent.tev.parser.db.WorkspaceDatabase workspace =
                com.resurgent.tev.parser.db.WorkspaceDatabase.open(db)) {
            return new com.resurgent.tev.doc.db.DocumentRepository(workspace.connection())
                    .findClassificationCode(documentVersionId)
                    .orElse(null);
        }
    }

    /** Resolves the extractor adapter for CLI commands. */
    public static ExtractClient resolveExtractor(String extractorId) {
        return resolveClient(extractorId);
    }

    private static ExtractClient resolveClient(String extractorId) {
        String id = extractorId == null || extractorId.isBlank()
                ? defaultExtractor()
                : extractorId.trim().toLowerCase(Locale.ROOT);
        return switch (id) {
            case "fixture", "fixture-extract" -> new FixtureExtractClient();
            case "doc-llm-extract", "doc-llm", "page" -> new DocLlmExtractClient();
            case "llama-extract", "llama", "llama-extract-2.5-agentic" -> new LlamaExtractAdapter();
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
}
