package com.resurgent.tev.parser.ingest;

/**
 * Structural reference token captured during formula tokenization.
 */
public record FormulaToken(
        int tokenIndex,
        String rawToken,
        String refKind,
        String targetSheetName,
        String targetRange,
        Boolean absRow,
        Boolean absCol,
        Integer rowOffset,
        Integer colOffset,
        boolean isWholeColumn,
        boolean isWholeRow
) {}
