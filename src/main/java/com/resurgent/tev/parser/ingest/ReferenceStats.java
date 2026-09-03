package com.resurgent.tev.parser.ingest;

/** Mutable running totals for reference reconciliation (QA gate, §12) across a workbook. */
public final class ReferenceStats {
    int total;
    int resolved;
    int unresolved;

    public int total() {
        return total;
    }

    public int resolved() {
        return resolved;
    }

    public int unresolved() {
        return unresolved;
    }
}
