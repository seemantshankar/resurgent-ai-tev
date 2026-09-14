package com.resurgent.tev.parser.classify;

import java.util.List;
import java.util.Objects;

/** One LLM Layer B proposal for a money line, optionally with line peers. */
public record LayerBLineJudgment(
        String coord,
        String verbatim,
        String path,
        String amountRole,
        List<String> aliases,
        Double confidence,
        List<LinePeerRef> peers) {

    public LayerBLineJudgment(
            String coord,
            String verbatim,
            String path,
            String amountRole,
            List<String> aliases,
            Double confidence) {
        this(coord, verbatim, path, amountRole, aliases, confidence, List.of());
    }

    public LayerBLineJudgment {
        Objects.requireNonNull(coord, "coord");
        Objects.requireNonNull(verbatim, "verbatim");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(amountRole, "amountRole");
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        peers = peers == null ? List.of() : List.copyOf(peers);
    }
}
