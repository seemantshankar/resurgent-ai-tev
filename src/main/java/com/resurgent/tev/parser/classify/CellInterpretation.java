package com.resurgent.tev.parser.classify;

/**
 * Replaceable machine interpretation for one persisted cell in a parse run.
 * Assembled snapshot of presentation + Layer B coverage; not an amount store.
 * {@code formulaGloss} is optional explanatory prose (#119), separate from
 * formula facts and deterministic dependency annotations.
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
        String nomenclatureStatus,
        String formulaGloss) {

    public CellInterpretation(
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
            String nomenclatureStatus) {
        this(
                parseRunId,
                cellId,
                valueOrigin,
                resultingValue,
                resultSource,
                formulaText,
                formulaState,
                cacheState,
                isError,
                errorType,
                nomenclaturePath,
                amountRole,
                softLeaf,
                viaAlias,
                nomenclatureStatus,
                null);
    }
}