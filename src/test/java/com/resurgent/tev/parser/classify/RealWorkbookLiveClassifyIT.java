package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.db.WorksheetRef;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import com.resurgent.tev.parser.nomenclature.NomenclatureCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Live OpenRouter Layer A + Layer B against Om Arham. Disabled unless
 * {@code -Dtev.liveLlm=true} so ordinary {@code mvn test} stays fake/offline.
 * Uses {@code Project Docs/OM Arham Ventures.xlsx} and credentials from {@code .env}.
 */
class RealWorkbookLiveClassifyIT {

    private static final Path WORKBOOK =
            Path.of("Project Docs", "OM Arham Ventures.xlsx");
    private static final Set<String> LIVE_SHEETS = Set.of("ASSETS", "CAPITAL COST");
    private static final String HOTEL_AC_PATH =
            "Project Cost > Plant & Machinery > Air Conditioning";

    /** Named Om Arham bindings that must survive latency changes (path + role + label). */
    private static final List<ExpectedBinding> EXPECTED = List.of(
            new ExpectedBinding("Genset", AmountRole.ADD, "Volvo Penta Genset"),
            new ExpectedBinding("CCTV System", AmountRole.ADD, "CCTV"),
            new ExpectedBinding("Fitness Equipments", AmountRole.ADD, "Fitness"),
            new ExpectedBinding("Plumbing Works", AmountRole.ADD, "Plumbing"),
            new ExpectedBinding(
                    "Centering, Shuttering", AmountRole.ADD, "Centering"),
            new ExpectedBinding("Air Conditioning", AmountRole.ADD, "Air Conditioning"));

    @TempDir
    Path tempDir;

