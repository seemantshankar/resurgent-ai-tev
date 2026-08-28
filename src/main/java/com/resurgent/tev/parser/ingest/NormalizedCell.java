package com.resurgent.tev.parser.ingest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Canonical cell produced by ingestion adapters: coordinates, typed values,
 * formula/cached state, merge ownership, and visibility flags.
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
        boolean sheetHidden) {
}
