package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;

import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ErrorCascadeEngine}: tracing root errors and populating {@code cell_error_root}.
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

            repo.insertCellReference(idB1, 0, "A1", "local_cell", "Sheet1", ws1, "A1", idA1, null, false, false, 0, -1, false, false, null);

            ErrorCascadeEngine engine = new ErrorCascadeEngine(repo);
            engine.processErrorCascades(parseRunId);

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
}
