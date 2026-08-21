package com.resurgent.tev.parser.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * Sprint 1 QA gates (ticket 11): a parse run may only report status 'success'
 * when 100% of occupied cells were ingested and 100% of external references
 * were resolved or queued for review. Any shortfall flips the run to
 * 'partial' (some cells landed) or 'failed' (none did), with reasons recorded
 * rather than a silent success.
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
            int externalRefsTotal, int externalRefsResolved, int externalRefsQueued) {
        List<String> reasons = new ArrayList<>();
        int cellsRejected = cellsIn - cellsWritten;
        if (cellsRejected != 0) {
            reasons.add("cell_reconciliation_mismatch: " + cellsIn + " occupied cells but "
                    + cellsWritten + " written");
        }
        int externalRefsAccountedFor = externalRefsResolved + externalRefsQueued;
        if (externalRefsAccountedFor != externalRefsTotal) {
            reasons.add("external_ref_reconciliation_mismatch: " + externalRefsTotal
                    + " external refs but " + externalRefsAccountedFor + " resolved or queued");
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
