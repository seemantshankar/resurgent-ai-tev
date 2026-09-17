package com.resurgent.tev.parser.classify;

/**
 * One operand of a formula: either a cell this formula reads, or a barrier that
 * makes part of the formula unusable. A barrier is kept rather than dropped so a
 * chain through a {@code #REF!} or an external workbook refuses with a reason
 * instead of typing optimistically from whatever else it could reach.
 */
record CellDependency(Long cellId, DependencyRole role, UnboundReason barrier) {

    static CellDependency of(long cellId, DependencyRole role) {
        return new CellDependency(cellId, role, null);
    }

    static CellDependency barrier(DependencyRole role, UnboundReason barrier) {
        return new CellDependency(null, role, barrier);
    }

    boolean isBarrier() {
        return barrier != null;
    }
}
