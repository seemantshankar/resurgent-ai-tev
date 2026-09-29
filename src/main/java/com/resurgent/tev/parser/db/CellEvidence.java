package com.resurgent.tev.parser.db;

/**
 * Persisted cell facts needed for region-discovery signatures (worksheet-local).
 */
public record CellEvidence(
        long cellId,
        String coord,
        int rowNum,
        int colNum,
        String valueType,
        String textValue,
        String formulaText,
        boolean hasNumeric,
        Long styleId,
        Boolean isBold,
        boolean hasBorder,
        boolean hasBottomBorder,
        boolean isMergedAnchor,
        boolean isMergedParticipant,
        String mergedRange) {
}