    @Test
    void liveLayerAAndLayerBOnOmArhamCapexSheets() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getProperty("tev.liveLlm")),
                "set -Dtev.liveLlm=true to call OpenRouter");
        assumeTrue(LlmEnvironment.liveConfigured(),
                "OPENROUTER_API_KEY and Excel_Enrichment_Model_id are required");
        assumeTrue(Files.exists(WORKBOOK),
                "Working workbook not found at " + WORKBOOK.toAbsolutePath());

        Path db = tempDir.resolve("om-arham-live-classify.db");
        IngestSummary ingest = new IngestService().ingest(WORKBOOK, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        Set<Long> liveWorksheetIds = new HashSet<>();
        List<CandidateRow> liveCandidates;
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            new NomenclatureCatalog(repo).confirmIndustry(1L, "hotel");
            for (WorksheetRef sheet : repo.selectWorksheetsForParseRun(ingest.parseRunId())) {
                if (LIVE_SHEETS.contains(sheet.sheetName())) {
                    liveWorksheetIds.add(sheet.worksheetId());
                }
            }
            liveCandidates = repo.selectCandidatesForParseRun(ingest.parseRunId()).stream()
                    .filter(c -> liveWorksheetIds.contains(c.worksheetId()))
                    .toList();
        }
        assertThat(liveWorksheetIds).isNotEmpty();
        assertThat(liveCandidates).isNotEmpty();

        ClassifierLlm openRouter = LlmEnvironment.classifierOrUnconfigured();
        AtomicInteger liveLayerA = new AtomicInteger();
        AtomicInteger liveLayerB = new AtomicInteger();
        ClassifierLlm mixed = new ClassifierLlm() {
            @Override
            public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
                if (!liveWorksheetIds.contains(prompt.packet().worksheetId())) {
                    return new LayerAJudgment(
                            ScheduleFamily.ASSUMPTIONS, Triage.ORPHAN, Relevance.NOISE,
                            List.of(), List.of(), null);
                }
                liveLayerA.incrementAndGet();
                System.err.printf("OpenRouter Layer A %d/%d candidate %d cheapPass=%s%n",
                        liveLayerA.get(), liveCandidates.size(),
                        prompt.packet().candidateId(), prompt.cheapPass());
                return openRouter.classifyLayerA(prompt);
            }

            @Override
            public List<LayerBLineJudgment> classifyLayerB(LayerBPrompt prompt) {
                if (!liveWorksheetIds.contains(prompt.packet().worksheetId())) {
                    return List.of();
                }
                liveLayerB.incrementAndGet();
                System.err.printf("OpenRouter Layer B %d candidate %d amounts=%d%n",
                        liveLayerB.get(),
                        prompt.packet().candidateId(),
                        LayerBAmountSupport.amountCells(prompt.packet()).size());
                return openRouter.classifyLayerB(prompt);
            }
        };

        long classifyStarted = System.nanoTime();
        ClassifySummary summary = new ClassifyService(mixed).classify(db, ingest.parseRunId());
        long classifyMs = (System.nanoTime() - classifyStarted) / 1_000_000L;
        assertThat(summary.dispositionCount()).isGreaterThan(liveCandidates.size());
        assertThat(liveLayerA.get()).isEqualTo(liveCandidates.size());
        assertThat(liveLayerB.get()).isGreaterThan(0);
        assertThat(summary.bindingCount()).isGreaterThan(0);
        assertThat(summary.layerBStats().accepted()).isEqualTo(summary.bindingCount());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<PacketDisposition> rows = repo.selectPacketDispositionsForParseRun(
                    ingest.parseRunId());
            List<PacketDisposition> liveRows = rows.stream()
                    .filter(row -> liveCandidates.stream()
                            .anyMatch(c -> c.candidateId() == row.candidateId()))
                    .toList();
            assertThat(liveRows).hasSize(liveCandidates.size());
            assertThat(liveRows).allMatch(row ->
                    row.triage().equals(Triage.MAIN)
                            || row.triage().equals(Triage.SCRATCH)
                            || row.triage().equals(Triage.ORPHAN));
            assertThat(liveRows).allMatch(row ->
                    row.relevance().equals(Relevance.PRIMARY)
                            || row.relevance().equals(Relevance.SUPPORTING)
                            || row.relevance().equals(Relevance.NOISE));
            assertThat(liveRows)
                    .as("live model should not collapse every Packet to the stub")
                    .anyMatch(row -> !ScheduleFamily.ASSUMPTIONS.equals(row.scheduleFamily())
                            || !Triage.ORPHAN.equals(row.triage()));

            List<NomenclatureBinding> bindings = repo.selectNomenclatureBindingsForParseRun(
                    ingest.parseRunId());
            assertThat(bindings).isNotEmpty();
            assertThat(bindings).allMatch(b -> AmountRole.isKnown(b.amountRole()));
            assertThat(bindings).allMatch(b -> b.path() != null && !b.path().isBlank());
            assertThat(bindings).allMatch(b -> b.verbatim() != null && !b.verbatim().isBlank());
            assertThat(bindings).noneMatch(b -> liveRows.stream()
                    .anyMatch(d -> d.candidateId() == b.candidateId() && d.cheapPass()));

            for (ExpectedBinding expected : EXPECTED) {
                assertThat(bindings)
                        .as("expected binding path~%s role=%s label~%s",
                                expected.pathContains(), expected.role(), expected.verbatimContains())
                        .anyMatch(expected::matches);
            }
            double acAdd = repo.sumAddAmountsForPath(ingest.parseRunId(), HOTEL_AC_PATH);
            assertThat(acAdd).as("Air Conditioning add rollup").isGreaterThan(0.0);

            Path report = Path.of("target", "om-arham-live-layer-ab.txt");
            StringBuilder body = new StringBuilder();
            body.append("liveLayerA=").append(liveLayerA.get())
                    .append(" liveLayerB=").append(liveLayerB.get())
                    .append(" bindings=").append(bindings.size())
                    .append(" classifyMs=").append(classifyMs)
                    .append('\n');
            body.append(summary.layerBStats().summaryLine()).append('\n');
            long layerAMs = 0;
            long layerBMs = 0;
            int layerAAttempts = 0;
            int layerBAttempts = 0;
            if (openRouter instanceof OpenRouterClassifierLlm timed) {
                long totalCompletion = 0;
                long totalPrompt = 0;
                int withUsage = 0;
                for (OpenRouterClassifierLlm.LlmCallMetric metric : timed.metrics()) {
                    body.append("metric\t").append(metric.layer())
                            .append('\t').append(metric.promptBytes())
                            .append('\t').append(metric.promptTokens())
                            .append('\t').append(metric.completionTokens())
                            .append('\t').append(metric.durationMs())
                            .append('\t').append(metric.attempts())
                            .append('\n');
                    if ("A".equals(metric.layer())) {
                        layerAMs += metric.durationMs();
                        layerAAttempts += metric.attempts();
                    } else if ("B".equals(metric.layer())) {
                        layerBMs += metric.durationMs();
                        layerBAttempts += metric.attempts();
                    }
                    if (metric.completionTokens() != null) {
                        totalCompletion += metric.completionTokens();
                        withUsage++;
                    }
                    if (metric.promptTokens() != null) {
                        totalPrompt += metric.promptTokens();
                    }
                }
                body.append("usageTotals promptTok=").append(totalPrompt)
                        .append(" completionTok=").append(totalCompletion)
                        .append(" callsWithUsage=").append(withUsage)
                        .append('\n');
                body.append("timeShare layerAMs=").append(layerAMs)
                        .append(" layerBMs=").append(layerBMs)
                        .append(" (Layer B wall is parallel; sums overlap)")
                        .append(" layerAAttempts=").append(layerAAttempts)
                        .append(" layerBAttempts=").append(layerBAttempts)
                        .append('\n');
                if (layerAMs > layerBMs) {
                    body.append("note: Layer A serial time dominates wall clock;"
                            + " bounded parent/child concurrency is a follow-up\n");
                }
            }
            body.append("quality expectedMatched=").append(EXPECTED.size())
                    .append('/').append(EXPECTED.size())
                    .append(" sumAddAC=").append(acAdd)
                    .append('\n');
            for (PacketDisposition row : liveRows) {
                CandidateRow candidate = liveCandidates.stream()
                        .filter(c -> c.candidateId() == row.candidateId())
                        .findFirst()
                        .orElseThrow();
                body.append(candidate.candidateKind())
                        .append('\t')
                        .append(row.cheapPass())
                        .append('\t')
                        .append(row.scheduleFamily())
                        .append('\t')
                        .append(row.triage())
                        .append('\t')
                        .append(row.relevance())
                        .append('\t')
                        .append(row.packetDefaultHead() == null ? "" : row.packetDefaultHead())
                        .append('\n');
            }
            body.append("--- bindings ---\n");
            for (NomenclatureBinding binding : bindings) {
                body.append(binding.cellId())
                        .append('\t')
                        .append(binding.amountRole())
                        .append('\t')
                        .append(binding.softLeaf())
                        .append('\t')
                        .append(binding.path())
                        .append('\t')
                        .append(binding.verbatim())
                        .append('\n');
            }
            body.append("sumAdd(").append(HOTEL_AC_PATH).append(")=").append(acAdd).append('\n');
            Files.createDirectories(report.getParent());
            Files.writeString(report, body.toString());
            System.err.println("Wrote " + report.toAbsolutePath());
            System.err.printf(Locale.ROOT,
                    "Live quality: %d ms, %d bindings, %s, expected %d/%d named bindings%n",
                    classifyMs,
                    bindings.size(),
                    summary.layerBStats().summaryLine(),
                    EXPECTED.size(),
                    EXPECTED.size());
        }
    }

    private record ExpectedBinding(String pathContains, String role, String verbatimContains) {
        boolean matches(NomenclatureBinding binding) {
            return binding.path() != null
                    && binding.path().contains(pathContains)
                    && role.equals(binding.amountRole())
                    && binding.verbatim() != null
                    && binding.verbatim().toLowerCase(Locale.ROOT)
                            .contains(verbatimContains.toLowerCase(Locale.ROOT));
        }
    }
}
