package com.resurgent.tev.parser.classify;

/**
 * A kind and scale together, or a refusal saying why neither could be settled.
 * Units are a property of the group, not of the cell, so this is what an
 * aggregation resolves to once from its members.
 */
record ResolvedUnit(CellKind kind, CellScale scale, UnboundReason refusal) {

    static ResolvedUnit of(CellKind kind, CellScale scale) {
        return new ResolvedUnit(kind, scale, null);
    }

    static ResolvedUnit refused(UnboundReason refusal) {
        return new ResolvedUnit(null, null, refusal);
    }

    static ResolvedUnit unresolved() {
        return new ResolvedUnit(null, null, null);
    }

    boolean isResolved() {
        return kind != null;
    }

    boolean isMoney() {
        return kind != null && kind.allowsCostRole();
    }
}
