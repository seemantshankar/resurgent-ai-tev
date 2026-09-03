package com.resurgent.tev.parser.ingest;

import com.resurgent.tev.parser.db.CellStyle;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Canonical cell produced by ingestion adapters: coordinates, typed values,
 * formula/cached state, merge ownership, visibility flags, shared appearance,
 * and normalised formula text.
 */
public record NormalizedCell(
        String coord,
        int rowNum,
        int colNum,
        String rawValue,
        String rawType,
        String valueType,
        String textValue,
        String displayValue,
        BigDecimal numericValue,
        Boolean boolValue,
        LocalDateTime dateValue,
        String formulaText,
        String formulaNormalized,
        String formulaState,
        String cachedValue,
        String cacheState,
        boolean coercedFromText,
        boolean isError,
        String errorType,
        boolean isMergedAnchor,
        boolean isMergedParticipant,
        String mergedRange,
        String valueSource,
        boolean rowHidden,
        boolean colHidden,
        boolean sheetHidden,
        CellStyle cellStyle) {

    /**
     * Compatibility constructor for callers that do not yet attach style or
     * normalised formula (null means unavailable).
     */
    public NormalizedCell(
            String coord, int rowNum, int colNum, String rawValue, String rawType,
            String valueType, String textValue, String displayValue, BigDecimal numericValue,
            Boolean boolValue, LocalDateTime dateValue, String formulaText, String formulaState,
            String cachedValue, String cacheState, boolean coercedFromText, boolean isError,
            String errorType, boolean isMergedAnchor, boolean isMergedParticipant, String mergedRange,
            String valueSource, boolean rowHidden, boolean colHidden, boolean sheetHidden) {
        this(coord, rowNum, colNum, rawValue, rawType, valueType, textValue, displayValue,
                numericValue, boolValue, dateValue, formulaText, null, formulaState, cachedValue,
                cacheState, coercedFromText, isError, errorType, isMergedAnchor, isMergedParticipant,
                mergedRange, valueSource, rowHidden, colHidden, sheetHidden, null);
    }

    NormalizedCell withCellStyle(CellStyle cellStyle) {
        return new NormalizedCell(
                coord, rowNum, colNum, rawValue, rawType, valueType, textValue, displayValue,
                numericValue, boolValue, dateValue, formulaText, formulaNormalized, formulaState,
                cachedValue, cacheState, coercedFromText, isError, errorType, isMergedAnchor,
                isMergedParticipant, mergedRange, valueSource, rowHidden, colHidden, sheetHidden,
                cellStyle);
    }
}
