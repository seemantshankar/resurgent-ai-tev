package com.resurgent.tev.parser.classify;

/**
 * One expanded dependency member under a formula annotation. Only real persisted
 * cells appear here — blank or unresolved targets are never invented.
 */
public record FormulaAnnotationMember(
        int ordinal,
        long targetCellId,
        String coord,
        String nomenclaturePath,
        String nomenclatureStatus) {}
