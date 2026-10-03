package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What a row says beyond its own label: status text beside the amounts, and the group it sits in. */
class RowContextTest {

    private static final long WS = 1L;
    private long nextId = 1;

    @Test
    void annotationColumnsGiveTheTextBesideTheRowsAmounts() {
        Grid g = new Grid();
        g.put(text("B8", 8, 2, "Basement"));
        g.put(number("C8", 8, 3, "284.11"));
        g.put(text("G8", 8, 7, "Exempted"));
        HeaderGeometry geometry = new HeaderGeometry(1L, List.of(), List.of(2), List.of(7), List.of());

        assertThat(RowContext.annotations(geometry, g.at(8, 3), g::at)).containsExactly("Exempted");
        assertThat(RowContext.annotations(geometry, g.at(8, 7), g::at)).isEmpty(); // not for the note itself
    }

    @Test
    void aRowWithBlankGroupCellBelongsToTheGroupAboveAndTakesItsLabel() {
        // serial no. in A starts a group; sub-rows leave it blank (the Royale "3 Mezzanine floor" block)
        Grid g = new Grid();
        g.put(number("A12", 12, 1, "3"));
        g.put(text("B12", 12, 2, "Mezzanine floor"));
        g.put(text("B13", 13, 2, "Area deducting lobby"));
        g.put(text("B14", 14, 2, "33% of Ground floor area"));
        g.put(number("A17", 17, 1, "4"));
        g.put(text("B17", 17, 2, "First floor"));
        g.put(number("C14", 14, 3, "68.29"));
        g.put(number("C17", 17, 3, "221.98"));
        HeaderGeometry geometry = new HeaderGeometry(1L, List.of(), List.of(2), List.of(), List.of(1));

        assertThat(RowContext.parent(geometry, g.at(14, 3), g::at, 12, 17)).isEqualTo("Mezzanine floor");
        assertThat(RowContext.parent(geometry, g.at(12, 2), g::at, 12, 17)).isNull(); // the group's own row
        assertThat(RowContext.parent(geometry, g.at(17, 3), g::at, 12, 17)).isNull();
    }

    @Test
    void aTextGroupCellIsItselfTheParentLabel() {
        // a section heading alone in column A; the items below leave A blank
        Grid g = new Grid();
        g.put(text("A5", 5, 1, "Current Assets"));
        g.put(text("B6", 6, 2, "Cash"));
        g.put(number("C6", 6, 3, "10"));
        HeaderGeometry geometry = new HeaderGeometry(1L, List.of(), List.of(2), List.of(), List.of(1));

        assertThat(RowContext.parent(geometry, g.at(6, 3), g::at, 5, 30)).isEqualTo("Current Assets");
    }

    @Test
    void geometryWithoutTheNewFieldsStillReadsFromOlderStoredJson() {
        HeaderGeometry g = HeaderGeometry.fromJson(9L, "{\"bands\":[\"B4:G7\"],\"rowLabelColumns\":[\"B\"]}");

        assertThat(g.annotationColumns()).isEmpty();
        assertThat(g.groupColumns()).isEmpty();
        HeaderGeometry full = new HeaderGeometry(9L, List.of(), List.of(2), List.of(7), List.of(1));
        assertThat(HeaderGeometry.fromJson(9L, full.toJson())).isEqualTo(full);
    }

    private final class Grid {
        final Map<Long, InterpretationCellView> cells = new HashMap<>();

        void put(InterpretationCellView cell) {
            cells.put(((long) cell.rowNum() << 20) | cell.colNum(), cell);
        }

        InterpretationCellView at(int row, int col) {
            return cells.get(((long) row << 20) | col);
        }
    }

    private InterpretationCellView text(String coord, int row, int col, String text) {
        return new InterpretationCellView(nextId++, WS, coord, row, col, "text", text, text, null, null, null, null,
                null, null, null, false, null, false, false, null, "cell");
    }

    private InterpretationCellView number(String coord, int row, int col, String value) {
        return new InterpretationCellView(nextId++, WS, coord, row, col, "number", null, value, value, null, null,
                null, null, null, null, false, null, false, false, null, "cell");
    }
}
