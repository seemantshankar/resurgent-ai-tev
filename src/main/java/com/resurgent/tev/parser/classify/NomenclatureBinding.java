package com.resurgent.tev.parser.classify;

/**
 * Persisted Layer B nomenclature binding for one amount cell in a parse run.
 * Line peers are stored separately ({@link BindingPeer}).
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
        Double confidence) {}
