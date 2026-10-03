package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The Royale area statement: a 4-row merged header, then a body cell "Nil" that is not a header. */
class GeometryHeadersTest {

    private static final long RUN = 1L;
    private static final long WS = 100L;
    private static final long REGION = 10L;
    private long nextId = 1;

    @Test
    void withoutGeometryABodyCellNilIsTakenAsTheColumnHeader() {
        Fixture f = royale();

        assertThat(f.header(f.c17, null, EvidenceRole.COLUMN_HEADER)).containsExactly("Nil");
    }

    @Test
    void geometryGivesTheRealHeaderBandInsteadOfNil() {
        Fixture f = royale();
        HeaderGeometry g = new HeaderGeometry(REGION, List.of(new HeaderGeometry.Band(4, 7, 2, 7)), List.of(2));

        assertThat(f.header(f.c17, g, EvidenceRole.COLUMN_HEADER))
                .containsExactly("Area of floors", "including", "lobby, staircase & lift", "in sq.m.");
        assertThat(f.header(f.c17, g, EvidenceRole.ROW_HEADER)).containsExactly("First floor");
    }

    @Test
    void aColumnNoBandCoversFallsBackToTheDeterministicLabel() {
        Fixture f = royale();
        HeaderGeometry g = new HeaderGeometry(REGION, List.of(new HeaderGeometry.Band(4, 7, 3, 3)), List.of(2));

        assertThat(f.header(f.f17, g, EvidenceRole.COLUMN_HEADER)).isNotEmpty().doesNotContain("Area of floors");
    }

    // ---- fixture -------------------------------------------------------------------------

    private Fixture royale() {
        Fixture f = new Fixture();
        f.add(merged("B4", 4, 2, "Area of floors", true, "B4:C4"));
        f.add(merged("C4", 4, 3, "Area of floors", false, "B4:C4"));
        f.add(text("D4", 4, 4, "Area of lobby,"));
        f.add(text("F4", 4, 6, "Area of floors"));
        f.add(merged("B5", 5, 2, "including", true, "B5:C5"));
        f.add(merged("C5", 5, 3, "including", false, "B5:C5"));
        f.add(text("D5", 5, 4, "staircase"));
        f.add(text("F5", 5, 6, "deducting the area"));
        f.add(merged("B6", 6, 2, "lobby, staircase & lift", true, "B6:C6"));
        f.add(merged("C6", 6, 3, "lobby, staircase & lift", false, "B6:C6"));
        f.add(text("D6", 6, 4, "& lift"));
        f.add(text("F6", 6, 6, "lobby, staircase & lift"));
        f.add(text("B7", 7, 2, "Floor"));
        f.add(text("C7", 7, 3, "in sq.m."));
        f.add(text("D7", 7, 4, "in sq.m."));
        f.add(text("F7", 7, 6, "in sq.m."));
        f.add(number("C14", 14, 3, "68.29"));
        f.add(text("C15", 15, 3, "Nil"));
        f.add(text("F15", 15, 6, "Nil"));
        f.add(text("B17", 17, 2, "First floor"));
        f.c17 = f.add(number("C17", 17, 3, "221.98"));
        f.f17 = f.add(number("F17", 17, 6, "158.82"));
        return f;
    }

    private final class Fixture {
        final Map<Long, InterpretationCellView> byId = new HashMap<>();
        InterpretationCellView c17;
        InterpretationCellView f17;

        InterpretationCellView add(InterpretationCellView cell) {
            byId.put(cell.cellId(), cell);
            return cell;
        }

        List<String> header(InterpretationCellView target, HeaderGeometry geometry, String role) {
            CandidateRow region = new CandidateRow(
                    REGION, RUN, WS, "child", null, 2, 1, 29, 7,
                    null, null, null, false, null, null, null, "2026-01-01T00:00:00Z", null);
            Set<Long> ids = new HashSet<>(byId.keySet());
            Map<Long, List<CandidateRow>> owners = new HashMap<>();
            for (Long id : ids) {
                owners.put(id, List.of(region));
            }
            InterpretationEvidenceResolver.ResolveCache cache =
                    new InterpretationEvidenceResolver.ResolveCache(byId);
            if (geometry != null) {
                cache.useGeometry(Map.of(REGION, geometry));
            }
            List<InterpretationEvidence> evidence = InterpretationEvidenceResolver.resolve(
                    RUN, target, cache, owners, Map.of(REGION, ids), Map.of(REGION, region), null);
            List<String> texts = new ArrayList<>();
            for (InterpretationEvidence e : evidence) {
                if (role.equals(e.role()) && EvidenceResolution.RESOLVED.equals(e.resolution())) {
                    texts.add(e.sourceText());
                }
            }
            return texts;
        }
    }

    private InterpretationCellView text(String coord, int row, int col, String text) {
        return new InterpretationCellView(
                nextId++, WS, coord, row, col, "text", text, text, null, null, null, null, null, null, null,
                false, null, false, false, null, "cell");
    }

    private InterpretationCellView number(String coord, int row, int col, String value) {
        return new InterpretationCellView(
                nextId++, WS, coord, row, col, "number", null, value, value, null, null, null, null, null, null,
                false, null, false, false, null, "cell");
    }

    private InterpretationCellView merged(String coord, int row, int col, String text, boolean anchor, String range) {
        return new InterpretationCellView(
                nextId++, WS, coord, row, col, anchor ? "text" : "empty", anchor ? text : null, text,
                null, null, null, null, null, null, null, false, null,
                anchor, !anchor, range, anchor ? "cell" : "merged_anchor");
    }
}
