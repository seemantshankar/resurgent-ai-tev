package com.resurgent.tev.parser.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * Ingest QA gates (ADR 0003 / 0009): a parse run reports {@code success} only when
 * occupied cells reconcile, every formula reference is accounted for as either
 * resolved (including ranges / known defined names / blank targets that do not invent
 * cells) or carrying an {@code unresolved_reason}, and every formula cell is
 * tokenized, {@code parse_error}, or {@code unavailable}. No region/semantic gates.
 */
public final class QaGate {

    private QaGate() {
    }

    public static QaGateResult evaluate(int cellsIn, int cellsWritten) {
        return evaluate(cellsIn, cellsWritten, 0, 0, 0, 0, 0, 0, 0);
    }

    public static QaGateResult evaluate(int cellsIn, int cellsWritten,
            int referencesTotal, int referencesResolved, int referencesUnresolved,
            int formulaCellsTotal, int formulaCellsTokenized, int formulaCellsParseError,
            int formulaCellsUnavailable) {
        List<String> reasons = new ArrayList<>();
        int cellsRejected = cellsIn - cellsWritten;
        if (cellsRejected != 0) {
            reasons.add("cell_reconciliation_mismatch: " + cellsIn + " occupied cells but "
                    + cellsWritten + " written");
        }
        int referencesAccountedFor = referencesResolved + referencesUnresolved;
        if (referencesAccountedFor != referencesTotal) {
            reasons.add("reference_reconciliation_mismatch: " + referencesTotal
                    + " references but " + referencesAccountedFor + " resolved or unresolved");
        }
        int formulaCellsAccountedFor = formulaCellsTokenized + formulaCellsParseError
                + formulaCellsUnavailable;
        if (formulaCellsAccountedFor != formulaCellsTotal) {
            reasons.add("formula_reconciliation_mismatch: " + formulaCellsTotal
                    + " formula cells but " + formulaCellsAccountedFor
                    + " tokenized, parse_error, or unavailable");
        }

        String status;
        if (reasons.isEmpty()) {
            status = "success";
        } else if (cellsWritten == 0 && cellsIn > 0) {
            status = "failed";
        } else {
            status = "partial";
        }
        return new QaGateResult(status, cellsRejected, List.copyOf(reasons));
    }
}
