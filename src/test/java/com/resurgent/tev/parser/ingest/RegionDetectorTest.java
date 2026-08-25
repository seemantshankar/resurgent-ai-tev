package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RegionDetectorTest {

    private final RegionDetector detector = new RegionDetector();

    @Test
    void skipOneSameRow_joinsTextStubToNumber() {
        Map<Long, RegionDetector.RegionCell> cells = cells(
                text("B1", 1, 2, "TOTAL INFLOWS"),
                number("D1", 1, 4, "4263.86"));

        List<RegionDetector.DetectedRegion> regions = detector.detect("CASH FLOW", cells);

        assertThat(regions).hasSize(1);
        assertThat(regions.getFirst().key()).isEqualTo("CASH FLOW!B1");
        assertThat(regions.getFirst().startCol()).isEqualTo(2);
        assertThat(regions.getFirst().endCol()).isEqualTo(4);
        assertThat(regions.getFirst().cellIds()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void skipOneSameRow_doesNotJoinTwoNumbers() {
        Map<Long, RegionDetector.RegionCell> cells = cells(
                number("B1", 1, 2, "10"),
                number("D1", 1, 4, "20"));

        List<RegionDetector.DetectedRegion> regions = detector.detect("Sheet", cells);

        assertThat(regions).hasSize(2);
    }

    @Test
    void skipOneSameColumn_joinsTextStubToNumber() {
        Map<Long, RegionDetector.RegionCell> cells = cells(
                text("B1", 1, 2, "Increase in Term Loan"),
                number("B3", 3, 2, "2700"));

        List<RegionDetector.DetectedRegion> regions = detector.detect("Sheet", cells);

        assertThat(regions).hasSize(1);
        assertThat(regions.getFirst().startRow()).isEqualTo(1);
        assertThat(regions.getFirst().endRow()).isEqualTo(3);
    }

    @Test
    void skipOneSameColumn_stillJoinsTwoTextLabels() {
        Map<Long, RegionDetector.RegionCell> cells = cells(
                text("B1", 1, 2, "TOTAL INFLOWS"),
                text("B3", 3, 2, "CASH OUTFLOWS"));

        List<RegionDetector.DetectedRegion> regions = detector.detect("Sheet", cells);

        assertThat(regions).hasSize(1);
    }

    @Test
    void touchingHeaderAndNumber_stillJoin() {
        Map<Long, RegionDetector.RegionCell> cells = cells(
                text("B1", 1, 2, "TOTAL INFLOWS"),
                number("C1", 1, 3, "4263.86"));

        List<RegionDetector.DetectedRegion> regions = detector.detect("Sheet", cells);

        assertThat(regions).hasSize(1);
        assertThat(regions.getFirst().startCol()).isEqualTo(2);
        assertThat(regions.getFirst().endCol()).isEqualTo(3);
    }

    private static Map<Long, RegionDetector.RegionCell> cells(NormalizedCell... values) {
        Map<Long, RegionDetector.RegionCell> result = new LinkedHashMap<>();
        long id = 1;
        for (NormalizedCell cell : values) {
            result.put(id++, new RegionDetector.RegionCell(cell, null));
        }
        return result;
    }

    private static NormalizedCell text(String coord, int row, int col, String value) {
        return new NormalizedCell(coord, row, col, value, "text", "text", value, value,
                null, null, null, null, null, null, null, null, false, null, false, null,
                null, null, false, false, null, "cell", false, false, false);
    }

    private static NormalizedCell number(String coord, int row, int col, String value) {
        return new NormalizedCell(coord, row, col, value, "number", "number", value, value,
                new BigDecimal(value), null, null, null, null, null, null, null, false, null,
                false, null, null, null, false, false, null, "cell", false, false, false);
    }
}
