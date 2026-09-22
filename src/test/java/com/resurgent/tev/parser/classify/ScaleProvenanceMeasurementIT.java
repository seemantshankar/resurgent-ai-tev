package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.resurgent.tev.parser.db.InterpretationCellView;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves scale provenance against the working client FM: the four bound project-cost
 * deductions that used to be asserted at unit scale adopt lakh from the estimate they
 * subtract from, and every additive triplet the graph can verify reconciles after
 * normalisation. Skips when the workbook is absent.
 */
class ScaleProvenanceMeasurementIT {

    private static final Path WORKBOOK = Path.of("Project Docs", "OM Arham Ventures.xlsx");

    /** ASSETS project-cost deductions whose own evidence names no scale. */
    private static final Set<String> ADOPTED_DEDUCTIONS =
            Set.of("J54", "J55", "J57", "J60");

    @TempDir
    Path tempDir;

    @Test
    void projectCostDeductionsAdoptTheEstimateScaleAndTripletsReconcile() throws Exception {
        assumeTrue(Files.exists(WORKBOOK), "workbook absent");
        Path db = tempDir.resolve("scale-provenance.db");
        IngestSummary ingest = new IngestService().ingest(WORKBOOK, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<InterpretationCellView> cells =
                    repo.selectInterpretationCellsForParseRun(ingest.parseRunId());
            CellGraph graph = new CellGraphBuilder().build(
                    ingest.parseRunId(), cells,
                    repo.selectCellReferencesForParseRun(ingest.parseRunId()));
            CellTypes types = new TypePropagation().resolve(graph);

            long assetsSheet = graph.cells().values().stream()
                    .filter(cell -> "J54".equals(cell.coord()))
                    .findFirst()
                    .orElseThrow()
                    .worksheetId();

            Map<String, GraphCell> assets = new java.util.LinkedHashMap<>();
            for (GraphCell cell : graph.cells().values()) {
                if (cell.worksheetId() == assetsSheet
                        && ADOPTED_DEDUCTIONS.contains(cell.coord())) {
                    assets.put(cell.coord(), cell);
                }
            }
            assertThat(assets.keySet()).containsExactlyInAnyOrderElementsOf(ADOPTED_DEDUCTIONS);
            for (GraphCell cell : assets.values()) {
                ResolvedUnit unit = types.unitOf(cell.cellId()).orElseThrow();
                assertThat(unit.kind()).isEqualTo(CellKind.MONEY);
                assertThat(unit.scale())
                        .as("%s is lakh, not the unit default it used to assert", cell.coord())
                        .isEqualTo(CellScale.LAKH);
                assertThat(types.scaleProvenanceOf(cell.cellId()))
                        .as("%s adopted its scale from the estimate it subtracts from", cell.coord())
                        .contains(ScaleProvenance.ADOPTED);
            }

            AggregationReconciliation.Report report = AggregationReconciliation.check(graph, types);
            assertThat(report.checked())
                    .as("the check verifies real additive triplets, not a vacuous set")
                    .isGreaterThan(10);
            assertThat(report.mismatches())
                    .as("every verifiable additive triplet reconciles after normalisation")
                    .isEmpty();
        }
    }
}
