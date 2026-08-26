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

    @Test
    void blankThenBoldSumOfLinesAbove_staysOneRegion() {
        Map<Long, RegionDetector.RegionCell> cells = cells(
                text("B1", 1, 2, "Line A"),
                number("D1", 1, 4, "10"),
                text("B2", 2, 2, "Line B"),
                number("D2", 2, 4, "20"),
                boldText("B4", 4, 2, "TOTAL"),
                formula("D4", 4, 4, "=SUM(D1:D2)", "30"));

        List<RegionDetector.DetectedRegion> regions = detector.detect("Sheet", cells);

        assertThat(regions).hasSize(1);
        assertThat(regions.getFirst().startRow()).isEqualTo(1);
        assertThat(regions.getFirst().endRow()).isEqualTo(4);
    }

    @Test
    void stackedTablesWithNewHeaderRow_split() {
        Map<Long, RegionDetector.RegionCell> cells = cells(
                text("B1", 1, 2, "Particulars"),
                text("C1", 1, 3, "Year 1"),
                text("B2", 2, 2, "Revenue"),
                number("C2", 2, 3, "100"),
                text("B4", 4, 2, "Particulars"),
                text("C4", 4, 3, "Year 1"),
                text("B5", 5, 2, "Cost"),
                number("C5", 5, 3, "200"));

        List<RegionDetector.DetectedRegion> regions = detector.detect("Sheet", cells);

        assertThat(regions).hasSize(2);
        assertThat(regions.get(0).startRow()).isEqualTo(1);
        assertThat(regions.get(0).endRow()).isEqualTo(2);
        assertThat(regions.get(1).startRow()).isEqualTo(4);
        assertThat(regions.get(1).endRow()).isEqualTo(5);
    }

    @Test
    void sharedHeaderInflowAndOutflow_staysOneRegion() {
        Map<Long, RegionDetector.RegionCell> cells = cells(
                text("B1", 1, 2, "Particulars"),
                text("C1", 1, 3, "Year 1"),
                text("D1", 1, 4, "Year 2"),
                text("B2", 2, 2, "CASH INFLOW"),
                number("C2", 2, 3, "100"),
                number("D2", 2, 4, "110"),
                text("B3", 3, 2, "Receipts"),
                number("C3", 3, 3, "100"),
                number("D3", 3, 4, "110"),
                boldText("B5", 5, 2, "TOTAL"),
                formula("C5", 5, 3, "=SUM(C3:C4)", "100"),
                formula("D5", 5, 4, "=SUM(D3:D4)", "110"),
                text("B7", 7, 2, "CASH OUTFLOWS"),
                number("C7", 7, 3, "50"),
                number("D7", 7, 4, "999"));

        List<RegionDetector.DetectedRegion> regions = detector.detect("Sheet", cells);

        assertThat(regions).hasSize(1);
        assertThat(regions.getFirst().startRow()).isEqualTo(1);
        assertThat(regions.getFirst().endRow()).isEqualTo(7);
    }

    @Test
    void titleAboveFirstHeaderRow_staysOneRegion() {
        Map<Long, RegionDetector.RegionCell> cells = cells(
                text("E1", 1, 5, "Projected Cash Flow"),
                text("D3", 3, 4, "Particulars"),
                text("E3", 3, 5, "Year 1"),
                text("D4", 4, 4, "Revenue"),
                number("E4", 4, 5, "100"));

        List<RegionDetector.DetectedRegion> regions = detector.detect("CASH FLOW", cells);

        assertThat(regions).hasSize(1);
        assertThat(regions.getFirst().startRow()).isEqualTo(1);
        assertThat(regions.getFirst().endRow()).isEqualTo(4);
    }

    @Test
    void openingBalanceAfterTotal_staysOneRegion() {
        Map<Long, RegionDetector.RegionCell> cells = cells(
                text("B1", 1, 2, "Particulars"),
                text("C1", 1, 3, "Year 1"),
                text("B2", 2, 2, "Receipts"),
                number("C2", 2, 3, "100"),
                boldText("B3", 3, 2, "TOTAL"),
                formula("C3", 3, 3, "=SUM(C2:C2)", "100"),
                text("B5", 5, 2, "Opening Balance"),
                number("C5", 5, 3, "10"),
                text("B6", 6, 2, "Closing Balance"),
                formula("C6", 6, 3, "=C5+C3", "110"));

        List<RegionDetector.DetectedRegion> regions = detector.detect("CASH FLOW", cells);

        assertThat(regions).hasSize(1);
        assertThat(regions.getFirst().endRow()).isEqualTo(6);
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

    private static NormalizedCell boldText(String coord, int row, int col, String value) {
        return text(coord, row, col, value).withStyle(true, null, null, null);
    }

    private static NormalizedCell formula(String coord, int row, int col, String formulaText,
            String cached) {
        return new NormalizedCell(coord, row, col, cached, "formula", "number", cached, cached,
                new BigDecimal(cached), null, null, formulaText, formulaText, "cached", cached, "ok",
                false, null, false, null, null, null, false, false, null, "cell", false, false, false);
    }
}
