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
    private static final String HOTEL_AC_PATH =
            "Project Cost > Plant & Machinery > Air Conditioning";

    /**
     * Named Om Arham bindings on the P&amp;L and depreciation tabs that defeated the
     * layout approach. Path fragments stay short: a derived soft leaf is named from
     * the workbook's own row label, so the exact leaf spelling is data, not an
     * ontology guarantee. Insurance is asserted through the J45/J53 cells below: the
     * "Insurance Charges" caption row 44 holds no numbers, while the premium money
     * sits in rows 45-48 under component labels (Building, ...), so a caption
     * expectation can never match and must not pretend to gate quality.
     */
    private static final List<ExpectedBinding> EXPECTED = List.of(
            new ExpectedBinding("Depreciation", AmountRole.ADD, "Depreciation"));

    @TempDir
    Path tempDir;

    @Test
    void liveLayerAAndLayerBOnOmArhamProfitAndDepreciationSheets() throws Exception {
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
                if (isLiveSheet(sheet.sheetName())) {
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
                LayerAJudgment judgment = openRouter.classifyLayerA(prompt);
                System.err.printf("OpenRouter Layer A done candidate %d%n",
                        prompt.packet().candidateId());
                return judgment;
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
                List<LayerBLineJudgment> lines = openRouter.classifyLayerB(prompt);
                System.err.printf("OpenRouter Layer B done candidate %d lines=%d%n",
                        prompt.packet().candidateId(), lines.size());
                return lines;
            }
        };

        long classifyStarted = System.nanoTime();
        // Use production hang budgets so a stalled OpenRouter send fails closed
        // inside the 5-minute wall gate instead of sitting for 10–20 minutes.
        ClassifySummary summary = new ClassifyService(mixed, new DiscoverService())
                .classify(db, ingest.parseRunId());
        long classifyMs = (System.nanoTime() - classifyStarted) / 1_000_000L;
        assertThat(summary.dispositionCount()).isGreaterThan(liveCandidates.size());
        assertThat(liveLayerA.get()).isEqualTo(liveCandidates.size());
        assertThat(liveLayerB.get())
                .as("the naming questions for live-sheet labels must be asked from the "
                        + "live sheets' own packets, not stubbed via a Case_II sheet")
                .isGreaterThan(1);
        assertThat(summary.bindingCount()).isGreaterThan(0);

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

            long cellCount = repo.countCellsForParseRun(ingest.parseRunId());
            assertThat(summary.interpretationCount())
                    .as("interpretation coverage equals persisted cells")
                    .isEqualTo((int) cellCount);
            assertThat(repo.countInterpretationsForParseRun(ingest.parseRunId()))
                    .isEqualTo(cellCount);
            List<CellInterpretation> interpretations =
                    repo.selectCellInterpretationsForParseRun(ingest.parseRunId());
            assertThat(interpretations)
                    .as("status vocabulary covers Layer B outcomes")
                    .anyMatch(row -> NomenclatureStatus.BOUND.equals(row.nomenclatureStatus()))
                    .anyMatch(row -> NomenclatureStatus.UNBOUND.equals(row.nomenclatureStatus())
                            || NomenclatureStatus.NOT_APPLICABLE.equals(row.nomenclatureStatus()));
            assertThat(interpretations.stream()
                            .filter(row -> NomenclatureStatus.BOUND.equals(row.nomenclatureStatus()))
                            .toList())
                    .as("bound rows carry lean Layer B snapshot")
                    .isNotEmpty()
                    .allMatch(row -> row.nomenclaturePath() != null
                            && !row.nomenclaturePath().isBlank()
                            && row.amountRole() != null
                            && AmountRole.isKnown(row.amountRole())
                            && row.softLeaf() != null
                            && row.viaAlias() != null);

            NomenclatureBinding sampleBinding = bindings.get(0);
            var sampleCell = repo.selectCellPacketViews(List.of(sampleBinding.cellId())).get(0);
            String sheetName = repo.selectWorksheetsForParseRun(ingest.parseRunId()).stream()
                    .filter(w -> w.worksheetId() == sampleCell.worksheetId())
                    .findFirst()
                    .orElseThrow()
                    .sheetName();
            String qualified = sheetName + "!" + sampleCell.coord();
            CellMeaning meaning = new CellMeaningService()
                    .lookup(db, ingest.parseRunId(), qualified);
            assertThat(meaning.interpretation()).isNotNull();
            assertThat(meaning.interpretation().nomenclatureStatus())
                    .isEqualTo(NomenclatureStatus.BOUND);
            assertThat(meaning.interpretation().nomenclaturePath())
                    .isEqualTo(sampleBinding.path());

            // Diagnostic only: AC on this sheet is a Less/quotation deduction, so an
            // add rollup is not a quality gate (and must not become one for other books).
            double acAdd = repo.sumAddAmountsForPath(ingest.parseRunId(), HOTEL_AC_PATH);
            int expectedMatched = 0;
            for (ExpectedBinding expected : EXPECTED) {
                if (bindings.stream().anyMatch(expected::matches)) {
                    expectedMatched++;
                }
            }

            Path report = Path.of("target", "om-arham-live-layer-ab.txt");
            StringBuilder body = new StringBuilder();
            body.append("interpretations=").append(summary.interpretationCount())
                    .append(" cells=").append(cellCount)
                    .append(" bound=")
                    .append(interpretations.stream()
                            .filter(row -> NomenclatureStatus.BOUND.equals(row.nomenclatureStatus()))
                            .count())
                    .append(" unbound=")
                    .append(interpretations.stream()
                            .filter(row -> NomenclatureStatus.UNBOUND.equals(row.nomenclatureStatus()))
                            .count())
                    .append(" not_applicable=")
                    .append(interpretations.stream()
                            .filter(row -> NomenclatureStatus.NOT_APPLICABLE.equals(
                                    row.nomenclatureStatus()))
                            .count())
                    .append(" sampleMeaning=").append(qualified)
                    .append('\n');
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
                            .append('\t').append(metric.reasoningTokens())
                            .append('\t').append(metric.visibleContentChars())
                            .append('\t').append(metric.finishReason())
                            .append('\t').append(metric.truncated())
                            .append('\t').append(metric.durationMs())
                            .append('\t').append(metric.parseAttempts())
                            .append('\t').append(metric.httpAttempts())
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
                        .append(" (Layer A/B may overlap; sums are not wall clock)")
                        .append(" layerAAttempts=").append(layerAAttempts)
                        .append(" layerBAttempts=").append(layerBAttempts)
                        .append('\n');
                if (layerAMs > layerBMs) {
                    body.append("note: Layer A token-time still dominates summed duration;"
                            + " wall clock should overlap A/B via the classify pool\n");
                }
            }
            body.append("quality expectedMatched=").append(expectedMatched)
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
                    "Live quality: %d ms, %d bindings, %d interpretations/%d cells, %s, expected %d/%d named bindings%n",
                    classifyMs,
                    bindings.size(),
                    summary.interpretationCount(),
                    cellCount,
                    summary.layerBStats().summaryLine(),
                    expectedMatched,
                    EXPECTED.size());
            for (ExpectedBinding expected : EXPECTED) {
                assertThat(bindings)
                        .as("expected binding path~%s role=%s label~%s",
                                expected.pathContains(), expected.role(), expected.verbatimContains())
                        .anyMatch(expected::matches);
            }
            NomenclatureBinding j45 = bindingAt(repo, ingest.parseRunId(), "P  L", "J45");
            assertThat(j45)
                    .as("'P  L '!J45 must bind as a live naming question, not stay silent")
                    .isNotNull();
            assertThat(j45.path())
                    .doesNotContain("Civil Works > Building");
            NomenclatureBinding j53 = bindingAt(repo, ingest.parseRunId(), "P  L", "J53");
            assertThat(j53)
                    .as("'P  L '!J53 must bind rather than stay unbound")
                    .isNotNull();
            NomenclatureBinding j33 = bindingAt(repo, ingest.parseRunId(), "depreciation", "J33");
            assertThat(j33)
                    .as("depreciation!J33 must bind under depreciation, not Furniture")
                    .isNotNull();
            assertThat(j33.path().toLowerCase(Locale.ROOT)).doesNotContain("furniture");
            assertThat(classifyMs)
                    .as("Om Arham P L + depreciation classify wall vs 5-minute gate")
                    .isLessThan(300_000L);
        }

        new DiscoverService().discover(db, ingest.parseRunId());
        assertThat(new CellMeaningService().lookup(db, ingest.parseRunId(),
                        findAnyQualifiedCoord(db, ingest.parseRunId())).interpretation())
                .as("successful rediscover clears interpretations")
                .isNull();
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            assertThat(new WorkspaceRepository(workspace.connection())
                            .countInterpretationsForParseRun(ingest.parseRunId()))
                    .isZero();
        }
    }

    private static boolean isLiveSheet(String sheetName) {
        if (sheetName == null) {
            return false;
        }
        String trimmed = sheetName.trim();
        if (trimmed.equalsIgnoreCase("depreciation")) {
            return true;
        }
        return trimmed.replace(" ", "").equalsIgnoreCase("PL");
    }

    private static NomenclatureBinding bindingAt(
            WorkspaceRepository repo, long parseRunId, String sheetName, String coord)
            throws Exception {
        Long worksheetId = repo.selectWorksheetsForParseRun(parseRunId).stream()
                .filter(sheet -> isLiveSheet(sheet.sheetName())
                        && (sheetName.trim().equalsIgnoreCase(sheet.sheetName().trim())
                                || sheetName.replace(" ", "")
                                        .equalsIgnoreCase(sheet.sheetName().replace(" ", ""))))
                .map(WorksheetRef::worksheetId)
                .findFirst()
                .orElse(null);
        if (worksheetId == null) {
            return null;
        }
        Long cellId = repo.selectInterpretationCellsForParseRun(parseRunId).stream()
                .filter(cell -> cell.worksheetId() == worksheetId
                        && coord.equalsIgnoreCase(cell.coord()))
                .map(com.resurgent.tev.parser.db.InterpretationCellView::cellId)
                .findFirst()
                .orElse(null);
        if (cellId == null) {
            return null;
        }
        return repo.selectNomenclatureBindingsForParseRun(parseRunId).stream()
                .filter(binding -> binding.cellId() == cellId)
                .findFirst()
                .orElse(null);
    }

    private static String findAnyQualifiedCoord(Path db, long parseRunId) throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            WorksheetRef sheet = repo.selectWorksheetsForParseRun(parseRunId).get(0);
            String coord = repo.selectCellsForWorksheet(sheet.worksheetId()).get(0).coord();
            return sheet.sheetName() + "!" + coord;
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
