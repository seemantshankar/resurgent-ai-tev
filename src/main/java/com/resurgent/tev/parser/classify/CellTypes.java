package com.resurgent.tev.parser.classify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What every numeric cell in a parse run turned out to be, and why the rest did
 * not. A cell either has a resolved unit or a reason it has none — coverage comes
 * from what can be proven, and everything else is explicitly unbound.
 */
record CellTypes(
        Map<Long, Typed> typed,
        Map<Long, UnboundReason> refusals,
        Map<Long, ResolvedUnit> aggregationUnits) {

    /**
     * One cell's resolved unit, how it was decided, and its distance from an input.
     *
     * <p>{@code weak} marks a kind whose derivation touched a product carrying an
     * unmarked literal, where the arithmetic could not decide the dimension on its
     * own — a count times hardcoded constants may be a count or a per-unit amount.
     * {@code bareDefault} marks a kind that rests on the money default ("a non-blank
     * label with no quantity token") rather than a stated cue. An aggregation lets a
     * weak dissenter yield to agreeing strong members, but only where the winning
     * kind has at least one supporter that is not a bare default.
     */
    record Typed(
            ResolvedUnit unit, String typeSource, int depth, boolean weak, boolean bareDefault) {}

    CellTypes {
        // Insertion order is kept for the same reason the graph keeps it: a run has
        // to be reproducible, and Map.copyOf randomises iteration.
        typed = Collections.unmodifiableMap(new LinkedHashMap<>(typed));
        refusals = Collections.unmodifiableMap(new LinkedHashMap<>(refusals));
        aggregationUnits = Collections.unmodifiableMap(new LinkedHashMap<>(aggregationUnits));
    }

    Optional<ResolvedUnit> unitOf(long cellId) {
        Typed row = typed.get(cellId);
        return row == null ? Optional.empty() : Optional.of(row.unit());
    }

    /** The unit a whole aggregation resolved to, or unresolved when its members disagreed. */
    ResolvedUnit unitOf(Aggregation aggregation) {
        return aggregationUnits.getOrDefault(
                aggregation.headCellId(), ResolvedUnit.unresolved());
    }

    Optional<UnboundReason> refusalOf(long cellId) {
        return Optional.ofNullable(refusals.get(cellId));
    }

    boolean isMoney(long cellId) {
        Typed row = typed.get(cellId);
        return row != null && row.unit().isMoney();
    }

    List<CellType> rows(long parseRunId) {
        List<CellType> rows = new ArrayList<>();
        for (Map.Entry<Long, Typed> entry : typed.entrySet()) {
            Typed row = entry.getValue();
            rows.add(new CellType(
                    parseRunId,
                    entry.getKey(),
                    row.unit().kind(),
                    row.unit().scale(),
                    row.typeSource(),
                    row.depth()));
        }
        return List.copyOf(rows);
    }
}
