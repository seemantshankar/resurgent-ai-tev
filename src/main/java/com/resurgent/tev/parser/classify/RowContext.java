package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * What a row says beyond its own label, read from the region's geometry: the status text beside
 * its amounts (Exempted, Added), and the group it sits in (a sub-row of "Mezzanine floor").
 * The lookup answers the cell at (row, column), or null.
 */
final class RowContext {

    private static final int MAX_WALK = 200;

    private RowContext() {}

    /** Text in the annotation columns of this cell's row; none for a cell that is itself a note. */
    static List<String> annotations(
            HeaderGeometry geometry,
            InterpretationCellView cell,
            BiFunction<Integer, Integer, InterpretationCellView> lookup) {
        List<String> notes = new ArrayList<>();
        if (geometry.annotationColumns().contains(cell.colNum())) {
            return notes;
        }
        for (int col : geometry.annotationColumns()) {
            String text = InterpretationEvidenceResolver.labelText(lookup.apply(cell.rowNum(), col));
            if (text != null && !notes.contains(text)) {
                notes.add(text);
            }
        }
        return notes;
    }

    /**
     * The group this row belongs to, or null if it starts one or belongs to none. In each group
     * column the nearest filled cell at or above the row starts a group, and every row below it
     * with that cell blank is in it. The group's name is that cell when it is text (a section
     * heading), otherwise the row-label text of the row that started it (a running item number).
     * Several group columns give an outer-to-inner path.
     */
    static String parent(
            HeaderGeometry geometry,
            InterpretationCellView cell,
            BiFunction<Integer, Integer, InterpretationCellView> lookup,
            int regionMinRow,
            int regionMaxRow) {
        List<String> path = new ArrayList<>();
        for (int groupCol : geometry.groupColumns()) {
            if (groupCol == cell.colNum()) {
                continue;
            }
            int floor = Math.max(regionMinRow, cell.rowNum() - MAX_WALK);
            for (int row = cell.rowNum(); row >= floor && row <= regionMaxRow; row--) {
                InterpretationCellView head = lookup.apply(row, groupCol);
                if (head == null || filled(head) == false) {
                    continue;
                }
                if (row < cell.rowNum()) {
                    String name = InterpretationEvidenceResolver.labelText(head);
                    path.add(name != null ? name : rowLabel(geometry, row, lookup));
                }
                break;
            }
        }
        path.removeIf(name -> name == null || name.isBlank());
        return path.isEmpty() ? null : String.join(" > ", path);
    }

    private static boolean filled(InterpretationCellView cell) {
        return InterpretationEvidenceResolver.labelText(cell) != null
                || cell.numericValue() != null && !cell.numericValue().isBlank();
    }

    private static String rowLabel(
            HeaderGeometry geometry, int row, BiFunction<Integer, Integer, InterpretationCellView> lookup) {
        List<String> parts = new ArrayList<>();
        for (int col : geometry.rowLabelColumns()) {
            String text = InterpretationEvidenceResolver.labelText(lookup.apply(row, col));
            if (text != null) {
                parts.add(text);
            }
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }
}
