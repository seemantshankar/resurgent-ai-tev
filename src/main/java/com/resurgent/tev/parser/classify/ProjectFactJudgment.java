package com.resurgent.tev.parser.classify;

import java.util.Objects;

/** One ProjectFact binding proposal from the LLM (Layer A adjunct). */
public record ProjectFactJudgment(String coord, String verbatim, String factPath) {

    public ProjectFactJudgment {
        Objects.requireNonNull(verbatim, "verbatim");
        Objects.requireNonNull(factPath, "factPath");
        if (coord != null) {
            coord = coord.isBlank() ? null : coord.trim();
        }
        verbatim = verbatim.trim();
        factPath = factPath.trim();
    }
}
