package com.resurgent.tev.parser.db;

/** One stored Layer B path for a main or helper cell. */
public record NomenclatureBinding(
        long parseRunId,
        long candidateId,
        long cellId,
        String coord,
        String pathRoot,
        String path,
        String amountRole,
        String verbatim) {
}
