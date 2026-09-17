package com.resurgent.tev.parser.classify;

import java.util.List;

/**
 * One ordered formula dependency annotation attached to a Cell interpretation.
 * Reference edges alone are not an expression tree: operators, functions, and
 * constants remain authoritative on {@code formula_text}. Shared dependency
 * path/kind describes referenced cells only and never asserts economic rollup.
 */
public record FormulaAnnotation(
        long parseRunId,
        long cellId,
        int ordinal,
        String rawToken,
        String refKind,
        String targetSheetName,
        String targetRange,
        String completeness,
        String enclosingFunction,
        String sharedDependencyPath,
        String sharedDependencyKind,
        List<FormulaAnnotationMember> members) {

    public FormulaAnnotation {
        members = members == null ? List.of() : List.copyOf(members);
    }

    /** Shared-head description is never a valid economic rollup instruction. */
    public boolean assertsEconomicRollup() {
        return false;
    }
}
