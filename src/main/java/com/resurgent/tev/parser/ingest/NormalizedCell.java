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
        boolean sheetHidden) {
}
