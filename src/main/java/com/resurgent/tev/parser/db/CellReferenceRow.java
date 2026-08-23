package com.resurgent.tev.parser.db;

/**
 * Data clump for {@link WorkspaceRepository#insertCellReference}: a single persisted
 * {@code cell_reference} row. Grouped into one record because the 16 positional fields
 * travel together as a unit end to end (built once in {@code ReferenceResolver}).
 */
public record CellReferenceRow(
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
        String unresolvedReason
) {}
