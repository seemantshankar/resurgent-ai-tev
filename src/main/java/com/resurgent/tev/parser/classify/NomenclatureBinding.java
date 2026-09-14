package com.resurgent.tev.parser.classify;

/**
 * Persisted Layer B nomenclature binding for one amount cell in a parse run.
 * Peers are out of scope for #107.
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
