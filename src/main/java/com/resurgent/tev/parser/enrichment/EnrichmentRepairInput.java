package com.resurgent.tev.parser.enrichment;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Inputs for a coverage-repair LLM call on leftover filled cells. */
public record EnrichmentRepairInput(
        Path redactedWorkbook,
        Path unhiddenWorkbook,
        String sheetName,
        List<String> typeMenu,
        EnrichmentPromptMode mode,
        RepairWindow window) {

    public EnrichmentRepairInput {
        Objects.requireNonNull(redactedWorkbook, "redactedWorkbook");
        Objects.requireNonNull(unhiddenWorkbook, "unhiddenWorkbook");
        if (sheetName == null || sheetName.isBlank()) {
            throw new IllegalArgumentException("sheetName must not be blank");
        }
        typeMenu = List.copyOf(Objects.requireNonNull(typeMenu, "typeMenu"));
        mode = Objects.requireNonNull(mode, "mode");
        window = Objects.requireNonNull(window, "window");
    }
}
