package com.resurgent.tev.parser.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * QA gates (tickets 11 and 21): a parse run may only report status 'success'
 * when 100% of occupied cells were ingested, 100% of persisted formula
 * reference tokens were either resolved or carry an {@code unresolved_reason}
 * (which in turn always has a matching review_queue row), and every formula
 * cell's tokenization state accounts for the cell (tokenized, parse_error, or
 * unavailable). Any shortfall flips the run to 'partial' (some cells landed)
 * or 'failed' (none did), with reasons recorded rather than a silent success.
 *
 * <p>No adapter currently produces an explicit per-cell rejection (with its
 * own reason), so today {@code cellsIn - cellsWritten} can only mean an
 * unexplained loss. If a future adapter gains a legitimate per-cell reject
 * path, that count should be threaded through as its own gate input rather
 * than folded into this reconciliation.
 */
public final class QaGate {

    private QaGate() {
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
        int formulaCellsAccountedFor = formulaCellsTokenized + formulaCellsParseError + formulaCellsUnavailable;
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
