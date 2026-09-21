package com.resurgent.tev.parser.classify;

/**
 * One operand of a formula: either a cell this formula reads, or a barrier that
 * makes part of the formula unusable, or a hardcoded number. A barrier is kept rather than dropped so a
 * chain through a {@code #REF!} or an external workbook refuses with a reason
 * instead of typing optimistically from whatever else it could reach.
 */
record CellDependency(Long cellId, DependencyRole role, UnboundReason barrier, Double constant) {

    static CellDependency of(long cellId, DependencyRole role) {
        return new CellDependency(cellId, role, null, null);
    }

    static CellDependency barrier(DependencyRole role, UnboundReason barrier) {
        return new CellDependency(null, role, barrier, null);
    }

    /**
     * A hardcoded number in an operand position. Carried because a {@code /100000}
     * divisor is what turns a figure in rupees into a figure in lakhs, and scale
     * has to survive propagation for two members of one aggregation to be
     * comparable at all.
     */
    static CellDependency constant(DependencyRole role, double value) {
        return new CellDependency(null, role, null, value);
    }

    boolean isBarrier() {
        return barrier != null;
    }

    boolean isConstant() {
        return constant != null;
    }
}
