package com.resurgent.tev.parser.classify;

/** Persisted ProjectFact binding for one parse run. */
public record ProjectFactBinding(
        long parseRunId,
        long candidateId,
        Long cellId,
        String verbatim,
        String factPath) {}
