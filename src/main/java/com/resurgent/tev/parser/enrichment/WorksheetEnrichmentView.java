package com.resurgent.tev.parser.enrichment;

import java.util.List;
import java.util.Objects;

/** Deterministic spatial representation of one worksheet tab for LLM enrichment. */
public record WorksheetEnrichmentView(
        int filledCellCount,
        int minRow,
        int maxRow,
        int minCol,
        int maxCol,
        String columnHeaderLine,
        String sparseGrid,
        String cellIndexNdjson,
        List<IslandHint> islands) {

    public WorksheetEnrichmentView {
        islands = List.copyOf(Objects.requireNonNull(islands, "islands"));
    }
}
