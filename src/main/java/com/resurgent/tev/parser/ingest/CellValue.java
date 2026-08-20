package com.resurgent.tev.parser.ingest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Normalized value fields produced by {@link CellNormalizer}. Adapters combine
 * this with coordinate and formula information to build a {@link NormalizedCell}.
 */
public record CellValue(
        String rawType,
        String valueType,
        String textValue,
        String displayValue,
        BigDecimal numericValue,
        Boolean boolValue,
        LocalDateTime dateValue,
        boolean coercedFromText,
        ParsedQuantity parsedQuantity,
        boolean isError,
        String errorType) {

    public static CellValue empty() {
        return new CellValue("empty", "empty", null, null,
                null, null, null, false, null, false, null);
    }
}
