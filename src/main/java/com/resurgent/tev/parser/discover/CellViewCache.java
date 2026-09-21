package com.resurgent.tev.parser.discover;

import com.resurgent.tev.parser.db.CellPacketView;
import com.resurgent.tev.parser.db.PersistedCellReference;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-pass cache of Cell views and formula reference edges for one parse run.
 *
 * <p>Packets and Candidate links are built one Candidate at a time, but the Cells and
 * reference edges they read are fixed for the whole run. Without this cache every
 * Candidate re-ran the whole-run reference join and every unresolved formula edge
 * re-read its entire target worksheet from SQLite, which dominated discover and classify
 * on large workbooks.
 *
 * <p>Not thread-safe. Valid only while the cell graph is unchanged: create one per
 * packet-building pass and never hold one across writes to {@code cell} or
 * {@code cell_reference}.
 */
final class CellViewCache {

    private final WorkspaceRepository repo;
    private final Map<Long, List<CellPacketView>> byWorksheet = new HashMap<>();
    private final Map<Long, CellPacketView> byCellId = new HashMap<>();
    private Long edgeParseRunId;
    private List<PersistedCellReference> edges;

    CellViewCache(WorkspaceRepository repo) {
        this.repo = Objects.requireNonNull(repo, "repo");
    }

    /** Every persisted Cell on a worksheet, read once per pass. */
    List<CellPacketView> worksheet(long worksheetId) throws SQLException {
        List<CellPacketView> cached = byWorksheet.get(worksheetId);
        if (cached != null) {
            return cached;
        }
        List<CellPacketView> loaded =
                List.copyOf(repo.selectCellPacketViewsForWorksheet(worksheetId));
        byWorksheet.put(worksheetId, loaded);
        for (CellPacketView view : loaded) {
            byCellId.putIfAbsent(view.cellId(), view);
        }
        return loaded;
    }

    /** Cells inside an A1-style range, filtered over the cached worksheet. */
    List<CellPacketView> targetRange(long worksheetId, String targetRange) throws SQLException {
        if (targetRange == null || targetRange.isBlank()) {
            return List.of();
        }
        return WorkspaceRepository.filterToTargetRange(worksheet(worksheetId), targetRange);
    }

    /** One Cell view, or {@code null} when the Cell is not persisted. */
    CellPacketView cell(long cellId) throws SQLException {
        if (byCellId.containsKey(cellId)) {
            return byCellId.get(cellId);
        }
        List<CellPacketView> views = repo.selectCellPacketViews(List.of(cellId));
        CellPacketView view = views.isEmpty() ? null : views.get(0);
        // Misses are cached too: a dangling edge must not re-query on every Candidate.
        byCellId.put(cellId, view);
        return view;
    }

    /**
     * Every reference edge in the parse run, in {@code from_cell_id, token_index} order,
     * read once per pass rather than once per Candidate.
     */
    List<PersistedCellReference> edgesForParseRun(long parseRunId) throws SQLException {
        if (edges == null || edgeParseRunId == null || edgeParseRunId != parseRunId) {
            edges = List.copyOf(repo.selectPersistedCellReferencesForParseRun(parseRunId));
            edgeParseRunId = parseRunId;
        }
        return edges;
    }
}
