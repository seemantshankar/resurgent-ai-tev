package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ReferenceResolver}: reference resolution & persistence in {@code cell_reference}.
 */
class ReferenceResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesOccupiedCellAndExternalLinkAndQueuesUnresolved() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(tempDir.resolve("refres.db"))) {
            Connection c = db.connection();
            WorkspaceRepository repo = new WorkspaceRepository(c);

            long sourceFileId = repo.insertSourceFile(1L, "ref.xlsx", "hash16", "fm_xlsx", Timestamps.now(), "0.1.0", null);
            long parseRunId = repo.insertParseRun(sourceFileId, 1L, "0.1.0", "cfg", Timestamps.now(), null, "success", "{}");
            long workbookId = repo.insertWorkbook(sourceFileId, "Excel", "16.0", 2, "[\"Sheet1\",\"P  L \"]", "[]", "{}", false, Timestamps.now(), Timestamps.now());
            long ws1 = repo.insertWorksheet(parseRunId, "Sheet1", 0);
            long ws2 = repo.insertWorksheet(parseRunId, "P  L ", 1);

            long extLinkId = repo.insertExternalLink(workbookId, "external", 15, "Manpower.xlsx", "unchecked", Timestamps.now());

            NormalizedCell targetCell = new NormalizedCell(
                    "F35", 35, 6, "100", "number", "number", "100", "100",
                    new java.math.BigDecimal("100"), null, null, null, null, null, null, null,
                    false, null, false, null, null, null, false, false, null, "cell", false, false, false);
            long targetCellId = repo.insertCell(ws2, targetCell);

            NormalizedCell sourceCell = new NormalizedCell(
                    "I19", 19, 9, "=[15]P  L !F35", "formula", "number", "100", "100",
                    new java.math.BigDecimal("100"), null, null, "[15]P  L !F35", "[15]P  L !F35", "ok", "100", "fresh",
                    false, null, false, null, null, null, false, false, null, "cell", false, false, false);
            long sourceCellId = repo.insertCell(ws1, sourceCell);

            FormulaToken token = new FormulaToken(0, "[15]P  L !F35", "external", "P  L ", "F35", false, false, 16, -3, false, false);

            Map<String, Long> sheetNameToId = Map.of("Sheet1", ws1, "P  L ", ws2);
            Map<Integer, Long> externalLinkMap = Map.of(15, extLinkId);
            Map<String, Map<String, Long>> cellCoordMap = Map.of("P  L ", Map.of("F35", targetCellId));

            ReferenceResolver resolver = new ReferenceResolver(repo);
            resolver.resolveAndPersist(sourceCellId, "Sheet1", List.of(token), parseRunId, sheetNameToId, externalLinkMap, cellCoordMap, Timestamps.now());

            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT from_cell_id, ref_kind, target_sheet_name, target_worksheet_id, resolved_cell_id, external_link_id, unresolved_reason FROM cell_reference")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("from_cell_id")).isEqualTo(sourceCellId);
                assertThat(rs.getString("target_sheet_name")).isEqualTo("P  L ");
                assertThat(rs.getLong("target_worksheet_id")).isEqualTo(ws2);
                assertThat(rs.getLong("resolved_cell_id")).isEqualTo(targetCellId);
                assertThat(rs.getLong("external_link_id")).isEqualTo(extLinkId);
                assertThat(rs.getString("unresolved_reason")).isNull();
            }
        }
    }

    @Test
    void missingSheetRecordsUnresolvedReasonAndReviewQueueRow() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(tempDir.resolve("miss-sheet.db"))) {
            Connection c = db.connection();
            WorkspaceRepository repo = new WorkspaceRepository(c);

            long sourceFileId = repo.insertSourceFile(1L, "miss.xlsx", "hash16", "fm_xlsx", Timestamps.now(), "0.1.0", null);
            long parseRunId = repo.insertParseRun(sourceFileId, 1L, "0.1.0", "cfg", Timestamps.now(), null, "success", "{}");
            long ws1 = repo.insertWorksheet(parseRunId, "Sheet1", 0);

            NormalizedCell cell = new NormalizedCell(
                    "A1", 1, 1, "=MissingSheet!A1", "formula", "number", null, null,
                    null, null, null, "MissingSheet!A1", "MissingSheet!A1", "ok", null, "missing",
                    false, null, false, null, null, null, false, false, null, "cell", false, false, false);
            long cellId = repo.insertCell(ws1, cell);

            FormulaToken token = new FormulaToken(0, "MissingSheet!A1", "cross_sheet_cell", "MissingSheet", "A1", false, false, 0, 0, false, false);

            ReferenceResolver resolver = new ReferenceResolver(repo);
            resolver.resolveAndPersist(cellId, "Sheet1", List.of(token), parseRunId, Map.of("Sheet1", ws1), Map.of(), Map.of(), Timestamps.now());

            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT unresolved_reason FROM cell_reference")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("unresolved_reason")).isEqualTo("sheet_not_found");
            }

            assertThat(repo.countReviewQueue()).isEqualTo(1);
        }
    }
}
