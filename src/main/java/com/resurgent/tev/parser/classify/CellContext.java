package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Where the LLM fallback gets a cell's labels and region. Production wires the
 * candidate-scoped resolver ({@link ResolverCellContext}); {@link #scan} is the plain
 * geometric fallback for callers with no candidates.
 */
interface CellContext {

    /** Row label text, or {@code ""}. Never a number, error or date. */
    String rowLabel(InterpretationCellView cell);

    /** Column header text, or {@code ""}. */
    String columnLabel(InterpretationCellView cell);

    /** Layer A region of the cell, or {@link RegionContext#NONE}. */
    RegionContext region(InterpretationCellView cell);

    /** Stable identity of the workbook (file hash) for de-duplicating evidence across runs. */
    String workbookKey();

    Pattern NUMERIC_LOOKING = Pattern.compile("^[\\s,.\\-+0-9()%/]*$");

    /** A label must be a real text cell whose text is not just digits and punctuation. */
    static boolean isLabel(InterpretationCellView c) {
        return "text".equals(c.valueType())
                && c.textValue() != null
                && !c.textValue().isBlank()
                && !NUMERIC_LOOKING.matcher(c.textValue()).matches();
    }

    static CellContext scan(List<InterpretationCellView> cells) {
        return new ScanCellContext(cells);
    }

    /** Leftmost text in columns 0-3 of the row; nearest text above in the same column. */
    final class ScanCellContext implements CellContext {
        private static final int MAX_LABEL_COLUMN = 3;
        private static final int MAX_HEADER_DISTANCE = 15;

        private final Map<Long, Map<Integer, TreeMap<Integer, InterpretationCellView>>> byRow = new HashMap<>();
        private final Map<Long, Map<Integer, TreeMap<Integer, InterpretationCellView>>> byColumn = new HashMap<>();

        ScanCellContext(List<InterpretationCellView> cells) {
            for (InterpretationCellView c : cells) {
                if (!isLabel(c)) {
                    continue;
                }
                byRow.computeIfAbsent(c.worksheetId(), w -> new HashMap<>())
                        .computeIfAbsent(c.rowNum(), r -> new TreeMap<>())
                        .put(c.colNum(), c);
                byColumn.computeIfAbsent(c.worksheetId(), w -> new HashMap<>())
                        .computeIfAbsent(c.colNum(), r -> new TreeMap<>())
                        .put(c.rowNum(), c);
            }
        }

        @Override
        public String rowLabel(InterpretationCellView cell) {
            TreeMap<Integer, InterpretationCellView> row =
                    byRow.getOrDefault(cell.worksheetId(), Map.of()).get(cell.rowNum());
            if (row == null) {
                return "";
            }
            var first = row.firstEntry();
            return first != null && first.getKey() <= MAX_LABEL_COLUMN ? first.getValue().textValue() : "";
        }

        @Override
        public String columnLabel(InterpretationCellView cell) {
            TreeMap<Integer, InterpretationCellView> column =
                    byColumn.getOrDefault(cell.worksheetId(), Map.of()).get(cell.colNum());
            if (column == null) {
                return "";
            }
            var above = column.lowerEntry(cell.rowNum());
            return above != null && cell.rowNum() - above.getKey() <= MAX_HEADER_DISTANCE
                    ? above.getValue().textValue()
                    : "";
        }

        @Override
        public RegionContext region(InterpretationCellView cell) {
            return RegionContext.NONE;
        }

        @Override
        public String workbookKey() {
            return "";
        }
    }
}
