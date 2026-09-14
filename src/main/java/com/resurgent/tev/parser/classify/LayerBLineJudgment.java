package com.resurgent.tev.parser.classify;

import java.util.List;
import java.util.Objects;

/**
 * One LLM Layer B proposal for a money line. Peers are out of scope for #107.
 */
public record LayerBLineJudgment(
        String coord,
        String verbatim,
        String path,
        String amountRole,
        List<String> aliases,
        Double confidence) {

    public LayerBLineJudgment {
        Objects.requireNonNull(coord, "coord");
        Objects.requireNonNull(verbatim, "verbatim");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(amountRole, "amountRole");
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }
}
