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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Measures the cell graph against the working client FM: how many numeric cells are
 * hardcoded inputs, how many decisions remain once a repeated row-series collapses,
 * and how many aggregations and distinct group names there are. Skips when the
 * workbook is absent. Bounds are deliberately loose — this asserts the shape of the
 * workload, not a golden number.
 */
class CellGraphMeasurementIT {

    private static final Path WORKBOOK = Path.of("Project Docs", "OM Arham Ventures.xlsx");

    @TempDir
    Path tempDir;

    @Test
    void theGraphFindsFarFewerDecisionsThanThereAreNumericCells() throws Exception {
        assumeTrue(Files.exists(WORKBOOK), "Working workbook not found; skipping.");
        Path db = tempDir.resolve("graph-measure.db");
        IngestSummary ingest = new IngestService().ingest(WORKBOOK, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<InterpretationCellView> cells =
                    repo.selectInterpretationCellsForParseRun(ingest.parseRunId());
            CellGraph graph = new CellGraphBuilder().build(
                    ingest.parseRunId(),
                    cells,
                    repo.selectCellReferencesForParseRun(ingest.parseRunId()));

            long numeric = graph.cells().values().stream().filter(GraphCell::numeric).count();
            Set<String> inputDecisions = new LinkedHashSet<>();
            long labelledInputs = 0;
            for (InputCell input : graph.inputs()) {
                inputDecisions.add(input.seriesKey());
                if (input.rowLabel() != null && !input.rowLabel().isBlank()) {
                    labelledInputs++;
                }
            }
            Set<String> groupNames = new LinkedHashSet<>();
            for (Aggregation aggregation : graph.aggregations()) {
                groupNames.add(aggregation.worksheetId() + "|"
                        + (aggregation.headLabel() == null ? "" : aggregation.headLabel()));
            }

            CellTypes types = new TypePropagation().resolve(graph);
            long typedNumeric = graph.cells().values().stream()
                    .filter(GraphCell::numeric)
                    .filter(cell -> types.unitOf(cell.cellId()).isPresent())
                    .count();
            long settled = graph.cells().values().stream()
                    .filter(GraphCell::numeric)
                    .filter(cell -> types.unitOf(cell.cellId()).isPresent()
                            || types.refusalOf(cell.cellId()).isPresent())
                    .count();
            long moneyAggregations = graph.aggregations().stream()
                    .filter(aggregation -> types.unitOf(aggregation).isMoney())
                    .count();

            java.util.Map<UnboundReason, Long> reasons = new java.util.LinkedHashMap<>();
            for (GraphCell cell : graph.cells().values()) {
                if (!cell.numeric()) {
                    continue;
                }
                types.refusalOf(cell.cellId())
                        .ifPresent(reason -> reasons.merge(reason, 1L, Long::sum));
            }
            System.out.println("cell-graph refusals: " + reasons);
            System.out.println("cell-graph typing:"
                    + " typed=" + typedNumeric
                    + " ofNumeric=" + numeric
                    + " settled=" + settled
                    + " moneyAggregations=" + moneyAggregations);
            System.out.println("cell-graph measurement:"
                    + " numericCells=" + numeric
                    + " inputs=" + graph.inputs().size()
                    + " inputDecisions=" + inputDecisions.size()
                    + " labelledInputs=" + labelledInputs
                    + " aggregations=" + graph.aggregations().size()
                    + " groupNames=" + groupNames.size()
                    + " driverOnly=" + graph.driverOnly().size());

            assertThat(numeric).isGreaterThan(1_000);
            assertThat(graph.inputs()).as("inputs are a minority of numeric cells")
                    .hasSizeLessThan((int) numeric);
            assertThat(inputDecisions.size())
                    .as("collapsing a repeated row-series cuts the decisions sharply")
                    .isLessThan(graph.inputs().size());
            assertThat(graph.aggregations()).isNotEmpty();
            assertThat(groupNames.size())
                    .as("groups to name are far fewer than aggregations")
                    .isLessThan(graph.aggregations().size());
            assertThat(labelledInputs)
                    .as("most inputs carry a row label")
                    .isGreaterThan(graph.inputs().size() / 2);
            assertThat(settled)
                    .as("every numeric cell ends with either a unit or a reason")
                    .isEqualTo(numeric);
            assertThat(typedNumeric)
                    .as("propagation reaches well beyond the inputs it started from")
                    .isGreaterThan(graph.inputs().size());
            assertThat(moneyAggregations).isNotZero();
        }
    }
}
