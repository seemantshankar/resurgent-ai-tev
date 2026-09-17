package com.resurgent.tev.parser.classify;

/**
 * Replaceable machine interpretation for one persisted cell in a parse run.
 * Assembled snapshot of presentation + Layer B coverage; not an amount store.
 * {@code formulaGloss} is optional explanatory prose (#119), separate from
 * formula facts and deterministic dependency annotations. {@code unboundReason}
 * says why an unbound numeric cell carries no path, so coverage gaps are explained
 * rather than silent.
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
        String formulaGloss,
        String unboundReason) {

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
                null,
                null);
    }

    /** Snapshot without a gloss, carrying why the cell stayed unbound. */
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
            String nomenclatureStatus,
            UnboundReason unboundReason) {
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
                null,
                unboundReason == null ? null : unboundReason.wireName());
    }
}