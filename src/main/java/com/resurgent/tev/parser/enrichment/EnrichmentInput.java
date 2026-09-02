package com.resurgent.tev.parser.enrichment;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Inputs needed to enrich one number-redacted workbook tab. */
public record EnrichmentInput(
        Path redactedWorkbook,
        Path unhiddenWorkbook,
        String sheetName,
        List<String> typeMenu,
        EnrichmentPromptMode mode) {

    public EnrichmentInput(
            Path redactedWorkbook,
            Path unhiddenWorkbook,
            String sheetName,
            List<String> typeMenu) {
        this(redactedWorkbook, unhiddenWorkbook, sheetName, typeMenu, EnrichmentPromptMode.FULL);
    }

    public EnrichmentInput {
        Objects.requireNonNull(redactedWorkbook, "redactedWorkbook");
        Objects.requireNonNull(unhiddenWorkbook, "unhiddenWorkbook");
        if (sheetName == null || sheetName.isBlank()) {
            throw new IllegalArgumentException("sheetName must not be blank");
        }
        typeMenu = List.copyOf(Objects.requireNonNull(typeMenu, "typeMenu"));
        mode = Objects.requireNonNull(mode, "mode");
    }
}
