package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import com.resurgent.tev.parser.db.CellReferenceRow;
import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ErrorCascadeEngine}: tracing root errors, populating {@code cell_error_root}
 * and {@code cell.error_descendant}, and barrier cells stopping propagation.
 */
class ErrorCascadeEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void identifiesRootErrorAndCascadesToDependents() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(tempDir.resolve("errcascade.db"))) {
            Connection c = db.connection();
            WorkspaceRepository repo = new WorkspaceRepository(c);

            long sourceFileId = repo.insertSourceFile(1L, "err.xlsx", "hash18", "fm_xlsx", Timestamps.now(), "0.1.0", null);
            long parseRunId = repo.insertParseRun(sourceFileId, 1L, "0.1.0", "cfg", Timestamps.now(), null, "success", "{}");
            long ws1 = repo.insertWorksheet(parseRunId, "Sheet1", 0);

            // Root error cell A1: =10/0 -> #DIV/0!
            NormalizedCell cellA1 = new NormalizedCell(
                    "A1", 1, 1, "=10/0", "formula", "error", null, "#DIV/0!",
                    null, null, null, "10/0", "10/0", "ok", "#DIV/0!", "fresh",
                    false, null, true, "#DIV/0!", null, null, false, false, null, "cell", false, false, false);
            long idA1 = repo.insertCell(ws1, cellA1);

            // Dependent error cell B1: =A1 -> #DIV/0!
            NormalizedCell cellB1 = new NormalizedCell(
                    "B1", 1, 2, "=A1", "formula", "error", null, "#DIV/0!",
                    null, null, null, "A1", "A1", "ok", "#DIV/0!", "fresh",
                    false, null, true, "#DIV/0!", null, null, false, false, null, "cell", false, false, false);
            long idB1 = repo.insertCell(ws1, cellB1);

            repo.insertCellReference(new CellReferenceRow(idB1, 0, "A1", "local_cell", "Sheet1", ws1, "A1", idA1, null, false, false, 0, -1, false, false, null));

            Map<Long, List<Long>> adjacency = ReferenceGraphLoader.loadAdjacency(repo, parseRunId);
            ErrorCascadeEngine engine = new ErrorCascadeEngine(repo);
            engine.processErrorCascades(parseRunId, adjacency);

