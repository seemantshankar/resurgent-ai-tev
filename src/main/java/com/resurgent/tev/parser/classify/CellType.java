package com.resurgent.tev.parser.classify;

/**
 * One numeric cell's resolved dimension for a parse run: what it is, at what scale,
 * how that was decided, and how many propagation steps from an input it sits.
 * This is the evidence behind a binding, so "why is J45 bound" stays answerable.
 */
public record CellType(
        long parseRunId,
        long cellId,
        CellKind kind,
        CellScale scale,
        String typeSource,
        int depth) {}
