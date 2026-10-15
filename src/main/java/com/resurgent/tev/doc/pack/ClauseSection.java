package com.resurgent.tev.doc.pack;

import java.util.List;

/**
 * The pack's clause section (M3-08): the clause types bound for that document type and
 * whether open extraction runs. Only document types whose pack declares clauses are read
 * for them.
 *
 * @param declared true when the pack YAML carries a {@code clauses} section
 * @param openExtraction true when unmatched clauses are stored open (Pass B)
 * @param types the clause types this document type binds
 */
public record ClauseSection(boolean declared, boolean openExtraction, List<PackClauseType> types) {

    /** Packs without a clause section: nothing to read. */
    public static final ClauseSection NONE = new ClauseSection(false, false, List.of());

    public ClauseSection {
        types = types == null ? List.of() : List.copyOf(types);
    }
}
