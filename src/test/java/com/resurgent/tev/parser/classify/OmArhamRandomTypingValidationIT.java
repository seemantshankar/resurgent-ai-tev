package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.resurgent.tev.parser.db.InterpretationCellView;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.db.WorksheetRef;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import com.resurgent.tev.parser.nomenclature.NomenclatureCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Typing validation across all tabs in Om Arham, using both pre-determined
 * critical cells and random sampling to ensure typing works end-to-end.
 *
 * <p>Disabled unless {@code -Dtev.validateTyping=true} to avoid long offline runs.
 * Tests all tabs (not just Visible/P&L/Depreciation) and validates:
 * <ul>
 *   <li>Input cells type correctly at the graph level
 *   <li>Derived cells resolve their type from dependencies
 *   <li>Known parser gaps are logged for fix validation
 *   <li>Random cell sampling catches regressions across tabs
 * </ul>
 *
 * <p>Correction (verified 2026-09-18, post Gap 1/Gap 2 fixes): Gap 1
 * (sheet-prefixed multiplicative terms) and Gap 2 (power-of-10 divisors) are
 * fixed and locked by {@code CellGraphBuilderTest}. Gap 3 as originally written
 * — "the edge to {@code D75} is lost" — was refuted: {@code parse()} places every
 * edge whose offset lies inside the term span, so {@code I75*(1+D75)} already
 * yields {@code const(1.0) FACTOR}, {@code I75 FACTOR} and {@code D75 FACTOR}.
 * The growth-factor pattern below is informational only, not a defect. The
 * remaining refusals on the {@code J45}/{@code J53}/{@code J33} chains are real
 * kind conflicts from upstream mixed-kind sums (e.g. a quantity {@code Details!E152}
 * summed with money, a quantity {@code SALESPROJECTION!F15} summed with money,
 * {@code RATE+MONEY} aggregates), plus a {@code PERCENT} rate driver
 * ({@code P L!B45}/{@code B46}) money-multiplied downstream — not lost edges.
 * Row 45 is "Building" and row 33 is "F & B sales"; the Gap 1 evidence sits on
 * row 46 ({@code P L!D46}), not row 45.
 */
class OmArhamRandomTypingValidationIT {

    private static final Path WORKBOOK =
            Path.of("Project Docs", "OM Arham Ventures.xlsx");

    private static final int RANDOM_SAMPLE_SIZE = 50;

