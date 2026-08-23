package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import com.resurgent.tev.parser.db.CellReferenceRow;
import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ReferenceGraphLoader}: range expansion (§4.4), including the §13
 * whole-column-reference fixture — {@code =SUM(A:A)} must expand to edges reaching only
 * the worksheet's actually-occupied cells, clamped to its real bbox, never to Excel's
 * 1,048,576-row limit.
 */
class ReferenceGraphLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void wholeColumnReferenceExpandsOnlyToOccupiedCellsWithinRealBbox() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(tempDir.resolve("wholecol.db"))) {
            Connection c = db.connection();
            WorkspaceRepository repo = new WorkspaceRepository(c);

            long sourceFileId = repo.insertSourceFile(1L, "wholecol.xlsx", "hashwc", "fm_xlsx", Timestamps.now(), "0.1.0", null);
            long parseRunId = repo.insertParseRun(sourceFileId, 1L, "0.1.0", "cfg", Timestamps.now(), null, "success", "{}");
            // bbox deliberately small (rows 1-5) -- never Excel's 1,048,576-row max.
            long ws1 = repo.insertWorksheet(parseRunId, "Sheet1", 0, "visible", 1, 1, 5, 2, "A1:B5", 5, 0);

            long[] occupied = new long[3];
            for (int r = 1; r <= 3; r++) {
                NormalizedCell cell = new NormalizedCell(
                        "A" + r, r, 1, String.valueOf(r * 10), "number", "number", null, null,
                        new java.math.BigDecimal(r * 10), null, null, null, null, null, null, null,
                        false, null, false, null, null, null, false, false, null, "cell", false, false, false);
                occupied[r - 1] = repo.insertCell(ws1, cell);
            }

            NormalizedCell formulaCell = new NormalizedCell(
                    "B1", 1, 2, "=SUM(A:A)", "formula", "number", null, null,
                    null, null, null, "SUM(A:A)", "SUM(A:A)", "ok", null, "missing",
                    false, null, false, null, null, null, false, false, null, "cell", false, false, false);
            long formulaCellId = repo.insertCell(ws1, formulaCell);

            // Whole-column token: tokenizer bakes Excel's shorthand bounds into targetRange
            // (A1:A65536), with is_whole_column=true; resolved_cell_id stays NULL (ranges are
            // stored unexpanded, per §4.4) -- expansion happens only in ReferenceGraphLoader.
            repo.insertCellReference(new CellReferenceRow(formulaCellId, 0, "A:A", "local_range",
                    "Sheet1", ws1, "A1:A65536", null, null, false, false, 0, -1, true, false, null));

            Map<Long, List<Long>> adjacency = ReferenceGraphLoader.loadAdjacency(repo, parseRunId);

            List<Long> targets = adjacency.get(formulaCellId);
            assertThat(targets).containsExactlyInAnyOrder(occupied[0], occupied[1], occupied[2]);
        }
    }
}
