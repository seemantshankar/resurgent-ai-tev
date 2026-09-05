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
        Long styleId,
        Boolean isBold,
        boolean isMergedAnchor,
        boolean isMergedParticipant,
        String mergedRange) {
}
