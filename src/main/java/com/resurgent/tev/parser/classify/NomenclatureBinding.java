package com.resurgent.tev.parser.classify;

/**
 * Persisted Layer B nomenclature binding for one amount cell in a parse run.
 * Line peers are stored separately ({@link BindingPeer}).
 *
 * <p>{@code source} says how the binding was produced ({@link BindingSource}),
 * {@code labelKey} names the distinct qualified label one LLM answer covered, and
 * {@code aggregationId} points at the group the cell belongs to. All three are the
 * evidence trail behind the binding rather than part of its meaning.
 */
public record NomenclatureBinding(
        long cellId,
        long parseRunId,
        long candidateId,
        String verbatim,
        String path,
        String amountRole,
        boolean softLeaf,
        boolean viaAlias,
        Double confidence,
        String source,
        String labelKey,
        Long aggregationId) {

    public NomenclatureBinding {
        source = source == null ? BindingSource.LLM_LINE : source;
    }

    /** A per-cell Layer B binding, the only kind that existed before the graph. */
    public NomenclatureBinding(
            long cellId,
            long parseRunId,
            long candidateId,
            String verbatim,
            String path,
            String amountRole,
            boolean softLeaf,
            boolean viaAlias,
            Double confidence) {
        this(cellId, parseRunId, candidateId, verbatim, path, amountRole, softLeaf, viaAlias,
                confidence, BindingSource.LLM_LINE, null, null);
    }

    public NomenclatureBinding withSource(String source, String labelKey, Long aggregationId) {
        return new NomenclatureBinding(cellId, parseRunId, candidateId, verbatim, path,
                amountRole, softLeaf, viaAlias, confidence, source, labelKey, aggregationId);
    }
}
