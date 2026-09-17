package com.resurgent.tev.parser.db;

/**
 * Rich cell projection for interpretation snapshotting. Broader than
 * {@link CellPacketView}: includes formula/cache/error/merge facts.
 */
public record InterpretationCellView(
        long cellId,
        long worksheetId,
        String coord,
        int rowNum,
        int colNum,
        String valueType,
        String textValue,
        String displayValue,
        String numericValue,
        Boolean boolValue,
        String dateValue,
        String formulaText,
        String formulaState,
        String cachedValue,
        String cacheState,
        boolean isError,
        String errorType,
        boolean isMergedAnchor,
        boolean isMergedParticipant,
        String mergedRange,
        String valueSource) {}
