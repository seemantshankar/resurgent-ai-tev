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
    void skipTwoSameRow_doesNotJoinTwoNumbers() {
        Map<Long, RegionDetector.RegionCell> cells = cells(
                number("B1", 1, 2, "10"),
                number("E1", 1, 5, "20"));

        List<RegionDetector.DetectedRegion> regions = detector.detect("Sheet", cells);

        assertThat(regions).hasSize(2);
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
    void yearAxisHeaderGathersOverflowStubsAndSpacedValueRows() {
        Map<Long, RegionDetector.RegionCell> cells = cells(
                text("D1", 1, 4, "Year 1"),
                text("E1", 1, 5, "Year 2"),
                text("F1", 1, 6, "Year 3"),
                text("H2", 2, 8, "Notes"),
                text("A3", 3, 1, "LAND COST & DEVELOPMENT"),
                text("A5", 5, 1, "Opening Balance (WDV)"),
                number("D5", 5, 4, "100"),
                number("E5", 5, 5, "90"),
                number("F5", 5, 6, "80"),
                text("A7", 7, 1, "Less: Depreciation @ 10%"),
                number("D7", 7, 4, "10"),
                number("E7", 7, 5, "10"),
                number("F7", 7, 6, "10"),
                number("H3", 3, 8, "99"));

        List<RegionDetector.DetectedRegion> regions = detector.detect("depreciation", cells);

        assertThat(regions).anySatisfy(region -> {
            assertThat(region.startRow()).isEqualTo(1);
            assertThat(region.endRow()).isEqualTo(7);
            assertThat(region.startCol()).isEqualTo(1);
            assertThat(region.endCol()).isEqualTo(6);
        });
        assertThat(regions).anySatisfy(region ->
                assertThat(region.startCol()).isEqualTo(8));
    }

    @Test
    void yearAxisStopsWhenRowTypesLeaveTheHeaderSchema() {
        Map<Long, RegionDetector.RegionCell> cells = cells(
                text("D1", 1, 4, "Year 1"),
                text("E1", 1, 5, "Year 2"),
                text("F1", 1, 6, "Year 3"),
                text("A3", 3, 1, "LAND COST & DEVELOPMENT"),
                text("A5", 5, 1, "Written Down Value"),
                number("D5", 5, 4, "80"),
                number("E5", 5, 5, "70"),
                number("F5", 5, 6, "60"),
                text("A7", 7, 1, "TOTAL WDV"),
                number("D7", 7, 4, "80"),
                number("E7", 7, 5, "70"),
                number("F7", 7, 6, "60"),
                number("F9", 9, 6, "44"),
                text("F11", 11, 6, "APPENDIX 6"),
                text("D13", 13, 4, "Year 1"),
                text("E13", 13, 5, "Year 2"),
                text("F13", 13, 6, "Year 3"),
                text("A15", 15, 1, "BUILDING"),
                number("D15", 15, 4, "10"),
                number("E15", 15, 5, "9"),
                number("F15", 15, 6, "8"));

        List<RegionDetector.DetectedRegion> regions = detector.detect("depreciation", cells);

        assertThat(regions).anySatisfy(region -> {
            assertThat(region.startRow()).isEqualTo(1);
            assertThat(region.endRow()).isEqualTo(7);
        });
        assertThat(regions).anySatisfy(region ->
                assertThat(region.startRow()).isIn(11, 13));
        assertThat(regions).noneSatisfy(region -> {
            assertThat(region.startRow()).isLessThanOrEqualTo(1);
            assertThat(region.endRow()).isGreaterThanOrEqualTo(9);
        });
    }

    @Test
    void headerlessBoqLineUnderTotal_attachesToTableAbove() {
        Map<Long, RegionDetector.RegionCell> cells = cells(
                text("A1", 1, 1, "S.No"),
                text("B1", 1, 2, "Description"),
                text("C1", 1, 3, "Quantity"),
                text("D1", 1, 4, "Rate"),
                text("E1", 1, 5, "Amount"),
                number("A2", 2, 1, "1"),
                text("B2", 2, 2, "MBBR STP 100 KLD"),
                text("C2", 2, 3, "1Set"),
                number("D2", 2, 4, "1300000"),
                number("E2", 2, 5, "1300000"),
                text("D4", 4, 4, "Total Amount"),
                formula("E4", 4, 5, "=E2", "1300000"),
                number("A6", 6, 1, "5"),
                text("B6", 6, 2, "For Passanger Lifts (6 Nos)"),
                number("E6", 6, 5, "7200000"));

        List<RegionDetector.DetectedRegion> regions = detector.detect("Details", cells);

        assertThat(regions).hasSize(1);
        assertThat(regions.getFirst().key()).isEqualTo("Details!A1");
        assertThat(regions.getFirst().startRow()).isEqualTo(1);
        assertThat(regions.getFirst().endRow()).isEqualTo(6);
        assertThat(regions.getFirst().endCol()).isEqualTo(5);
    }

    @Test
    void stackedBoqAfterTotalPriceHeader_splitsAndDoesNotShredQuoteRows() {
        Map<Long, RegionDetector.RegionCell> cells = cells(
                text("A1", 1, 1, "S.No"),
                text("B1", 1, 2, "Specification"),
                text("C1", 1, 3, "Unit Price"),
                quantityText("D1", 1, 4, "Qty"),
                text("E1", 1, 5, "Total price"),
                number("A2", 2, 1, "1"),
                text("B2", 2, 2, "Lift"),
                number("C2", 2, 3, "100"),
                number("D2", 2, 4, "2"),
                number("E2", 2, 5, "200"),
                text("A3", 3, 1, "Total"),
                formula("E3", 3, 5, "=SUM(E2:E2)", "200"),
                text("A5", 5, 1, "S.NO."),
                text("B5", 5, 2, "NAME OF ITEM"),
                text("C5", 5, 3, "MAKE"),
                quantityText("D5", 5, 4, "QTY"),
                text("E5", 5, 5, "UNIT PRICE"),
                text("B6", 6, 2, "STORE"),
                text("A7", 7, 1, "ST.02"),
                text("B7", 7, 2, "S.S. RACK"),
                text("C7", 7, 3, "VSG"),
                number("D7", 7, 4, "1"),
                number("E7", 7, 5, "30600"),
                text("A8", 8, 1, "HK.04"),
                text("B8", 8, 2, "WORK TABLE"),
                text("C8", 8, 3, "VSG"),
                number("D8", 8, 4, "1"),
                number("E8", 8, 5, "45900"),
                text("D9", 9, 4, "Grand Total"),
                number("E9", 9, 5, "76500"));

        List<RegionDetector.DetectedRegion> regions = detector.detect("Details", cells);

        assertThat(regions).hasSize(2);
        assertThat(regions.get(0).key()).isEqualTo("Details!A1");
        assertThat(regions.get(0).startRow()).isEqualTo(1);
        assertThat(regions.get(0).endRow()).isEqualTo(3);
        assertThat(regions.get(1).key()).isEqualTo("Details!A5");
        assertThat(regions.get(1).startRow()).isEqualTo(5);
        assertThat(regions.get(1).endRow()).isEqualTo(9);
    }

    @Test
    void sectionLetterAndTitleAboveQuote_stayOneRegionWithTheTable() {
        Map<Long, RegionDetector.RegionCell> cells = cells(
                text("A1", 1, 1, "D"),
                text("B1", 1, 2, "AIR CONDITIONING"),
                text("A2", 2, 1, "FORECAST ESTIMATE"),
                text("A3", 3, 1, "CLIENT:- OM ARHAM VENTURES"),
                text("A5", 5, 1, "S no"),
                text("B5", 5, 2, "Description"),
                text("C5", 5, 3, "Unit"),
                quantityText("D5", 5, 4, "Qty"),
                text("E5", 5, 5, "Rate"),
                text("F5", 5, 6, "Amount"),
                number("A6", 6, 1, "1"),
                text("B6", 6, 2, "SITC of VRV/VR"),
                text("C6", 6, 3, "PER TR"),
                number("D6", 6, 4, "610"),
                number("E6", 6, 5, "33000"),
                number("F6", 6, 6, "20130000"),
                number("A7", 7, 1, "2"),
                text("B7", 7, 2, "BASEMENT VENTILATION"),
                text("C7", 7, 3, "PER CFM"),
                number("D7", 7, 4, "62000"),
                number("E7", 7, 5, "15"),
                number("F7", 7, 6, "930000"),
                text("B8", 8, 2, "TOTAL COST OF AIR CONDITIONING WORK IN Rs."),
                formula("F8", 8, 6, "=SUM(F6:F7)", "21060000"));

        List<RegionDetector.DetectedRegion> regions = detector.detect("Details", cells);

        assertThat(regions).hasSize(1);
        assertThat(regions.getFirst().key()).isEqualTo("Details!A1");
        assertThat(regions.getFirst().startRow()).isEqualTo(1);
        assertThat(regions.getFirst().endRow()).isEqualTo(8);
    }

    @Test
    void mixedQuoteRowsUnderSharedHeader_stayOneRegion() {
        Map<Long, RegionDetector.RegionCell> cells = cells(
                text("A1", 1, 1, "S.NO."),
                text("B1", 1, 2, "NAME OF ITEM"),
                text("C1", 1, 3, "MAKE"),
                text("A2", 2, 1, "ST.02"),
                text("B2", 2, 2, "S.S. RACK"),
                text("C2", 2, 3, "VSG"),
                number("D2", 2, 4, "1"),
                number("E2", 2, 5, "30600"),
                text("A3", 3, 1, "HK.04"),
                text("B3", 3, 2, "WORK TABLE"),
                text("C3", 3, 3, "VSG"),
                number("D3", 3, 4, "1"),
                number("E3", 3, 5, "45900"));

        List<RegionDetector.DetectedRegion> regions = detector.detect("Details", cells);

        assertThat(regions).hasSize(1);
        assertThat(regions.getFirst().startRow()).isEqualTo(1);
        assertThat(regions.getFirst().endRow()).isEqualTo(3);
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

    private static NormalizedCell quantityText(String coord, int row, int col, String value) {
        return new NormalizedCell(coord, row, col, value, "text", "quantity_text", value, value,
                null, null, null, null, null, null, null, null, false,
                new ParsedQuantity(null, value, value), false, null, null, null, false, false, null,
                "cell", false, false, false);
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
