package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class HeaderGeometryTest {

    @Test
    void roundTripsThroughJsonUsingA1Coordinates() {
        HeaderGeometry g = new HeaderGeometry(
                7L, List.of(new HeaderGeometry.Band(4, 7, 2, 7)), List.of(2));

        assertThat(g.toJson()).contains("B4:G7").contains("\"B\"");
        HeaderGeometry back = HeaderGeometry.fromJson(7L, g.toJson());
        assertThat(back).isEqualTo(g);
    }

    @Test
    void columnLettersAndNumbersAgreePastZ() {
        assertThat(HeaderGeometry.columnNumber("AA")).isEqualTo(27);
        assertThat(HeaderGeometry.columnLetters(27)).isEqualTo("AA");
        assertThat(HeaderGeometry.columnNumber("b")).isEqualTo(2);
        assertThat(HeaderGeometry.columnNumber("B2")).isZero();
    }

    @Test
    void rangeCornersMayComeInAnyOrder() {
        assertThat(HeaderGeometry.parseBand("G7:B4")).isEqualTo(new HeaderGeometry.Band(4, 7, 2, 7));
        assertThat(HeaderGeometry.parseBand("B4")).isNull();
    }

    @Test
    void nearestBandAboveWinsSoStackedTablesDoNotLeakHeaders() {
        HeaderGeometry g = new HeaderGeometry(
                1L,
                List.of(new HeaderGeometry.Band(2, 3, 2, 5), new HeaderGeometry.Band(20, 21, 2, 5)),
                List.of(1));

        assertThat(g.bandsAbove(10, 3)).containsExactly(new HeaderGeometry.Band(2, 3, 2, 5));
        assertThat(g.bandsAbove(30, 3)).containsExactly(new HeaderGeometry.Band(20, 21, 2, 5));
        assertThat(g.bandsAbove(10, 9)).isEmpty();
        assertThat(g.bandsAbove(2, 3)).isEmpty();
    }
}
