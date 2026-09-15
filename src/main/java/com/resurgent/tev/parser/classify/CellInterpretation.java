package com.resurgent.tev.parser.classify;

/**
 * Replaceable machine interpretation for one persisted cell in a parse run.
 * Assembled snapshot of presentation + Layer B coverage; not an amount store.
 */
public record CellInterpretation(
        long parseRunId,
        long cellId,
        String valueOrigin,
        String resultingValue,
        String resultSource,
        String formulaText,
        String formulaState,
        String cacheState,
        boolean isError,
        String errorType,
        String nomenclaturePath,
        String amountRole,
        Boolean softLeaf,
        Boolean viaAlias,
        String nomenclatureStatus) {}
