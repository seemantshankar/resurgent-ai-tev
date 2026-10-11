package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SheetScaleStatementTest {

    @Test
    void readsTheScaleFromRealTitles() {
        assertThat(SheetScaleStatement.statedBy("Rs. In Lacs")).isEqualTo(CellScale.LAKH);
        assertThat(SheetScaleStatement.statedBy("TOTAL ROOM SALES(Rs. In Lacs)")).isEqualTo(CellScale.LAKH);
        assertThat(SheetScaleStatement.statedBy("Amt. \\n(in Lac Rs.)".replace("\\n", "\n"))).isEqualTo(CellScale.LAKH);
        assertThat(SheetScaleStatement.statedBy("(Rs. in crore)")).isEqualTo(CellScale.CRORE);
        assertThat(SheetScaleStatement.statedBy("Amount in Rs")).isEqualTo(CellScale.UNIT);
        assertThat(SheetScaleStatement.statedBy("(Amt. in Rs.)")).isEqualTo(CellScale.UNIT);
    }

    @Test
    void ignoresTextThatStatesNoMoneyScale() {
        assertThat(SheetScaleStatement.statedBy("Rate (Rs.)")).isNull();
        assertThat(SheetScaleStatement.statedBy("Amount")).isNull();
        assertThat(SheetScaleStatement.statedBy("Lakhs of visitors expected")).isNull();
        assertThat(SheetScaleStatement.statedBy(null)).isNull();
        assertThat(SheetScaleStatement.statedBy(
                "The amounts quoted in this long explanatory paragraph are in crore for the purpose of the "
                        + "bank and are approximate only")).isNull();
    }

    @Test
    void aLabelStatesRupeesInBracketsOrWithIn() {
        assertThat(SheetScaleStatement.statesRupees("Amount (Rs.)")).isTrue();
        assertThat(SheetScaleStatement.statesRupees("Cost (INR)")).isTrue();
        assertThat(SheetScaleStatement.statesRupees("Amt. in Rs.")).isTrue();
        assertThat(SheetScaleStatement.statesRupees("Principal")).isFalse();
        assertThat(SheetScaleStatement.statesRupees("Rs. in Lakhs")).isFalse();
    }

    @Test
    void aSheetThatStatesTwoScalesStatesNone() {
        Map<Long, CellScale> scales = SheetScaleStatement.byWorksheet(List.of(
                text(1, 1, "Rs. In Lacs"),
                text(1, 2, "Amount in Rs"),
                text(2, 1, "Rs. In Lacs"),
                text(2, 40, "Amount in Rs"),
                text(3, 2, "Rs. In Lakhs"),
                text(3, 3, "Rs. in lacs")));

        assertThat(scales).doesNotContainKey(1L);
        assertThat(scales.get(2L)).isEqualTo(CellScale.LAKH); // row 40 is data, not a title
        assertThat(scales.get(3L)).isEqualTo(CellScale.LAKH); // two statements, one scale
    }

    private static InterpretationCellView text(long sheet, int row, String text) {
        return new InterpretationCellView(
                row, sheet, "A" + row, row, 1, "text", text, text, null, null, null, null, null, null, null,
                false, null, false, false, null, "cell");
    }
}
