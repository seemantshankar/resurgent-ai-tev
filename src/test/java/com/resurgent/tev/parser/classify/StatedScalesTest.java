package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A scale Layer A reports is used only when the cell it cites really says so. */
class StatedScalesTest {

    private static final InterpretationCellView TITLE = cell(1, "J6", 6, "text", "Rs. In Crore");
    private static final InterpretationCellView AMOUNT = cell(2, "D10", 10, "number", null);

    @Test
    void aVerifiedRegionScaleIsUsedAndBeatsTheSheetTitle() {
        StatedScales scales = scales(disposition("crore", "J6"), List.of(
                cell(3, "A1", 1, "text", "Rs. In Lacs"), TITLE, AMOUNT));

        assertThat(scales.of(AMOUNT)).isEqualTo(CellScale.CRORE);
        assertThat(scales.sentence(AMOUNT)).contains("crores");
    }

    @Test
    void aClaimTheCitedCellDoesNotSupportIsDropped() {
        // The model says lakh, but J6 says crore: no region scale, so the sheet fallback applies.
        StatedScales scales = scales(disposition("lakh", "J6"), List.of(TITLE, AMOUNT));

        assertThat(scales.of(AMOUNT)).isEqualTo(CellScale.CRORE); // from the sheet title itself
    }

    @Test
    void aClaimCitingNoCellIsDropped() {
        StatedScales scales = scales(disposition("lakh", "Z99"), List.of(AMOUNT));

        assertThat(scales.of(AMOUNT)).isNull();
        assertThat(scales.sentence(AMOUNT)).isEmpty();
    }

    @Test
    void anUnknownScaleNameIsDropped() {
        StatedScales scales = scales(disposition("zillion", "J6"), List.of(AMOUNT));

        assertThat(scales.of(AMOUNT)).isNull();
    }

    private static StatedScales scales(PacketDisposition d, List<InterpretationCellView> cells) {
        return new StatedScales(
                cells, Map.of(AMOUNT.cellId(), List.of(candidate())), Map.of(d.candidateId(), d));
    }

    private static PacketDisposition disposition(String scale, String cell) {
        return new PacketDisposition(
                7L, 1L, "assets", "main", "primary", List.of(), List.of(), null, "About.", null, false, scale, cell);
    }

    private static CandidateRow candidate() {
        return new CandidateRow(7L, 1L, 1L, "child", null, 1, 1, 20, 10, null, null, null, false, null, null,
                null, null, null);
    }

    private static InterpretationCellView cell(long id, String coord, int row, String type, String text) {
        return new InterpretationCellView(
                id, 1L, coord, row, 1, type, text, text, null, null, null, null, null, null, null,
                false, null, false, false, null, "cell");
    }
}
