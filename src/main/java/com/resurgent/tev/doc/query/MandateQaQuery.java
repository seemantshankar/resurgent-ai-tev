package com.resurgent.tev.doc.query;

/**
 * Filter for the mandate QA read API (M3). Only the mandate scope is fixed; later M3
 * tickets widen the read surface (entities, sites, passages, clauses, conflicts,
 * checklists) without changing this scope.
 */
public record MandateQaQuery(long mandateId) {

    /**
     * Query scoped to one mandate.
     *
     * @param mandateId required mandate scope
     */
    public static MandateQaQuery forMandate(long mandateId) {
        return new MandateQaQuery(mandateId);
    }
}
