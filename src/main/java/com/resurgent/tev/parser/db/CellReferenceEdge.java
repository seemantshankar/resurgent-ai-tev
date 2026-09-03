package com.resurgent.tev.parser.db;

/**
 * One unexpanded formula reference token persisted at ingest (ADR 0013).
 */
public record CellReferenceEdge(
        long fromCellId,
        int tokenIndex,
        String rawToken,
        String refKind,
        String targetSheetName,
        Long targetWorksheetId,
        String targetRange,
        Long resolvedCellId,
        Long externalLinkId,
        Boolean absRow,
        Boolean absCol,
        Integer rowOffset,
        Integer colOffset,
        boolean isWholeColumn,
        boolean isWholeRow,
        String unresolvedReason) {
}
