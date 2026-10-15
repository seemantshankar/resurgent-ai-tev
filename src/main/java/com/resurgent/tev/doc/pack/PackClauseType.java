package com.resurgent.tev.doc.pack;

import java.util.List;

/**
 * One clause type a pack binds for its document type (M3 clauses, spec §"Clauses").
 * Packs are data: the Java core never hard-codes clause vocabulary.
 *
 * @param key stable snake_case key stored on the clause ({@code termination}, {@code force_majeure})
 * @param label human label ({@code Termination})
 * @param parentFamily parent clause family the type groups under, or null when standalone
 * @param aliases heading/body phrases that deterministically match this type
 */
public record PackClauseType(String key, String label, String parentFamily, List<String> aliases) {

    public PackClauseType {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }
}
