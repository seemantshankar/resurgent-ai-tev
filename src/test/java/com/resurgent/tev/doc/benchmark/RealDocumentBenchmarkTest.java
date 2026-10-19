package com.resurgent.tev.doc.benchmark;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.resurgent.tev.doc.assess.DocAssessRunner;
import com.resurgent.tev.doc.bind.PackPathBinder;
import com.resurgent.tev.doc.classify.ClassifyRequest;
import com.resurgent.tev.doc.classify.ClassifyResult;
import com.resurgent.tev.doc.classify.ClassifyTieBreak;
import com.resurgent.tev.doc.classify.DocClassifyRunner;
import com.resurgent.tev.doc.classify.DocTypeClassifier;
import com.resurgent.tev.doc.classify.LlmClassifyTieBreak;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.MandateQaQueryRunner;
import com.resurgent.tev.doc.extract.DocExtractRunner;
import com.resurgent.tev.doc.extract.LlamaExtractAdapter;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmEnvironment;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.doc.query.MandateQaQuery;
import com.resurgent.tev.parser.db.CostUnit;
import com.resurgent.tev.parser.db.LlmUsageCosts;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * On-demand real-document benchmark (M3-16, issue #149): runs the full pipeline
 * — ingest, layout, classify, live LlamaCloud extract, bind, M3 assess with the
 * live document LLM — over real client document sets in a local folder outside
 * git, one {@code workspace.db} per mandate under {@code target/benchmark/},
 * and prints key outcomes: stage status, per-site and checklist counts,
 * conflicts by settlement tier, and LLM cost.
 *
 * <p>Never part of a meaningful default run: it skips cleanly unless
 * {@code -Dtev.live.extract=true}, {@code -Dtev.benchmark.dirs=<paths>} and
 * both live keys are configured. Client documents are never committed; the
 * output tree sits under the gitignored {@code target/}.
 *
 * <p>Run with (see docs/plans/m3-benchmark.md):
 * <pre>{@code
 * mvn test -Dtest=RealDocumentBenchmarkTest -Dtev.live.extract=true \
 *     -Dtev.benchmark.dirs="/path/to/Project Docs/Bhagyanager Solar Ltd. Client Docs" \
 *     -Dtev.benchmark.packdir="/path/to/an/earlier/run/packs/general"
 * }</pre>
 *
 * <p>{@code tev.benchmark.packdir} points at the pack store of an earlier run
 * ({@code packs/} root with {@code registry.json}, or an industry subfolder);
 * it is copied into the mandate's own {@code {db-parent}/packs} when that is
 * empty, so induced packs are visible to every stage. Without any matching
 * pack a document stays {@code unknown} — a pack is never invented.
 *
 * <p>For the Bhagyanager set the ticket's expected outcomes are asserted
 * (eight sites, quoted PPA/PVsyst/quotation evidence, borrower and promoters,
 * country India, no hotel items). {@code -Dtev.benchmark.assert=false} turns
 * the assertions off and keeps it print-only.
 */
@Tag("live-extract")
class RealDocumentBenchmarkTest {

    private static final long MANDATE = 1L;

    @Test
    void runsTheFullPipelineOverConfiguredClientSets() throws Exception {
        assumeTrue("true".equals(System.getProperty("tev.live.extract")),
                "on-demand benchmark: add -Dtev.live.extract=true");
        String dirs = System.getProperty("tev.benchmark.dirs");
        assumeTrue(dirs != null && !dirs.isBlank(),
                "set -Dtev.benchmark.dirs to comma-separated client document folders (one mandate each)");
        assumeTrue(LlamaExtractAdapter.hasApiKey(),
                "set LLAMA_CLOUD_API_KEY or llamaparse_api_key (.env) for live LlamaCloud");
        assumeTrue(DocLlmEnvironment.clientOrNull() != null,
                "set OPENROUTER_API_KEY and DOC_LLM_MODEL (or Excel_Enrichment_Model_id) for live assess");
        DocLlmClient client = DocLlmEnvironment.clientOrNull();

        List<String> failures = new ArrayList<>();
        for (String raw : dirs.split(",")) {
            Path setDir = Path.of(raw.trim());
            assumeTrue(Files.isDirectory(setDir), "benchmark folder missing: " + setDir);
            MandateQa qa = runOne(setDir, client);
            if (isBhagyanager(setDir)) {
                collectBhagyanagerExpectations(setDir, qa, failures);
            }
        }
        assumeTrue(failures.isEmpty(), "benchmark expectations not met:\n" + String.join("\n", failures));
    }

    /** Runs one client folder end to end and returns the mandate's QA read. */
    private static MandateQa runOne(Path setDir, DocLlmClient client) throws Exception {
        String mandateName = setDir.getFileName().toString();
        Path outDir = Path.of(System.getProperty("tev.benchmark.out", "target/benchmark"))
                .resolve(sanitize(mandateName));
        Files.createDirectories(outDir);
        seedPackStore(outDir);
        Path dbPath = outDir.resolve("workspace.db");
        System.out.println("=== benchmark: " + mandateName + " -> " + dbPath.toAbsolutePath() + " ===");

        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(dbPath)) {
            // opening applies migrations
        }
        BlobStore blobs = BlobStore.besideDatabase(dbPath);
        List<DocTypePack> packs = candidatePacks(dbPath);

        List<Path> files = documentFiles(setDir);
        System.out.printf("Found %d document file(s) under %s%n", files.size(), setDir);
        int classified = 0;
        for (Path file : files) {
            String logical = mandateName + " / " + relativise(setDir, file);
            try {
                DocumentIngestService.IngestResult ingested =
                        new DocumentIngestService().ingest(
                                new DocumentIngestService.IngestRequest(
                                        file, MANDATE, logical, "v1", null, null),
                                dbPath, blobs);
                new DocumentLayoutService().layout(ingested.documentVersionId(), dbPath, blobs);
                String docType = classify(dbPath, ingested, packs);
                if (docType == null) {
                    System.out.printf("  %-60s unclassified (no pack; extract skipped)%n",
                            relativise(setDir, file));
                    continue;
                }
                classified++;
                DocExtractRunner.Result extract = DocExtractRunner.run(
                        MANDATE, ingested.documentVersionId(), dbPath, blobs,
                        null, "llama-extract", null, null);
                DocTypePack pack = packs.stream()
                        .filter(candidate -> candidate.code().equals(docType))
                        .findFirst().orElse(null);
                if (pack != null) {
                    new PackPathBinder().bind(dbPath, extract.parseRunId(), MANDATE, pack, null);
                }
                System.out.printf("  %-60s %s -> pack %s via %s: %d fact(s), missing=%d ambiguous=%d (%s)%n",
                        relativise(setDir, file), ingested.fileRole(), extract.packIdentity(),
                        extract.extractorId(), extract.factsWritten(), extract.missingRequired(),
                        extract.ambiguousCount(), extract.status());
            } catch (Exception e) {
                System.out.printf("  %-60s FAILED: %s%n", relativise(setDir, file), e);
            }
        }

        DocAssessRunner.run(MANDATE, dbPath, client);
        MandateQa qa = MandateQaQueryRunner.query(dbPath, MandateQaQuery.forMandate(MANDATE));
        printOutcomes(qa, classified, files.size(), dbPath);
        return qa;
    }

    /**
     * Seeds the mandate's pack store: copies the pack store given by
     * {@code -Dtev.benchmark.packdir} (the {@code packs/} root of an earlier run,
     * with {@code registry.json}, or its industry subfolder) into
     * {@code {outDir}/packs} when that is empty. Every later stage resolves packs
     * from seed plus {@code {db-parent}/packs}, so one copy makes induced packs
     * visible to classify, extract, bind and assess exactly as in production.
     */
    static void seedPackStore(Path outDir) throws Exception {
        String raw = System.getProperty("tev.benchmark.packdir");
        if (raw == null || raw.isBlank()) {
            return;
        }
        Path given = Path.of(raw.trim()).toAbsolutePath();
        Path storeRoot = Files.exists(given.resolve("registry.json"))
                ? given
                : given.getParent();
        if (storeRoot == null || !Files.exists(storeRoot.resolve("registry.json"))) {
            System.err.println("[benchmark] tev.benchmark.packdir has no registry.json; "
                    + "induced packs will be invisible: " + given);
            return;
        }
        Path target = outDir.resolve("packs");
        if (Files.exists(target.resolve("registry.json"))) {
            return;
        }
        try (var stream = Files.list(storeRoot)) {
            if (stream.findAny().isEmpty()) {
                return;
            }
        }
        Files.createDirectories(target);
        try (var stream = Files.walk(storeRoot)) {
            for (Path src : stream.toList()) {
                Path dest = target.resolve(storeRoot.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(src, dest);
                }
            }
        }
        System.out.println("[benchmark] seeded pack store " + storeRoot + " -> " + target);
    }

    /**
     * Candidate packs exactly as production classify assembles them: seed packs
     * plus the workspace pack store at {@code {db-parent}/packs} (seeded by
     * {@link #seedPackStore}). Reusing {@link DocClassifyRunner} keeps the
     * benchmark from diverging from the CLI (the divergence that left induced
     * packs invisible and every document unclassified).
     */
    static List<DocTypePack> candidatePacks(Path dbPath) throws Exception {
        return DocClassifyRunner.loadCandidatePacks(dbPath, null);
    }

    private static String classify(Path dbPath, DocumentIngestService.IngestResult ingested,
            List<DocTypePack> packs) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            DocumentRepository docs = new DocumentRepository(db.connection());
            DocumentRepository.DocumentVersionRow version =
                    docs.findVersion(ingested.documentVersionId()).orElseThrow();
            String page1 = DocTypeClassifier.loadPage1Text(db.connection(),
                    ingested.documentVersionId());
            // The benchmark ingests unlabeled folders, so the ingest-default role
            // (every PDF = agreement) is noise: pass an empty role and let the
            // packs' own identity signals decide, with the same live tie-break
            // the CLI wires when a document is ambiguous or scanned.
            DocLlmClient client = DocLlmEnvironment.clientOrNull();
            ClassifyTieBreak tieBreak = client == null
                    ? ClassifyTieBreak.offline()
                    : new LlmClassifyTieBreak(client, usage -> { });
            ClassifyResult result = new DocTypeClassifier(tieBreak).classify(new ClassifyRequest(
                    ingested.documentVersionId(), "", version.fileName(),
                    page1, "general", packs, true), db.connection());
            return "unknown".equalsIgnoreCase(result.docTypeCode())
                    ? null : result.docTypeCode();
        }
    }

    /** Prints the ticket's outcome report: statuses, conflicts by tier and cost. */
    private static void printOutcomes(MandateQa qa, int classified, int fileCount, Path dbPath)
            throws Exception {
        System.out.println("--- assessment stages ---");
        for (var stage : qa.stages()) {
            System.out.printf("  %-18s %s%s%n", stage.stage(), stage.status(),
                    stage.llmFailures() > 0
                            ? " (" + stage.llmFailures() + " LLM calls failed)" : "");
        }
        System.out.printf("--- documents: %d file(s), %d classified & extracted, %d version(s) ---%n",
                fileCount, classified, qa.documents().size());
        System.out.printf("--- country: %s | industries: %s%n",
                qa.country() == null ? "none" : qa.country().country(),
                qa.industries().stream().map(MandateQa.IndustryRow::industry).toList());
        System.out.printf("--- entities: %d (roles: %s)%n", qa.entities().size(),
                qa.entities().stream().map(MandateQa.EntityRow::role)
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new)));
        System.out.printf("--- sites: %d%n", qa.sites().size());
        for (var site : qa.sites()) {
            System.out.printf("  site %-40s documents=%d%n", site.siteName(),
                    site.documents().size());
        }
        System.out.printf("--- passages: %d | clauses: %d%n", qa.passages().size(),
                qa.clauses().size());

        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (var row : qa.checklist()) {
            byStatus.merge(row.status(), 1, Integer::sum);
        }
        System.out.printf("--- checklist (%d item(s)) by status: %s%n",
                qa.checklist().size(), byStatus);

        Map<String, Integer> conflictsBySettlement = new LinkedHashMap<>();
        for (var conflict : qa.conflicts()) {
            conflictsBySettlement.merge(conflict.settlement(), 1, Integer::sum);
        }
        System.out.printf("--- conflicts: %d by tier %s | versions differ: %d | skips: %d%n",
                qa.conflicts().size(), conflictsBySettlement,
                qa.versionsDiffer().size(), qa.conflictSkips().size());

        // Cost is reported through the one shared converter: credit rows priced in USD, USD rows
        // passed through (#152). Nothing here guesses a unit from the model id any more.
        double usdPerCredit = LlamaExtractAdapter.usdPerCredit();
        System.out.printf(Locale.ROOT,
                "--- LLM cost (llm_usage for this mandate; credit rows at %.6f USD each) ---%n",
                usdPerCredit);
        double totalCredits = 0;
        List<LlmUsageCosts.StageCost> usage;
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            usage = LlmUsageCosts.forMandate(db.connection(), MANDATE, usdPerCredit);
        }
        for (LlmUsageCosts.StageCost row : usage) {
            if (row.unit() == CostUnit.CREDITS) {
                totalCredits += row.recordedAmount();
            }
            System.out.printf(Locale.ROOT,
                    "  %-18s %-32s calls=%-4d tokens=%-10d %s=%-10.2f usd=$%.6f%n",
                    row.stage(), row.modelId(), row.calls(),
                    row.promptTokens() + row.completionTokens(),
                    row.unit().wireName(), row.recordedAmount(), row.usd());
        }
        System.out.println(usage.isEmpty()
                ? "  (no usage rows)"
                : String.format(Locale.ROOT, "  TOTAL: $%.6f (incl. %.2f LlamaCloud credits)%n",
                        LlmUsageCosts.totalUsd(usage), totalCredits));
    }

    // --- Bhagyanager expectations (issue #149 AC2), collected as one report ----

    private static boolean isBhagyanager(Path setDir) {
        return setDir.getFileName().toString().toLowerCase(Locale.ROOT).contains("bhagyan");
    }

    private static void collectBhagyanagerExpectations(Path setDir, MandateQa qa,
            List<String> failures) {
        if (!"false".equals(System.getProperty("tev.benchmark.assert"))) {
            expect(qa.sites().size() == 8, "eight sites found, got " + qa.sites().size(), failures);
            expect(qa.country() != null && qa.country().country().toUpperCase(Locale.ROOT)
                    .contains("INDIA"), "country India, got "
                    + (qa.country() == null ? "none" : qa.country().country()), failures);
            var roles = qa.entities().stream().map(MandateQa.EntityRow::role).toList();
            expect(roles.contains("borrower"), "borrower identified, roles=" + roles, failures);
            expect(roles.contains("promoter"), "promoter(s) identified, roles=" + roles, failures);
            expect(qa.checklist().stream()
                            .noneMatch(row -> row.itemKey().toLowerCase(Locale.ROOT).contains("hotel")
                                    || (row.basisText() != null
                                    && row.basisText().toLowerCase(Locale.ROOT).contains("hotel"))),
                    "no hotel items asked", failures);
            String names = qa.sites().stream()
                    .flatMap(site -> site.documents().stream())
                    .map(MandateQa.SiteDocumentRow::logicalName)
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .reduce((a, b) -> a + " " + b).orElse("");
            expect(names.contains("ppa"), "site documents cite a PPA", failures);
            expect(names.contains("pvsyst") || names.contains("pv syst"),
                    "site documents cite PVsyst", failures);
            expect(qa.checklist().stream().anyMatch(row -> row.itemKey().equals("cost_quotations")
                            && row.status().equals("provided")),
                    "cost quotations Provided", failures);
        }
        System.out.println(failures.isEmpty()
                ? "=== Bhagyanager expectations: ALL MET ==="
                : "=== Bhagyanager expectations NOT met: " + failures + " ===");
    }

    private static void expect(boolean ok, String what, List<String> failures) {
        System.out.printf("  [%s] %s%n", ok ? "PASS" : "FAIL", what);
        if (!ok) {
            failures.add(what);
        }
    }

    // --- helpers ---------------------------------------------------------------

    private static List<Path> documentFiles(Path root) throws Exception {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(RealDocumentBenchmarkTest::isDocument)
                    .sorted()
                    .forEach(files::add);
        }
        return files;
    }

    private static boolean isDocument(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".pdf") || name.endsWith(".png") || name.endsWith(".jpg")
                || name.endsWith(".jpeg") || name.endsWith(".tif") || name.endsWith(".tiff");
    }

    private static String relativise(Path root, Path file) {
        return root.relativize(file).toString();
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
