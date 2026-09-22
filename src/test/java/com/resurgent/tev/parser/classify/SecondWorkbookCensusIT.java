package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.InterpretationCellView;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the graph typing, scale adoption and reconciliation against client financial
 * models other than the one these rules were written on, and prints the refusal census
 * and binding outcome per workbook. That is the only test of whether the rules
 * generalise: a rule justified only by Om Arham is fit to that workbook, not a rule.
 *
 * <p>Offline. The LLM is the recording fake, so what binds here is what the graph
 * proves on its own; coverage beyond that needs the live naming question and is not
 * measured. Workbooks are skipped individually when the client files at
 * {@code Project Docs/} are absent.
 */
class SecondWorkbookCensusIT {

    private static final List<Path> WORKBOOKS = List.of(
            Path.of("Project Docs",
                    "FM_Solar Project - 61.50 Consolidated 170224_Working_NRJ_Draft_Sent"
                            + " to Client.xlsx"),
            Path.of("Project Docs", "Jettwings_F.Model_CMA_final 2.xlsx"),
            Path.of("Project Docs", "SA hospitalities pvt ltd resort - CLient FM.xlsx"));

    @TempDir
    Path tempDir;

    @Test
    void theRulesHoldOnUnseenClientModels() throws Exception {
        String only = System.getProperty("tev.census.workbook", "");
        int ran = 0;
        for (Path workbook : WORKBOOKS) {
            if (!Files.exists(workbook)) {
                System.out.println("SKIP (absent): " + workbook);
                continue;
            }
            if (!only.isBlank() && !workbook.getFileName().toString().contains(only)) {
                continue;
            }
            ran++;
            census(workbook);
        }
        assertThat(ran).as("at least one second workbook is present").isGreaterThan(0);
    }

    private void census(Path workbook) throws Exception {
        String name = workbook.getFileName().toString();
        long started = System.currentTimeMillis();
        Path db = tempDir.resolve(name.replaceAll("[^A-Za-z0-9]+", "-") + ".db");
        IngestSummary ingest = new IngestService().ingest(workbook, 1L, db);
        long parseRunId = ingest.parseRunId();
        System.out.println("=== " + name + " ===");
        System.out.println("  ingest=" + (System.currentTimeMillis() - started) + "ms");

        long discoverAt = System.currentTimeMillis();
        new DiscoverService().discover(db, parseRunId);
        System.out.println("  discover=" + (System.currentTimeMillis() - discoverAt) + "ms");

        if (!"true".equals(System.getProperty("tev.census.skipClassify"))) {
            long classifyAt = System.currentTimeMillis();
            new ClassifyService(new RealWorkbookClassifyIT.RecordingLlm(Set.of()))
                    .classify(db, parseRunId);
            System.out.println("  classify=" + (System.currentTimeMillis() - classifyAt) + "ms");
        }

        long graphAt = System.currentTimeMillis();

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<InterpretationCellView> cells =
                    repo.selectInterpretationCellsForParseRun(parseRunId);
            CellGraph graph = new CellGraphBuilder().build(
                    parseRunId, cells, repo.selectCellReferencesForParseRun(parseRunId));
            CellTypes types = new TypePropagation().resolve(graph);
            System.out.println("  graph+types=" + (System.currentTimeMillis() - graphAt) + "ms");

            long numeric = graph.cells().values().stream().filter(GraphCell::numeric).count();
            long typed = graph.cells().values().stream()
                    .filter(GraphCell::numeric)
                    .filter(cell -> types.unitOf(cell.cellId()).isPresent())
                    .count();

            Map<UnboundReason, Long> refusals = new LinkedHashMap<>();
            for (GraphCell cell : graph.cells().values()) {
                if (!cell.numeric()) {
                    continue;
                }
                types.refusalOf(cell.cellId())
                        .ifPresent(reason -> refusals.merge(reason, 1L, Long::sum));
            }

            Map<ScaleProvenance, Long> moneyProvenance = new LinkedHashMap<>();
            long moneyTyped = 0;
            for (GraphCell cell : graph.cells().values()) {
                if (!cell.numeric()) {
                    continue;
                }
                CellTypes.Typed row = types.typed().get(cell.cellId());
                if (row == null || !row.unit().isMoney()) {
                    continue;
                }
                moneyTyped++;
                moneyProvenance.merge(row.scaleProvenance(), 1L, Long::sum);
            }

            AggregationReconciliation.Report report =
                    AggregationReconciliation.check(graph, types);
            List<String> mismatchDetail = new ArrayList<>();
            for (AggregationReconciliation.Mismatch mismatch : report.mismatches()) {
                GraphCell head = graph.cells().get(mismatch.headCellId());
                mismatchDetail.add((head == null ? "?" : head.coord())
                        + "[head=" + mismatch.headNormalized()
                        + " members=" + mismatch.membersNormalized() + "]");
            }

            System.out.println("=== " + name + " ===");
            System.out.println("  numeric=" + numeric + " typed=" + typed
                    + " moneyTyped=" + moneyTyped + " moneyProvenance=" + moneyProvenance);
            System.out.println("  refusals=" + refusals);
            System.out.println("  reconciliation: checked=" + report.checked()
                    + " skipped=" + report.skipped()
                    + " mismatches=" + report.mismatches().size()
                    + (mismatchDetail.isEmpty() ? "" : " " + mismatchDetail));

            Map<String, Long> bySource = new LinkedHashMap<>();
            Map<String, Long> byRole = new LinkedHashMap<>();
            Set<String> boundRows = new java.util.HashSet<>();
            List<NomenclatureBinding> bindings =
                    repo.selectNomenclatureBindingsForParseRun(parseRunId);
            Map<Long, InterpretationCellView> byCell = new LinkedHashMap<>();
            for (InterpretationCellView cell : cells) {
                byCell.put(cell.cellId(), cell);
            }
            for (NomenclatureBinding binding : bindings) {
                bySource.merge(binding.source(), 1L, Long::sum);
                byRole.merge(binding.amountRole(), 1L, Long::sum);
                InterpretationCellView cell = byCell.get(binding.cellId());
                if (cell != null) {
                    boundRows.add(cell.worksheetId() + "!" + cell.rowNum());
                }
            }
            Map<String, Long> unboundReasons = new LinkedHashMap<>();
            long unboundInBoundRow = 0;
            for (CellInterpretation row : repo.selectCellInterpretationsForParseRun(parseRunId)) {
                if (!NomenclatureStatus.UNBOUND.equals(row.nomenclatureStatus())) {
                    continue;
                }
                unboundReasons.merge(row.unboundReason(), 1L, Long::sum);
                InterpretationCellView cell = byCell.get(row.cellId());
                if (cell != null && cell.numericValue() != null
                        && boundRows.contains(cell.worksheetId() + "!" + cell.rowNum())) {
                    unboundInBoundRow++;
                }
            }
            System.out.println("  bindings=" + bindings.size()
                    + " bySource=" + bySource + " byRole=" + byRole
                    + " boundRows=" + boundRows.size()
                    + " unboundNumericInABoundRow=" + unboundInBoundRow);
            System.out.println("  unboundReasons=" + unboundReasons);
        }
    }
}
