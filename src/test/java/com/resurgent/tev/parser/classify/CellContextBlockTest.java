package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CellContextBlockTest {

    @Test
    void oneFormatterWritesTheSameFactsForEveryPrompt() {
        CellContextBlock.Parts parts = new CellContextBlock.Parts(
                "33% of Ground floor area", "Area of floors in sq.m.", List.of("Exempted"), "Mezzanine floor");

        assertThat(CellContextBlock.lines(parts, "  ")).isEqualTo(
                "  Row Label: 33% of Ground floor area\n"
                        + "  Column Label: Area of floors in sq.m.\n"
                        + "  Part of: Mezzanine floor\n"
                        + "  Row note: Exempted\n");
        assertThat(CellContextBlock.inline(parts)).isEqualTo(
                "row: 33% of Ground floor area | column: Area of floors in sq.m."
                        + " | part of: Mezzanine floor | note: Exempted");
    }

    @Test
    void blankPartsAreLeftOutSoAnOldCellLooksTheSameAsBefore() {
        CellContextBlock.Parts parts = new CellContextBlock.Parts("Basement", "", List.of(), null);

        assertThat(CellContextBlock.lines(parts, "")).isEqualTo("Row Label: Basement\n");
        assertThat(CellContextBlock.inline(parts)).isEqualTo("row: Basement");
        assertThat(CellContextBlock.inline(new CellContextBlock.Parts("", "", List.of(), null))).isEmpty();
    }

    @Test
    void theResolverContextSuppliesNotesAndTheGroupFromTheRegionGeometry() {
        long ws = 7L;
        Map<Long, InterpretationCellView> byId = new HashMap<>();
        InterpretationCellView[] cells = {
            text(1, ws, "B12", 12, 2, "Mezzanine floor"),
            num(2, ws, "A12", 12, 1, "3"),
            text(3, ws, "B14", 14, 2, "33% of Ground floor area"),
            num(4, ws, "C14", 14, 3, "68.29"),
            text(5, ws, "G14", 14, 7, "Exempted"),
        };
        for (InterpretationCellView c : cells) {
            byId.put(c.cellId(), c);
        }
        CandidateRow region = new CandidateRow(
                10L, 1L, ws, "child", null, 2, 1, 29, 7, null, null, null, false, null, null, null, "t", null);
        Set<Long> ids = new HashSet<>(byId.keySet());
        Map<Long, List<CandidateRow>> owners = new HashMap<>();
        for (Long id : ids) {
            owners.put(id, List.of(region));
        }
        InterpretationEvidenceResolver.ResolveCache cache = new InterpretationEvidenceResolver.ResolveCache(byId);
        cache.useGeometry(Map.of(10L, new HeaderGeometry(10L, List.of(), List.of(2), List.of(7), List.of(1))));
        ResolverCellContext context = new ResolverCellContext(
                1L, cache, owners, Map.of(10L, ids), Map.of(10L, region), Map.of(), Map.of(), "", null);

        InterpretationCellView target = byId.get(4L);

        assertThat(context.rowNotes(target)).containsExactly("Exempted");
        assertThat(context.partOf(target)).isEqualTo("Mezzanine floor");
    }

    private static InterpretationCellView text(long id, long ws, String coord, int row, int col, String text) {
        return new InterpretationCellView(id, ws, coord, row, col, "text", text, text, null, null, null, null, null,
                null, null, false, null, false, false, null, "cell");
    }

    private static InterpretationCellView num(long id, long ws, String coord, int row, int col, String v) {
        return new InterpretationCellView(id, ws, coord, row, col, "number", null, v, v, null, null, null, null,
                null, null, false, null, false, false, null, "cell");
    }
}
