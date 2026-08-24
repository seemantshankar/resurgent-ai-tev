package com.resurgent.tev.parser.ingest;

import java.math.BigDecimal;
import java.util.List;

/** Projected cost-head total for ingest reports. */
record CostHeadTrust(
        String code,
        String state,
        String source,
        BigDecimal amount,
        String unit,
        String currency,
        double confidence,
        List<String> reasons,
        String reviewStatus,
        String fingerprint,
        List<TrustEvaluator.Gate> gates) {}
