package com.resurgent.tev.parser.classify;

/**
 * One numeric cell's resolved dimension for a parse run: what it is, at what scale,
 * how that was decided, and how many propagation steps from an input it sits.
 * This is the evidence behind a binding, so "why is J45 bound" stays answerable.
 *
 * <p>{@code scaleProvenance} qualifies the scale: stated by a cue, adopted from an
 * additive consumer, or unstated. It is the explicit marker that keeps the unit
 * default from reading as a confident claim — a binding over an unstated scale
 * reports that fact instead of passing rupees off as certain.
 */
public record CellType(
        long parseRunId,
        long cellId,
        CellKind kind,
        CellScale scale,
        String typeSource,
        int depth,
        ScaleProvenance scaleProvenance) {}
