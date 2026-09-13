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
                System.err.printf("OpenRouter Layer B %d candidate %d cells=%d%n",
                        liveLayerB.get(),
                        prompt.packet().candidateId(),
                        prompt.packet().cells().size());
                return openRouter.classifyLayerB(prompt);
            }
        };

        ClassifySummary summary = new ClassifyService(mixed).classify(db, ingest.parseRunId());
        assertThat(summary.dispositionCount()).isGreaterThan(liveCandidates.size());
        assertThat(liveLayerA.get()).isEqualTo(liveCandidates.size());
        assertThat(liveLayerB.get()).isGreaterThan(0);
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

            Path report = Path.of("target", "om-arham-live-layer-ab.txt");
            StringBuilder body = new StringBuilder();
            body.append("liveLayerA=").append(liveLayerA.get())
                    .append(" liveLayerB=").append(liveLayerB.get())
                    .append(" bindings=").append(bindings.size())
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
            double acAdd = repo.sumAddAmountsForPath(ingest.parseRunId(), HOTEL_AC_PATH);
            body.append("sumAdd(").append(HOTEL_AC_PATH).append(")=").append(acAdd).append('\n');
            Files.createDirectories(report.getParent());
            Files.writeString(report, body.toString());
            System.err.println("Wrote " + report.toAbsolutePath());
        }
    }
}
