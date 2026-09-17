package com.resurgent.tev.parser.classify;

/**
 * One ordered evidence item attached to a Cell interpretation: header or
 * comparison-context cue with source lineage and resolution state.
 */
public record InterpretationEvidence(
        long parseRunId,
        long cellId,
        String role,
        Long sourceCellId,
        String sourceText,
        int ordinal,
        String resolution,
        String normalizedValue,
        String ruleId) {}
