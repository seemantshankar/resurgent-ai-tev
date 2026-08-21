package com.resurgent.tev.parser.ingest;

import java.util.List;

/** Outcome of {@link QaGate#evaluate}: the resulting parse_run status and why. */
public record QaGateResult(String status, int cellsRejected, List<String> reasons) {

    public boolean passed() {
        return reasons.isEmpty();
    }
}