    @Test
    void validateTypingAcrossAllTabs() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getProperty("tev.validateTyping")),
                "set -Dtev.validateTyping=true to run comprehensive typing validation");
        assumeTrue(Files.exists(WORKBOOK),
                "Working workbook not found at " + WORKBOOK.toAbsolutePath());

        Path db = Path.of("target", "om-arham-type-validation.db");
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

            // Build the graph and type system
            CellGraph graph = new CellGraphBuilder().read(repo, ingest.parseRunId());
            CellTypes types = new TypePropagation().resolve(graph);

            // Track validation results
            TypeValidationResults results = new TypeValidationResults();

            // 1. Validate pre-determined critical cells
            validateCriticalCells(graph, types, sheetById, results);

            // 2. Validate inputs: all input cells should have a type or be unsettled
            validateInputCells(graph, types, results);

            // 3. Random sampling across all tabs
            validateRandomSampling(
                    repo, ingest.parseRunId(), graph, types, sheetById, results);

            // 4. Log parser gap candidates for upstream fix
            logParserGapCandidates(graph, types, sheetById, results);

            results.summarize();
            double inputCoveragePct = (100.0 * results.getInputsTyped()) / Math.max(1, results.getInputsTotal());
            System.out.printf("Input type coverage: %.1f%% (%d/%d)%n",
                    inputCoveragePct, results.getInputsTyped(), results.getInputsTotal());
            assertThat(inputCoveragePct)
                    .as("input coverage should hold at/above the measured 79.9%%"
                            + " (remainder is external links and orphaned labels)")
                    .isGreaterThanOrEqualTo(79.9);
        }
        Files.deleteIfExists(db);
    }

    private void validateCriticalCells(
            CellGraph graph,
            CellTypes types,
            Map<Long, String> sheetById,
            TypeValidationResults results) {
        System.out.println("\n=== Critical Cells (Verified Outcomes) ===");
        // P L!D46's Gap 1 parse (B46*'CAPITAL COST'!D20 multiplicative) is locked by
        // CellGraphBuilderTest. The head can no longer be asserted MONEY: the engine no
        // longer freezes a subset reading, and 'CAPITAL COST'!D20 resolves through
        // ASSETS!I62, a genuine mixed-kind sum (an item row whose label states "Nos."
        // summed with money). The chain refuses rather than guess.
        validateCellForDiagnostics(graph, types, sheetById, results, "P  L", "D46",
                "Refuses KIND_CONFLICT upstream (CAPITAL COST!D20 -> ASSETS!I62 mixed-kind sum)");
        // P L!D23/J23/J33/I25 are the poisoned cells this pass fixes: each was PERCENT
        // (a product typed from its percent factor alone). They must now be MONEY.
        validateCellType(graph, types, sheetById, results, "P  L", "D23", true, CellKind.MONEY);
        validateCellType(graph, types, sheetById, results, "P  L", "J23", true, CellKind.MONEY);
        validateCellType(graph, types, sheetById, results, "P  L", "J33", true, CellKind.MONEY);
        validateCellType(graph, types, sheetById, results, "P  L", "I25", true, CellKind.MONEY);
        // ASSETS!I9 is the Gap 2 lock: F21/10^5 is one 100000 divisor, MONEY/LAKH.
        validateCellType(graph, types, sheetById, results, "ASSETS", "I9", true, CellKind.MONEY);
        validateCellType(graph, types, sheetById, results, "ASSETS", "F21", true, CellKind.MONEY);
        // SALESPROJECTION growth-factor chain: the D75 edge is present (Gap 3
        // refuted), but the chain refuses on a genuine upstream KIND_CONFLICT
        // (quantity summed with money), so these stay diagnostic.
        validateCellForDiagnostics(graph, types, sheetById, results, "SALESPROJECTION", "J75",
                "Growth-factor pattern: D75 edge present; upstream KIND_CONFLICT is genuine");
        // J45/J53/depreciation!J33 refuse on genuine upstream kind conflicts, not
        // lost edges: J45 inherits PERCENT from the B45 rate driver, J53 and J33
        // sit under mixed-kind sums. Diagnostic until the model handles them.
        validateCellForDiagnostics(graph, types, sheetById, results, "P  L", "J45",
                "PERCENT via B45 rate driver; upstream KIND_CONFLICT chain");
        validateCellForDiagnostics(graph, types, sheetById, results, "P  L", "J53",
                "Downstream of depreciation!J57 KIND_CONFLICT");
        validateCellForDiagnostics(graph, types, sheetById, results, "depreciation", "J33",
                "Downstream of D132 KIND_CONFLICT (RATE+MONEY aggregate)");
        validateCellForDiagnostics(graph, types, sheetById, results, "P  L", "B45",
                "Input rate driver (percent format), not a defect");
    }

    private void validateCellForDiagnostics(
            CellGraph graph,
            CellTypes types,
            Map<Long, String> sheetById,
            TypeValidationResults results,
            String sheetName,
            String coord,
            String expectedGapDescription) {
        Long cellId = findCell(graph, sheetById, sheetName, coord);
        if (cellId == null) {
            System.out.printf("  %s!%s NOT FOUND%n", sheetName, coord);
            results.recordMissing(sheetName, coord);
            return;
        }

        GraphCell cell = graph.cells().get(cellId);
        Optional<ResolvedUnit> unit = types.unitOf(cellId);
        Optional<UnboundReason> refusal = types.refusalOf(cellId);

        String state = unit.isPresent()
                ? unit.get().kind() + "/" + unit.get().scale()
                : refusal.map(r -> "REFUSED:" + r).orElse("UNSETTLED");

        System.out.printf("  %s!%s -> %s (formula=%s) [%s]%n",
                sheetName, coord, state, cell.formulaText(), expectedGapDescription);
        results.recordAttempted(sheetName, coord, state, cell.isFormula());
    }

    private void validateCellType(
            CellGraph graph,
            CellTypes types,
            Map<Long, String> sheetById,
            TypeValidationResults results,
            String sheetName,
            String coord,
            boolean shouldType,
            CellKind expectedKind) {
        Long cellId = findCell(graph, sheetById, sheetName, coord);
        if (cellId == null) {
            results.recordMissing(sheetName, coord);
            return;
        }

        GraphCell cell = graph.cells().get(cellId);
        Optional<ResolvedUnit> unit = types.unitOf(cellId);
        Optional<UnboundReason> refusal = types.refusalOf(cellId);

        String state = unit.isPresent()
                ? unit.get().kind() + "/" + unit.get().scale()
                : refusal.map(r -> "REFUSED:" + r).orElse("UNSETTLED");

        if (shouldType) {
            assertThat(unit)
                    .as("%s!%s should type but got %s (formula=%s)",
                            sheetName, coord, state, cell.formulaText())
                    .isPresent();
            if (expectedKind != null) {
                assertThat(unit.get().kind())
                        .as("%s!%s should be %s", sheetName, coord, expectedKind)
                        .isEqualTo(expectedKind);
            }
            results.recordSuccess(sheetName, coord, state);
        } else {
            results.recordAttempted(sheetName, coord, state, cell.isFormula());
        }
    }


    private void validateInputCells(
            CellGraph graph, CellTypes types, TypeValidationResults results) {
        for (InputCell input : graph.inputs()) {
            long cellId = input.cellId();
            GraphCell cell = graph.cells().get(cellId);
            Optional<ResolvedUnit> unit = types.unitOf(cellId);
            Optional<UnboundReason> refusal = types.refusalOf(cellId);

            if (unit.isEmpty() && refusal.isEmpty()) {
                results.recordUnsettled(cell);
            } else if (unit.isPresent()) {
                results.recordInputTyped();
            }
            results.recordInputTotal();
        }
    }

    private void validateRandomSampling(
            WorkspaceRepository repo,
            long parseRunId,
            CellGraph graph,
            CellTypes types,
            Map<Long, String> sheetById,
            TypeValidationResults results)
            throws Exception {
        List<InterpretationCellView> allCells = repo.selectInterpretationCellsForParseRun(parseRunId);
        List<InterpretationCellView> numericCells = allCells.stream()
                .filter(c -> {
                    GraphCell gc = graph.cells().get(c.cellId());
                    return gc != null && gc.numeric();
                })
                .collect(Collectors.toList());

        if (numericCells.isEmpty()) {
            return;
        }

        // Random sample
        Random rand = new Random(12345L); // Fixed seed for reproducibility
        Collections.shuffle(numericCells, rand);
        int sampleSize = Math.min(RANDOM_SAMPLE_SIZE, numericCells.size());
        List<InterpretationCellView> sample = numericCells.subList(0, sampleSize);

        System.out.printf("Random sampling %d/%d numeric cells across all tabs%n",
                sampleSize, numericCells.size());

        for (InterpretationCellView cellView : sample) {
            GraphCell cell = graph.cells().get(cellView.cellId());
            String sheetName = sheetById.get(cell.worksheetId());
            Optional<ResolvedUnit> unit = types.unitOf(cell.cellId());
            Optional<UnboundReason> refusal = types.refusalOf(cell.cellId());

            String state = unit.isPresent()
                    ? unit.get().kind() + "/" + unit.get().scale()
                    : refusal.map(r -> "REFUSED:" + r).orElse("UNSETTLED");

            System.out.printf("    %s!%s(%s) -> %s %s%n",
                    sheetName,
                    cell.coord(),
                    cell.isFormula() ? "derived" : "input",
                    state,
                    cell.rowLabel() != null ? "rowLabel=" + cell.rowLabel() : "");

            results.recordSampled(sheetName, cell, state);
        }
    }

    private void logParserGapCandidates(
            CellGraph graph,
            CellTypes types,
            Map<Long, String> sheetById,
            TypeValidationResults results) {
        // Look for cells that:
        // 1. Are formulas but refuse to type
        // 2. Have only MULTIPLICATIVE or mixed dependencies that might expose parser gaps
        List<String> gaps = new ArrayList<>();

        for (GraphCell cell : graph.cells().values()) {
            if (!cell.isFormula() || !cell.numeric()) {
                continue;
            }
            Optional<UnboundReason> refusal = types.refusalOf(cell.cellId());
            if (refusal.isEmpty()) {
                continue; // Typed successfully
            }

            String sheetName = sheetById.get(cell.worksheetId());
            String cellRef = sheetName + "!" + cell.coord();
            String formula = cell.formulaText();

            // Check formula patterns that match known gaps:
            // Gap 1: sheet-prefixed references in multiplication (e.g., B46*'CAPITAL COST'!D20)
            if (formula.contains("!") && formula.matches(".*[*/%].*'[^']*'!.*")) {
                gaps.add(String.format("GAP1 %s: external ref in multiplicative term: %s",
                        cellRef, formula));
            }
            // Gap 2: division by power of 10 (e.g., F21/10^5)
            if (formula.matches(".*/(\\d+\\^\\d+|10\\^\\d+).*")) {
                gaps.add(String.format("GAP2 %s: power-of-10 divisor: %s", cellRef, formula));
            }
            // Growth-factor pattern (informational only, not a defect): I75*(1+D75)
            // keeps const(1.0) and the D75 edge; refusals here are genuine
            // upstream kind conflicts, verified via ScratchOmTypingDiagnoseIT.
            if (formula.matches(".*\\(1[+-].*\\).*") && formula.contains("*")) {
                gaps.add(String.format("GROWTH %s: growth-factor pattern (informational): %s",
                        cellRef, formula));
            }
        }

        if (!gaps.isEmpty()) {
            System.out.println("\n=== Parser Gap Candidates (for CellGraphBuilder fix) ===");
            gaps.forEach(System.out::println);
        }
        results.recordGapCandidates(gaps.size());
    }

    private Long findCell(
            CellGraph graph,
            Map<Long, String> sheetById,
            String sheetNameTarget,
            String coordTarget) {
        for (GraphCell cell : graph.cells().values()) {
            String sheetName = sheetById.get(cell.worksheetId());
            if (sheetNameTarget.trim().equalsIgnoreCase(sheetName)
                    && coordTarget.equalsIgnoreCase(cell.coord())) {
                return cell.cellId();
            }
        }
        return null;
    }

    private static class TypeValidationResults {
        private final List<String> successes = new ArrayList<>();
        private final List<String> attempts = new ArrayList<>();
        private final List<String> missing = new ArrayList<>();
        private final List<GraphCell> unsettled = new ArrayList<>();
        private final List<String> sampledCells = new ArrayList<>();
        private final Map<String, Integer> refusalsByReason = new HashMap<>();
        private int inputsTotal = 0;
        private int inputsTyped = 0;
        private int gapCandidates = 0;

        void recordSuccess(String sheet, String coord, String state) {
            successes.add(String.format("%s!%s -> %s", sheet, coord, state));
        }

        void recordAttempted(String sheet, String coord, String state, boolean isFormula) {
            attempts.add(String.format("%s!%s -> %s (%s)", sheet, coord, state,
                    isFormula ? "derived" : "input"));
        }

        void recordMissing(String sheet, String coord) {
            missing.add(String.format("%s!%s not found", sheet, coord));
        }

        void recordUnsettled(GraphCell cell) {
            unsettled.add(cell);
        }

        void recordInputTyped() {
            inputsTyped++;
        }

        void recordInputTotal() {
            inputsTotal++;
        }

        void recordSampled(String sheet, GraphCell cell, String state) {
            sampledCells.add(String.format("%s!%s -> %s", sheet, cell.coord(), state));
        }

        void recordGapCandidates(int count) {
            this.gapCandidates = count;
        }

        List<String> getDerivedRefusals() {
            return attempts.stream()
                    .filter(s -> s.contains("REFUSED:") && s.contains("derived"))
                    .collect(Collectors.toList());
        }

        int getInputsTotal() {
            return inputsTotal;
        }

        int getInputsTyped() {
            return inputsTyped;
        }

        void summarize() {
            System.out.println("\n=== Typing Validation Results ===");
            System.out.printf("Critical cells: %d success, %d attempted, %d missing%n",
                    successes.size(), attempts.size(), missing.size());
            System.out.printf("Input cells: %d typed / %d total%n", inputsTyped, inputsTotal);
            System.out.printf("Random samples: %d cells%n", sampledCells.size());
            System.out.printf("Parser gap candidates: %d%n", gapCandidates);

            if (!successes.isEmpty()) {
                System.out.println("\nCritical successes:");
                successes.forEach(s -> System.out.println("  " + s));
            }
            if (!attempts.isEmpty()) {
                System.out.println("\nAttempted (may refuse until parser fixes land):");
                attempts.forEach(s -> System.out.println("  " + s));
            }
            if (!missing.isEmpty()) {
                System.out.println("\nMissing cells:");
                missing.forEach(s -> System.out.println("  " + s));
            }
            if (!unsettled.isEmpty()) {
                System.out.println("\nUnsettled inputs (expected to be empty):");
                unsettled.forEach(c -> System.out.println("  " + c.coord()));
            }
        }
    }
}
