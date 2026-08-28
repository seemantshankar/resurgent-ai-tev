package com.resurgent.tev.parser.ingest;

import java.util.ArrayList;
import java.util.List;

/** Cell-count QA gate for ingest runs. */
public final class QaGate {

    private QaGate() {
    }

    public static QaGateResult evaluate(int cellsIn, int cellsWritten) {
        List<String> reasons = new ArrayList<>();
        int cellsRejected = cellsIn - cellsWritten;
        if (cellsRejected != 0) {
            reasons.add("cell_reconciliation_mismatch: " + cellsIn + " occupied cells but "
                    + cellsWritten + " written");
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
