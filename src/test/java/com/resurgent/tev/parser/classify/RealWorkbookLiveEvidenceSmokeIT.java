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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Focused live OpenRouter smoke for #117 evidence on one Om Arham tab with a
 * handful of Candidates. Not a full-sheet quality gate. Enable with
 * {@code -Dtev.liveLlm=true}.
 */
class RealWorkbookLiveEvidenceSmokeIT {

    private static final Path WORKBOOK =
            Path.of("Project Docs", "OM Arham Ventures.xlsx");
    private static final String LIVE_SHEET = "ASSETS";
    /** Coverage parent + this many narrowest non-coverage children. */
    private static final int MAX_NARROW_CANDIDATES = 2;

    @TempDir
    Path tempDir;

    @Test
    void liveEvidenceOnFewAssetsCandidates() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getProperty("tev.liveLlm")),
                "set -Dtev.liveLlm=true to call OpenRouter");
        assumeTrue(LlmEnvironment.liveConfigured(),
                "OPENROUTER_API_KEY and Excel_Enrichment_Model_id are required");
        assumeTrue(Files.exists(WORKBOOK),
                "Working workbook not found at " + WORKBOOK.toAbsolutePath());

        Path db = tempDir.resolve("om-arham-evidence-smoke.db");
        IngestSummary ingest = new IngestService().ingest(WORKBOOK, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        long liveWorksheetId;
        Set<Long> liveCandidateIds = new HashSet<>();
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            new NomenclatureCatalog(repo).confirmIndustry(1L, "hotel");
            WorksheetRef assets = repo.selectWorksheetsForParseRun(ingest.parseRunId()).stream()
                    .filter(w -> LIVE_SHEET.equalsIgnoreCase(w.sheetName()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("ASSETS sheet missing"));
            liveWorksheetId = assets.worksheetId();

            List<CandidateRow> onSheet = repo.selectCandidatesForParseRun(ingest.parseRunId())
                    .stream()
                    .filter(c -> c.worksheetId() == liveWorksheetId)
                    .toList();
            CandidateRow coverage = onSheet.stream()
                    .filter(c -> "coverage_parent".equals(c.candidateKind()))
                    .findFirst()
                    .orElseThrow();
            List<CandidateRow> narrow = onSheet.stream()
                    .filter(c -> !"coverage_parent".equals(c.candidateKind()))
                    .filter(c -> c.parentCandidateId() != null
                            && c.parentCandidateId() == coverage.candidateId())
                    .filter(c -> bboxArea(c) >= 12)
                    .sorted(Comparator
                            .comparingInt(RealWorkbookLiveEvidenceSmokeIT::bboxArea)
                            .reversed()
                            .thenComparingLong(CandidateRow::candidateId))
                    .limit(MAX_NARROW_CANDIDATES)
                    .toList();
            if (narrow.isEmpty()) {
                // Fall back to any reasonably sized locals if none are direct coverage children.
                narrow = onSheet.stream()
                        .filter(c -> !"coverage_parent".equals(c.candidateKind()))
                        .filter(c -> bboxArea(c) >= 12)
                        .sorted(Comparator
                                .comparingInt(RealWorkbookLiveEvidenceSmokeIT::bboxArea)
                                .reversed()
                                .thenComparingLong(CandidateRow::candidateId))
                        .limit(MAX_NARROW_CANDIDATES)
                        .toList();
            }
            if (narrow.isEmpty()) {
                narrow = onSheet.stream()
                        .filter(c -> !"coverage_parent".equals(c.candidateKind()))
                        .sorted(Comparator
                                .comparingInt(RealWorkbookLiveEvidenceSmokeIT::bboxArea)
                                .reversed()
                                .thenComparingLong(CandidateRow::candidateId))
                        .limit(MAX_NARROW_CANDIDATES)
                        .toList();
            }
            liveCandidateIds.add(coverage.candidateId());
            narrow.forEach(c -> liveCandidateIds.add(c.candidateId()));

            System.err.printf(Locale.ROOT,
                    "Evidence smoke: sheet=%s worksheetId=%d liveCandidates=%s%n",
                    LIVE_SHEET,
                    liveWorksheetId,
                    liveCandidateIds.stream().sorted().map(String::valueOf)
                            .collect(Collectors.joining(",")));
            for (CandidateRow c : onSheet) {
                if (!liveCandidateIds.contains(c.candidateId())) {
                    continue;
                }
                System.err.printf(Locale.ROOT,
                        "  candidate %d kind=%s bbox=[%s,%s]-[%s,%s] area=%d%n",
                        c.candidateId(),
                        c.candidateKind(),
                        c.bboxMinRow(),
                        c.bboxMinCol(),
                        c.bboxMaxRow(),
                        c.bboxMaxCol(),
                        bboxArea(c));
            }
            assertThat(liveCandidateIds).hasSize(1 + narrow.size());
            assertThat(narrow).isNotEmpty();
        }

        ClassifierLlm openRouter = LlmEnvironment.classifierOrUnconfigured();
        AtomicInteger liveLayerA = new AtomicInteger();
        AtomicInteger liveLayerB = new AtomicInteger();
        ClassifierLlm gated = new ClassifierLlm() {
            @Override
            public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
                long id = prompt.packet().candidateId();
                if (!liveCandidateIds.contains(id)) {
                    return new LayerAJudgment(
                            ScheduleFamily.ASSUMPTIONS, Triage.ORPHAN, Relevance.NOISE,
                            List.of(), List.of(), null);
                }
                int n = liveLayerA.incrementAndGet();
                System.err.printf("OpenRouter Layer A %d/%d candidate %d cheapPass=%s%n",
                        n, liveCandidateIds.size(), id, prompt.cheapPass());
                LayerAJudgment judgment = openRouter.classifyLayerA(prompt);
                System.err.printf("OpenRouter Layer A done candidate %d family=%s triage=%s%n",
                        id, judgment.scheduleFamily(), judgment.triage());
                return judgment;
            }

            @Override
            public List<LayerBLineJudgment> classifyLayerB(LayerBPrompt prompt) {
                long id = prompt.packet().candidateId();
                if (!liveCandidateIds.contains(id)) {
                    return List.of();
                }
                int n = liveLayerB.incrementAndGet();
                int amounts = LayerBAmountSupport.amountCells(prompt.packet()).size();
                System.err.printf("OpenRouter Layer B %d candidate %d amounts=%d%n",
                        n, id, amounts);
                List<LayerBLineJudgment> lines = openRouter.classifyLayerB(prompt);
                System.err.printf("OpenRouter Layer B done candidate %d lines=%d%n",
                        id, lines.size());
                return lines;
            }
        };

        long started = System.nanoTime();
        ClassifySummary summary = new ClassifyService(gated, new DiscoverService())
                .classify(db, ingest.parseRunId());
        long classifyMs = (System.nanoTime() - started) / 1_000_000L;

        assertThat(liveLayerA.get()).isEqualTo(liveCandidateIds.size());
        assertThat(summary.interpretationCount()).isGreaterThan(0);

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            assertThat(repo.countInterpretationsForParseRun(ingest.parseRunId()))
                    .isEqualTo(repo.countCellsForParseRun(ingest.parseRunId()));

            List<NomenclatureBinding> bindings = repo.selectNomenclatureBindingsForParseRun(
                    ingest.parseRunId());
            System.err.printf(Locale.ROOT,
                    "Evidence smoke done: classifyMs=%d liveA=%d liveB=%d bindings=%d interpretations=%d%n",
                    classifyMs,
                    liveLayerA.get(),
                    liveLayerB.get(),
                    bindings.size(),
                    summary.interpretationCount());

            // Prefer a bound money cell on ASSETS; fall back to any ASSETS numeric cell.
            String sampleCoord;
            if (!bindings.isEmpty()) {
                var sample = bindings.get(0);
                var cell = repo.selectCellPacketViews(List.of(sample.cellId())).get(0);
                String sheet = repo.selectWorksheetsForParseRun(ingest.parseRunId()).stream()
                        .filter(w -> w.worksheetId() == cell.worksheetId())
                        .findFirst()
                        .orElseThrow()
                        .sheetName();
                sampleCoord = sheet + "!" + cell.coord();
            } else {
                sampleCoord = anyAssetsCoord(repo, ingest.parseRunId(), liveWorksheetId);
            }

            CellMeaning meaning = new CellMeaningService()
                    .lookup(db, ingest.parseRunId(), sampleCoord);
            assertThat(meaning.interpretation())
                    .as("classified cell has interpretation")
                    .isNotNull();
            assertThat(meaning.evidence())
                    .as("classified cell has interpretation evidence")
                    .isNotEmpty();
            assertThat(meaning.evidence())
                    .as("row/column header roles present")
                    .anyMatch(e -> EvidenceRole.ROW_HEADER.equals(e.role())
                            || EvidenceRole.COLUMN_HEADER.equals(e.role()));
            assertThat(meaning.evidence())
                    .as("resolution vocabulary is populated")
                    .allMatch(e -> EvidenceResolution.RESOLVED.equals(e.resolution())
                            || EvidenceResolution.MISSING.equals(e.resolution())
                            || EvidenceResolution.AMBIGUOUS.equals(e.resolution()));

            long resolvedWithSource = meaning.evidence().stream()
                    .filter(e -> EvidenceResolution.RESOLVED.equals(e.resolution()))
                    .filter(e -> e.sourceCellId() != null)
                    .filter(e -> e.sourceText() != null && !e.sourceText().isBlank())
                    .count();
            System.err.printf(Locale.ROOT,
                    "sample %s status=%s evidence=%d resolvedWithSource=%d%n",
                    sampleCoord,
                    meaning.interpretation().nomenclatureStatus(),
                    meaning.evidence().size(),
                    resolvedWithSource);
            for (InterpretationEvidence e : meaning.evidence()) {
                System.err.printf(Locale.ROOT,
                        "  evidence role=%s res=%s text=%s norm=%s rule=%s%n",
                        e.role(),
                        e.resolution(),
                        e.sourceText(),
                        e.normalizedValue(),
                        e.ruleId());
            }
            if (!bindings.isEmpty()) {
                assertThat(resolvedWithSource)
                        .as("bound Om Arham sample should resolve at least one header with source")
                        .isGreaterThan(0);
            }
            // Prefer ASSETS!I9 when present: Civil Works ÷10^5 → lakh scale (formula divisor).
            try {
                CellMeaning i9 = new CellMeaningService()
                        .lookup(db, ingest.parseRunId(), "ASSETS!I9");
                if (i9.interpretation() != null
                        && i9.interpretation().formulaText() != null
                        && i9.interpretation().formulaText().contains("10^5")) {
                    assertThat(i9.evidence().stream()
                                    .filter(e -> EvidenceRole.SCALE.equals(e.role()))
                                    .toList())
                            .as("ASSETS!I9 /10^5 should yield lakh scale evidence")
                            .anyMatch(e -> "lakh".equals(e.normalizedValue())
                                    && EvidenceResolution.RESOLVED.equals(e.resolution()));
                    System.err.printf(Locale.ROOT,
                            "ASSETS!I9 formula=%s scale=%s%n",
                            i9.interpretation().formulaText(),
                            i9.evidence().stream()
                                    .filter(e -> EvidenceRole.SCALE.equals(e.role()))
                                    .map(e -> e.ruleId() + ":" + e.normalizedValue())
                                    .toList());
                }
            } catch (ClassifyException ignored) {
                // Sheet/coord may be absent on unexpected workbook variants.
            }

            // Spot-check a few more ASSETS cells for evidence coverage.
            int checked = 0;
            for (var cell : repo.selectCellsForWorksheet(liveWorksheetId)) {
                if (checked >= 5) {
                    break;
                }
                String q = LIVE_SHEET + "!" + cell.coord();
                CellMeaning m = new CellMeaningService().lookup(db, ingest.parseRunId(), q);
                assertThat(m.interpretation()).isNotNull();
                assertThat(m.evidence()).isNotEmpty();
                checked++;
            }
            assertThat(checked).isEqualTo(5);

            Path report = Path.of("target", "om-arham-live-evidence-smoke.txt");
            StringBuilder body = new StringBuilder();
            body.append("sheet=").append(LIVE_SHEET)
                    .append(" liveCandidates=").append(liveCandidateIds.size())
                    .append(" classifyMs=").append(classifyMs)
                    .append(" liveA=").append(liveLayerA.get())
                    .append(" liveB=").append(liveLayerB.get())
                    .append(" bindings=").append(bindings.size())
                    .append(" interpretations=").append(summary.interpretationCount())
                    .append(" sample=").append(sampleCoord)
                    .append('\n');
            for (InterpretationEvidence e : meaning.evidence()) {
                body.append(e.role()).append('\t')
                        .append(e.resolution()).append('\t')
                        .append(e.sourceText() == null ? "" : e.sourceText()).append('\t')
                        .append(e.normalizedValue() == null ? "" : e.normalizedValue())
                        .append('\n');
            }
            Files.createDirectories(report.getParent());
            Files.writeString(report, body.toString());
            System.err.println("Wrote " + report.toAbsolutePath());
        }
    }

    private static String anyAssetsCoord(
            WorkspaceRepository repo, long parseRunId, long worksheetId) throws Exception {
        var cells = repo.selectCellsForWorksheet(worksheetId);
        assertThat(cells).isNotEmpty();
        return LIVE_SHEET + "!" + cells.get(0).coord();
    }

    private static int bboxArea(CandidateRow c) {
        Integer minR = c.bboxMinRow();
        Integer maxR = c.bboxMaxRow();
        Integer minC = c.bboxMinCol();
        Integer maxC = c.bboxMaxCol();
        if (minR == null || maxR == null || minC == null || maxC == null) {
            return Integer.MAX_VALUE;
        }
        return (maxR - minR + 1) * (maxC - minC + 1);
    }
}
