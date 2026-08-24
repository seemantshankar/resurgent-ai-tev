package com.resurgent.tev.parser.ingest;

import java.util.List;
import java.util.Objects;

/** Result of scoring one detected region. */
public record RegionClassification(
        RegionType type,
        double confidence,
        List<DetectionReason> reasons,
        String costHeadCode) {

    public RegionClassification {
        Objects.requireNonNull(type, "type");
        if (confidence < 0.0 || confidence > 1.0 || Double.isNaN(confidence)) {
            throw new IllegalArgumentException("confidence must be in [0, 1]");
        }
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        if (type != RegionType.COST_HEAD && costHeadCode != null) {
            throw new IllegalArgumentException("only cost_head classifications may have a cost-head code");
        }
    }
}
