package com.resurgent.tev.parser.classify;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The dependency graph of one parse run: every numeric cell, what each formula
 * reads and how, which cells are hardcoded inputs, and which are aggregation heads.
 *
 * <p>This is built from formulas alone. Presentation is explicitly not used: bold,
 * borders, blank rows, column position and header wording are optional and
 * author-specific, so a rule set built on them can only grow and never converge.
 */
record CellGraph(
        long parseRunId,
        Map<Long, GraphCell> cells,
        List<InputCell> inputs,
        List<Aggregation> aggregations,
        Map<Long, List<CellDependency>> dependencies,
        Set<Long> driverOnly,
        Map<Long, List<Aggregation>> membershipsByCell) {

    CellGraph {
        cells = Map.copyOf(cells);
        inputs = List.copyOf(inputs);
        aggregations = List.copyOf(aggregations);
        dependencies = Map.copyOf(dependencies);
        driverOnly = Set.copyOf(driverOnly);
        membershipsByCell = Map.copyOf(membershipsByCell);
    }

    Optional<GraphCell> cell(long cellId) {
        return Optional.ofNullable(cells.get(cellId));
    }

    List<CellDependency> dependenciesOf(long cellId) {
        return dependencies.getOrDefault(cellId, List.of());
    }

    /** Every aggregation this cell is a member of; a cell may add into one and be deducted in another. */
    List<Aggregation> membershipsOf(long cellId) {
        return membershipsByCell.getOrDefault(cellId, List.of());
    }

    Optional<Aggregation> aggregationHeadedBy(long cellId) {
        return aggregations.stream().filter(a -> a.headCellId() == cellId).findFirst();
    }

    boolean isAggregationHead(long cellId) {
        return aggregations.stream().anyMatch(a -> a.headCellId() == cellId);
    }

    /** True when the cell is only ever read as an operand of {@code *} or {@code /}. */
    boolean isDriverOnly(long cellId) {
        return driverOnly.contains(cellId);
    }
}
