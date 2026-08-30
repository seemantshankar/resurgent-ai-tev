package com.resurgent.tev.parser.enrichment;

import java.util.List;

/** Menu snapshot and canonical labels produced by one normalization run. */
public record RegionTypeNormalizationResult(
        List<String> canonicalTypes,
        List<String> types,
        List<String> newTypesAdded) {

    public RegionTypeNormalizationResult {
        canonicalTypes = List.copyOf(canonicalTypes);
        types = List.copyOf(types);
        newTypesAdded = List.copyOf(newTypesAdded);
    }
}
