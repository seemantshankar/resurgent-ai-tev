package com.resurgent.tev.parser.ingest;

import com.resurgent.tev.parser.db.Jsonb;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured result of parsing a quantity text value such as {@code 1Set},
 * {@code 200 PC}, or {@code L S}. Kept separate from the raw text so downstream
 * consumers can choose precision and unit handling.
 */
public record ParsedQuantity(BigDecimal count, String unit, String raw) {

    /**
     * Serializes this quantity to the JSON object stored in
     * {@code cell.parsed_quantity}.
     */
    public String toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("count", count() == null ? null : count().toPlainString());
        map.put("unit", unit());
        map.put("raw", raw());
        try {
            return Jsonb.toJson(map);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize parsed_quantity", e);
        }
    }
}
