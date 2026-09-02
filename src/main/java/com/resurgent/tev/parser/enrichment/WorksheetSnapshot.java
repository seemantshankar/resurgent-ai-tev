package com.resurgent.tev.parser.enrichment;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Filled-cell occupancy and same-sheet formula references used by enrichment
 * quality assurance.
 */
public record WorksheetSnapshot(
        Set<String> filledCells,
        Map<String, Set<String>> formulaReferences) {

    public WorksheetSnapshot {
        filledCells = Set.copyOf(Objects.requireNonNull(filledCells, "filledCells"));
        Objects.requireNonNull(formulaReferences, "formulaReferences");
        formulaReferences = formulaReferences.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> Set.copyOf(entry.getValue())));
    }
}
