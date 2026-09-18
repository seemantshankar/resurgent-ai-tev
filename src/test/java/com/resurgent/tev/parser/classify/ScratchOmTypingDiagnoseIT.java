package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.db.WorksheetRef;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import com.resurgent.tev.parser.nomenclature.NomenclatureCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Temporary offline diagnostic: walk each target cell's dependency chain up the
 * graph until the typing roots, so the fix is derived from the chain, not guessed.
 *
 * <p>Targets are the report's real cells: {@code P L} {@code J45}/{@code J53}/
 * {@code J33}/{@code D46}, the SALESPROJECTION {@code *(1+...)} cells, and the
 * {@code depreciation!J33} chain.
 */
class ScratchOmTypingDiagnoseIT {

    private static final Path WORKBOOK =
            Path.of("Project Docs", "OM Arham Ventures.xlsx");

    @Test
    void diagnose() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                "true".equalsIgnoreCase(System.getProperty("tev.diagnoseTyping")),
                "set -Dtev.diagnoseTyping=true to run the typing chain diagnostic");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(WORKBOOK));
        Path db = Path.of("target", "scratch-om-typing.db");
        Files.deleteIfExists(db);
        IngestSummary ingest = new IngestService().ingest(WORKBOOK, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            new NomenclatureCatalog(new WorkspaceRepository(workspace.connection()))
                    .confirmIndustry(1L, "hotel");
        }
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            Map<Long, String> sheetById = new HashMap<>();
            for (WorksheetRef sheet : repo.selectWorksheetsForParseRun(ingest.parseRunId())) {
                sheetById.put(sheet.worksheetId(), sheet.sheetName().trim());
            }
            CellGraph graph = new CellGraphBuilder().read(repo, ingest.parseRunId());
            CellTypes types = new TypePropagation().resolve(graph);

            Set<Long> targets = new LinkedHashSet<>();
            for (GraphCell cell : graph.cells().values()) {
                String name = sheetById.get(cell.worksheetId());
                if (!cell.numeric()) {
                    continue;
                }
                if ("P  L".equals(name)
                        && List.of("J45", "J53", "J33", "D46").contains(cell.coord())) {
                    targets.add(cell.cellId());
                } else if ("SALESPROJECTION".equals(name)
                        && List.of("J75", "F75").contains(cell.coord())) {
                    targets.add(cell.cellId());
                } else if ("depreciation".equals(name) && "J33".equals(cell.coord())) {
                    targets.add(cell.cellId());
                } else if ("ASSETS".equals(name) && "I9".equals(cell.coord())) {
                    targets.add(cell.cellId());
                }
            }

            Deque<Long> queue = new ArrayDeque<>(targets);
            Set<Long> seen = new LinkedHashSet<>(targets);
            List<String> lines = new ArrayList<>();
            while (!queue.isEmpty()) {
                long cellId = queue.poll();
                GraphCell cell = graph.cells().get(cellId);
                if (cell == null) {
                    continue;
                }
                lines.add(sheetById.get(cell.worksheetId()) + "!" + cell.coord()
                        + " id=" + cell.cellId()
                        + " rowLabel=" + cell.rowLabel()
                        + " display=" + cell.displayValue()
                        + " format=" + cell.numberFormat()
                        + " formula=" + cell.formulaText()
                        + " -> " + (cell.isFormula() ? "[derived] " : "[input] ")
                        + state(types.unitOf(cell.cellId()), types.refusalOf(cell.cellId()))
                        + " deps=" + graph.dependenciesOf(cell.cellId()).stream()
                                .map(dep -> dep.isConstant()
                                        ? dep.role() + ":const(" + dep.constant() + ")"
                                        : dep.isBarrier()
                                        ? dep.role() + ":barrier(" + dep.barrier() + ")"
                                        : dep.role() + ":" + dep.cellId() + "("
                                                + sheetById.get(graph.cells()
                                                        .get(dep.cellId()).worksheetId())
                                                + "!" + graph.cells().get(dep.cellId()).coord()
                                                + ")")
                                .toList());
                for (CellDependency dep : graph.dependenciesOf(cell.cellId())) {
                    if (dep.isConstant()) {
                        continue;
                    }
                    if (dep.isBarrier()) {
                        lines.add("    BARRIER " + dep.barrier() + " under "
                                + sheetById.get(cell.worksheetId()) + "!" + cell.coord());
                        continue;
                    }
                    if (seen.add(dep.cellId())) {
                        queue.add(dep.cellId());
                    }
                }
            }
            lines.forEach(System.out::println);
            System.out.println("ancestors=" + seen.size());
            for (Aggregation aggregation : graph.aggregations()) {
                if (!seen.contains(aggregation.headCellId())) {
                    continue;
                }
                GraphCell head = graph.cells().get(aggregation.headCellId());
                StringBuilder row = new StringBuilder("AGG ")
                        .append(sheetById.get(head.worksheetId())).append('!')
                        .append(head.coord()).append(" <-");
                for (Aggregation.Member member : aggregation.members()) {
                    GraphCell memberCell = graph.cells().get(member.cellId());
                    if (memberCell == null) {
                        continue;
                    }
                    row.append(' ')
                            .append(sheetById.get(memberCell.worksheetId())).append('!')
                            .append(memberCell.coord()).append(':')
                            .append(types.unitOf(member.cellId())
                                    .map(u -> u.kind().name()).orElseGet(() ->
                                            types.refusalOf(member.cellId())
                                                    .map(r -> "R:" + r).orElse("unsettled")));
                }
                System.out.println(row);
            }
        }
        Files.deleteIfExists(db);
    }

    private static String state(Optional<ResolvedUnit> unit, Optional<UnboundReason> refusal) {
        if (unit.isPresent()) {
            return unit.get().kind() + "/" + unit.get().scale();
        }
        return refusal.map(r -> "REFUSED:" + r).orElse("UNSETTLED");
    }
}