            try (ResultSet rs = c.createStatement().executeQuery("SELECT cell_id, error_root_cell_id FROM cell_error_root ORDER BY cell_id")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("cell_id")).isEqualTo(idA1);
                assertThat(rs.getLong("error_root_cell_id")).isEqualTo(idA1);

                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("cell_id")).isEqualTo(idB1);
                assertThat(rs.getLong("error_root_cell_id")).isEqualTo(idA1);
            }
        }
    }

    @Test
    void nonErrorCellDownstreamOfRootIsMarkedErrorDescendant() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(tempDir.resolve("errcascade-descendant.db"))) {
            Connection c = db.connection();
            WorkspaceRepository repo = new WorkspaceRepository(c);

            long sourceFileId = repo.insertSourceFile(1L, "err2.xlsx", "hash19", "fm_xlsx", Timestamps.now(), "0.1.0", null);
            long parseRunId = repo.insertParseRun(sourceFileId, 1L, "0.1.0", "cfg", Timestamps.now(), null, "success", "{}");
            long ws1 = repo.insertWorksheet(parseRunId, "Sheet1", 0);

            NormalizedCell cellA1 = new NormalizedCell(
                    "A1", 1, 1, "=10/0", "formula", "error", null, "#DIV/0!",
                    null, null, null, "10/0", "10/0", "ok", "#DIV/0!", "fresh",
                    false, null, true, "#DIV/0!", null, null, false, false, null, "cell", false, false, false);
            long idA1 = repo.insertCell(ws1, cellA1);

            // B1 = A1 & "" -> not itself flagged error, but its evaluation chain passes through A1.
            NormalizedCell cellB1 = new NormalizedCell(
                    "B1", 1, 2, "=A1", "formula", "number", null, "0",
                    null, null, null, "A1", "A1", "ok", "0", "fresh",
                    false, null, false, null, null, null, false, false, null, "cell", false, false, false);
            long idB1 = repo.insertCell(ws1, cellB1);

            repo.insertCellReference(new CellReferenceRow(idB1, 0, "A1", "local_cell", "Sheet1", ws1, "A1", idA1, null, false, false, 0, -1, false, false, null));

            Map<Long, List<Long>> adjacency = ReferenceGraphLoader.loadAdjacency(repo, parseRunId);
            ErrorCascadeEngine engine = new ErrorCascadeEngine(repo);
            engine.processErrorCascades(parseRunId, adjacency);

            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT error_descendant FROM cell WHERE cell_id = " + idB1)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("error_descendant")).isTrue();
            }
        }
    }

    @Test
    void barrierCellStopsPropagationAndIsNotMarkedDescendant() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(tempDir.resolve("errcascade-barrier.db"))) {
            Connection c = db.connection();
            WorkspaceRepository repo = new WorkspaceRepository(c);

            long sourceFileId = repo.insertSourceFile(1L, "err3.xlsx", "hash20", "fm_xlsx", Timestamps.now(), "0.1.0", null);
            long parseRunId = repo.insertParseRun(sourceFileId, 1L, "0.1.0", "cfg", Timestamps.now(), null, "success", "{}");
            long ws1 = repo.insertWorksheet(parseRunId, "Sheet1", 0);

            // A1: root error.
            NormalizedCell cellA1 = new NormalizedCell(
                    "A1", 1, 1, "=10/0", "formula", "error", null, "#DIV/0!",
                    null, null, null, "10/0", "10/0", "ok", "#DIV/0!", "fresh",
                    false, null, true, "#DIV/0!", null, null, false, false, null, "cell", false, false, false);
            long idA1 = repo.insertCell(ws1, cellA1);

            // B1 = IFERROR(A1, 0): consumes the error, itself not an error.
            NormalizedCell cellB1 = new NormalizedCell(
                    "B1", 1, 2, "=IFERROR(A1,0)", "formula", "number", null, "0",
                    null, null, null, "IFERROR(A1,0)", "IFERROR(A1,0)", "ok", "0", "fresh",
                    false, null, false, null, null, null, false, false, null, "cell", false, false, false);
            long idB1 = repo.insertCell(ws1, cellB1);
            repo.updateCellErrorBarrier(idB1, true);

            // C1 = B1: downstream of the barrier, must NOT be marked error_descendant.
            NormalizedCell cellC1 = new NormalizedCell(
                    "C1", 1, 3, "=B1", "formula", "number", null, "0",
                    null, null, null, "B1", "B1", "ok", "0", "fresh",
                    false, null, false, null, null, null, false, false, null, "cell", false, false, false);
            long idC1 = repo.insertCell(ws1, cellC1);

            repo.insertCellReference(new CellReferenceRow(idB1, 0, "A1", "local_cell", "Sheet1", ws1, "A1", idA1, null, false, false, 0, -1, false, false, null));
            repo.insertCellReference(new CellReferenceRow(idC1, 0, "B1", "local_cell", "Sheet1", ws1, "B1", idB1, null, false, false, 0, -1, false, false, null));

            Map<Long, List<Long>> adjacency = ReferenceGraphLoader.loadAdjacency(repo, parseRunId);
            ErrorCascadeEngine engine = new ErrorCascadeEngine(repo);
            engine.processErrorCascades(parseRunId, adjacency);

            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT error_descendant FROM cell WHERE cell_id = " + idB1)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("error_descendant")).isFalse();
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT error_descendant FROM cell WHERE cell_id = " + idC1)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("error_descendant")).isFalse();
            }
        }
    }
}
