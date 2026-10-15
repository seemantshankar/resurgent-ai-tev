package com.resurgent.tev.doc.db;

import com.resurgent.tev.parser.db.CellReading;
import com.resurgent.tev.parser.db.InterpretationCellView;
import com.resurgent.tev.parser.db.NomenclatureBinding;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only adapter from the FM cell graph to document conflict assessment (#147). */
public final class FmConflictSources {
    private FmConflictSources() {}

    /** A Layer B bound cell with the persisted reading, not a fresh interpretation. */
    public record Cell(String path, String source, CellReading reading,
            InterpretationCellView value, List<Period> periods) {}

    /** A period cue already resolved (or refused) by FM classification. */
    public record Period(String text, String normalized, String resolution) {}

    /** All bound FM paths in this mandate; multiple candidates are kept, never picked. */
    public static Map<String, List<Cell>> forMandate(Connection connection, long mandateId)
            throws SQLException {
        WorkspaceRepository repo = new WorkspaceRepository(connection);
        List<Long> runs = new ArrayList<>();
        try (var ps = connection.prepareStatement(
                "SELECT DISTINCT p.parse_run_id FROM parse_run p"
                        + " JOIN nomenclature_binding b ON b.parse_run_id = p.parse_run_id"
                        + " WHERE p.mandate_id = ? ORDER BY p.parse_run_id")) {
            ps.setLong(1, mandateId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    runs.add(rs.getLong(1));
                }
            }
        }
        Map<String, List<Cell>> paths = new LinkedHashMap<>();
        for (long run : runs) {
            Map<Long, List<Period>> periods = periods(connection, run);
            Map<Long, CellReading> readings = new HashMap<>();
            for (CellReading reading : repo.selectCellReadingsForParseRun(run)) {
                readings.put(reading.cellId(), reading);
            }
            Map<Long, InterpretationCellView> cells = new HashMap<>();
            for (InterpretationCellView cell : repo.selectInterpretationCellsForParseRun(run)) {
                cells.put(cell.cellId(), cell);
            }
            Map<Long, String> sheets = new HashMap<>();
            for (var sheet : repo.selectWorksheetsForParseRun(run)) {
                sheets.put(sheet.worksheetId(), sheet.sheetName());
            }
            for (NomenclatureBinding binding : repo.selectNomenclatureBindings(run)) {
                InterpretationCellView cell = cells.get(binding.cellId());
                if (cell == null) {
                    continue;
                }
                String path = binding.pathRoot() + " > " + binding.path();
                String source = sheets.get(cell.worksheetId()) + "!" + binding.coord();
                paths.computeIfAbsent(path, key -> new ArrayList<>()).add(
                        new Cell(path, source, readings.get(binding.cellId()), cell,
                                periods.getOrDefault(binding.cellId(), List.of())));
            }
        }
        return paths;
    }

    private static Map<Long, List<Period>> periods(Connection connection, long run)
            throws SQLException {
        Map<Long, List<Period>> periods = new HashMap<>();
        try (var ps = connection.prepareStatement(
                "SELECT cell_id, source_text, normalized_value, resolution"
                        + " FROM cell_interpretation_evidence WHERE parse_run_id = ?"
                        + " AND role = 'period' ORDER BY cell_id, ordinal")) {
            ps.setLong(1, run);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    periods.computeIfAbsent(rs.getLong("cell_id"), key -> new ArrayList<>())
                            .add(new Period(rs.getString("source_text"),
                                    rs.getString("normalized_value"), rs.getString("resolution")));
                }
            }
        }
        return periods;
    }
}
