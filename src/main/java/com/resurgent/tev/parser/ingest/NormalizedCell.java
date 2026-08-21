package com.resurgent.tev.parser.ingest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Canonical cell produced by ingestion adapters. Holds every normalized field
 * the database schema expects for an occupied cell, including formula provenance,
 * cached value state, error classification, and quantity parsing.
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
        ParsedQuantity parsedQuantity,
        boolean isError,
        String errorType,
        String rowLabel,
        String colLabel,

        // Structural enrichment (Ticket 05 / issue #6)
        boolean isMergedAnchor,
        boolean isMergedParticipant,
        String mergedRange,
        String valueSource,
        boolean rowHidden,
        boolean colHidden,
        boolean sheetHidden,

        // External reference enrichment (Ticket 06 / issue #7)
        String externalRef,
        Long externalLinkId,
        String sheetRefs,
        String definedNameRefs) {

    /**
     * Returns a copy of this cell with the resolved external link id set.
     * Does not change the record component signature.
     */
    public NormalizedCell withExternalLinkId(Long externalLinkId) {
        return new NormalizedCell(
                coord, rowNum, colNum,
                rawValue, rawType, valueType, textValue, displayValue,
                numericValue, boolValue, dateValue,
                formulaText, formulaNormalized, formulaState,
                cachedValue, cacheState,
                coercedFromText, parsedQuantity, isError, errorType,
                rowLabel, colLabel,
                isMergedAnchor, isMergedParticipant, mergedRange, valueSource,
                rowHidden, colHidden, sheetHidden,
                externalRef, externalLinkId, sheetRefs, definedNameRefs);
    }
}
