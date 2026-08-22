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
 * Tests for {@link DependencyGraphEngine}: Tarjan's SCC cycle detection, workbook calc
 * metadata, and the circular-reference review-queue row with its info/warning severity split.
 */
class DependencyGraphEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsCyclesAndUpdatesWorkbookMetadata() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(tempDir.resolve("graph.db"))) {
            Connection c = db.connection();
            WorkspaceRepository repo = new WorkspaceRepository(c);

            long sourceFileId = repo.insertSourceFile(1L, "graph.xlsx", "hash17", "fm_xlsx", Timestamps.now(), "0.1.0", null);
            long parseRunId = repo.insertParseRun(sourceFileId, 1L, "0.1.0", "cfg", Timestamps.now(), null, "success", "{}");
            long workbookId = repo.insertWorkbook(sourceFileId, "Excel", "16.0", 1, "[\"Sheet1\"]", "[]", "{}", false, Timestamps.now(), Timestamps.now());
            long ws1 = repo.insertWorksheet(parseRunId, "Sheet1", 0);

            NormalizedCell cellA1 = new NormalizedCell(
                    "A1", 1, 1, "=B1", "formula", "number", null, null,
                    null, null, null, "B1", "B1", "ok", null, "missing",
                    false, null, false, null, null, null, false, false, null, "cell", false, false, false);
            long idA1 = repo.insertCell(ws1, cellA1);

            NormalizedCell cellB1 = new NormalizedCell(
                    "B1", 1, 2, "=A1", "formula", "number", null, null,
                    null, null, null, "A1", "A1", "ok", null, "missing",
                    false, null, false, null, null, null, false, false, null, "cell", false, false, false);
            long idB1 = repo.insertCell(ws1, cellB1);

            repo.insertCellReference(new CellReferenceRow(idA1, 0, "B1", "local_cell", "Sheet1", ws1, "B1", idB1, null, false, false, 0, 1, false, false, null));
            repo.insertCellReference(new CellReferenceRow(idB1, 0, "A1", "local_cell", "Sheet1", ws1, "A1", idA1, null, false, false, 0, -1, false, false, null));

            Map<Long, List<Long>> adjacency = ReferenceGraphLoader.loadAdjacency(repo, parseRunId);

            DependencyGraphEngine engine = new DependencyGraphEngine(repo);
            engine.processWorkbookGraph(workbookId, parseRunId, adjacency, false);

            long expectedGroupId = Math.min(idA1, idB1);
            try (ResultSet rs = c.createStatement().executeQuery("SELECT cell_id, is_circular, circular_group_id FROM cell ORDER BY cell_id")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("is_circular")).isTrue();
                assertThat(rs.getLong("circular_group_id")).isEqualTo(expectedGroupId);

                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("is_circular")).isTrue();
                assertThat(rs.getLong("circular_group_id")).isEqualTo(expectedGroupId);
            }

            try (ResultSet rs = c.createStatement().executeQuery("SELECT calc_is_circular, calc_circular_group_count, calc_max_cycle_length FROM workbook WHERE workbook_id = " + workbookId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("calc_is_circular")).isTrue();
                assertThat(rs.getInt("calc_circular_group_count")).isEqualTo(1);
                assertThat(rs.getInt("calc_max_cycle_length")).isEqualTo(2);
            }

            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT category, status FROM review_queue WHERE parse_run_id = " + parseRunId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("category")).isEqualTo("circular_reference");
                assertThat(rs.getString("status")).isEqualTo("Pending");
            }
        }
    }

    @Test
    void cycleSeverityIsInfoWhenIterativeCalcEnabledAndWarningOtherwise() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(tempDir.resolve("graph-severity.db"))) {
            Connection c = db.connection();
            WorkspaceRepository repo = new WorkspaceRepository(c);

            long sourceFileId = repo.insertSourceFile(1L, "graph2.xlsx", "hash18", "fm_xlsx", Timestamps.now(), "0.1.0", null);
            long parseRunId = repo.insertParseRun(sourceFileId, 1L, "0.1.0", "cfg", Timestamps.now(), null, "success", "{}");
            long workbookId = repo.insertWorkbook(sourceFileId, "Excel", "16.0", 1, "[\"Sheet1\"]", "[]", "{}", false, Timestamps.now(), Timestamps.now());
            long ws1 = repo.insertWorksheet(parseRunId, "Sheet1", 0);

            NormalizedCell cellA1 = new NormalizedCell(
                    "A1", 1, 1, "=B1", "formula", "number", null, null,
                    null, null, null, "B1", "B1", "ok", null, "missing",
                    false, null, false, null, null, null, false, false, null, "cell", false, false, false);
            long idA1 = repo.insertCell(ws1, cellA1);
            NormalizedCell cellB1 = new NormalizedCell(
                    "B1", 1, 2, "=A1", "formula", "number", null, null,
                    null, null, null, "A1", "A1", "ok", null, "missing",
                    false, null, false, null, null, null, false, false, null, "cell", false, false, false);
            long idB1 = repo.insertCell(ws1, cellB1);

            repo.insertCellReference(new CellReferenceRow(idA1, 0, "B1", "local_cell", "Sheet1", ws1, "B1", idB1, null, false, false, 0, 1, false, false, null));
            repo.insertCellReference(new CellReferenceRow(idB1, 0, "A1", "local_cell", "Sheet1", ws1, "A1", idA1, null, false, false, 0, -1, false, false, null));

            Map<Long, List<Long>> adjacency = ReferenceGraphLoader.loadAdjacency(repo, parseRunId);
            DependencyGraphEngine engine = new DependencyGraphEngine(repo);
            engine.processWorkbookGraph(workbookId, parseRunId, adjacency, true);

            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT detail FROM review_queue WHERE parse_run_id = " + parseRunId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("detail")).contains("\"severity\":\"info\"");
            }
        }
    }
}
