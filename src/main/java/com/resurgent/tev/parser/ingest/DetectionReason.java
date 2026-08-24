package com.resurgent.tev.parser.ingest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.Objects;

/** A stable, text-free explanation for a region classification decision. */
public record DetectionReason(Code code, int weight, Map<String, Long> params) {

    public DetectionReason {
        Objects.requireNonNull(code, "code");
        params = Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(Objects.requireNonNull(params, "params"))));
        for (Map.Entry<?, ?> entry : params.entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof Long)) {
                throw new IllegalArgumentException("detection-reason params must be numeric");
            }
        }
    }

    /**
     * Codes are persisted rather than prose so snapshots are stable and never contain workbook
     * text. Parameters are deliberately limited to numeric values and coordinates.
     */
    public enum Code {
        SERIAL_RESET,
        SKELETON_DRIFT,
        MERGED_TITLE,
        TITLE_STYLE,
        COLUMN_PROFILE_SHIFT,
        HEADER_TOKEN,
        SERIAL_PATTERN,
        STATEMENT_SHAPE,
        COST_HEAD_ALIAS,
        VERTICAL_FORM,
        SCRATCH_PATTERN,
        INSUFFICIENT_EVIDENCE
    }
}
