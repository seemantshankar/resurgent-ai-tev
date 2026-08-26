package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegionHeaderAnalyzerTest {

    private final RegionHeaderAnalyzer analyzer = new RegionHeaderAnalyzer();

    @Test
    void derivesPeriodColumnsAndLabelsFromWithinTheSuppliedRegion() {
        List<NormalizedCell> cells = List.of(
                text("A1", 1, 1, "Outside region"),
                text("B4", 4, 2, "Particulars"),
                text("D4", 4, 4, "Year 1"),
                text("E4", 4, 5, "FY 2025"),
                text("F4", 4, 6, "Yr 3"),
                text("B5", 5, 2, "Revenue"),
                number("D5", 5, 4, "100"),
                number("E5", 5, 5, "200"),
                number("F5", 5, 6, "300"));

        RegionHeaderContext context = analyzer.analyze(cells,
                new RegionHeaderAnalyzer.Bounds(4, 5, 2, 6));

        assertThat(context.headerRows()).containsExactly(4);
        assertThat(context.periodAxisByColumn()).containsExactly(
                java.util.Map.entry("D", 1), java.util.Map.entry("E", 2), java.util.Map.entry("F", 3));
        assertThat(context.rowLabelsByRow()).containsEntry(5, "Revenue");
        assertThat(context.columnLabelsByColumn())
                .containsEntry(4, "Year 1")
                .containsEntry(5, "FY 2025")
                .containsEntry(6, "Yr 3")
                .doesNotContainKey(1);
    }

    @Test
    void combinesMultiRowHeadersAndRecognizesCalendarAndQuarterPeriods() {
        List<NormalizedCell> cells = List.of(
                text("B8", 8, 2, "Metric"),
                text("D8", 8, 4, "FY 2024-25"),
                text("E8", 8, 5, "FY 2024-25"),
                text("D9", 9, 4, "Q1"),
                text("E9", 9, 5, "Q2"),
                text("B10", 10, 2, "EBITDA"),
                number("D10", 10, 4, "12"),
                number("E10", 10, 5, "13"));

        RegionHeaderContext context = analyzer.analyze(cells,
                new RegionHeaderAnalyzer.Bounds(8, 10, 2, 5));

        assertThat(context.headerRows()).containsExactly(8, 9);
        assertThat(context.periodAxisByColumn()).containsExactly(
                java.util.Map.entry("D", 1), java.util.Map.entry("E", 2));
        assertThat(context.columnLabelsByColumn())
                .containsEntry(4, "FY 2024-25 / Q1")
                .containsEntry(5, "FY 2024-25 / Q2");
    }

    @Test
    void doesNotInferHeadersOrLabelsAcrossRegionBoundaries() {
        List<NormalizedCell> cells = List.of(
                text("A1", 1, 1, "Year 1"),
                text("B3", 3, 2, "Revenue"),
                number("D3", 3, 4, "100"));

        RegionHeaderContext context = analyzer.analyze(cells,
                new RegionHeaderAnalyzer.Bounds(3, 3, 2, 4));

        assertThat(context.headerRows()).isEmpty();
        assertThat(context.periodAxisByColumn()).isEmpty();
        assertThat(context.rowLabelsByRow()).containsEntry(3, "Revenue");
        assertThat(context.columnLabelsByColumn()).isEmpty();
    }

    @Test
    void projectsMergedPeriodHeadersAcrossEveryCoveredColumn() {
        List<NormalizedCell> cells = List.of(
                mergedText("D4", 4, 4, "FY 2025", "D4:F4", true),
                mergedText("E4", 4, 5, "FY 2025", "D4:F4", false),
                mergedText("F4", 4, 6, "FY 2025", "D4:F4", false),
                text("D5", 5, 4, "Revenue"));

        RegionHeaderContext context = analyzer.analyze(cells,
                new RegionHeaderAnalyzer.Bounds(4, 5, 4, 6));

        assertThat(context.periodAxisByColumn()).containsExactly(
                java.util.Map.entry("D", 1), java.util.Map.entry("E", 2), java.util.Map.entry("F", 3));
        assertThat(context.columnLabelsByColumn()).containsExactly(
                java.util.Map.entry(4, "FY 2025"), java.util.Map.entry(5, "FY 2025"),
                java.util.Map.entry(6, "FY 2025"));
    }

    @Test
    void clipsMergedHeaderProjectionToTheRegionColumns() {
        List<NormalizedCell> cells = List.of(
                mergedText("D4", 4, 4, "FY 2025", "D4:H4", true),
                mergedText("E4", 4, 5, "FY 2025", "D4:H4", false),
                mergedText("F4", 4, 6, "FY 2025", "D4:H4", false),
                mergedText("G4", 4, 7, "FY 2025", "D4:H4", false),
                mergedText("H4", 4, 8, "FY 2025", "D4:H4", false));

        RegionHeaderContext context = analyzer.analyze(cells,
                new RegionHeaderAnalyzer.Bounds(4, 4, 6, 7));

        assertThat(context.periodAxisByColumn()).containsExactly(
                java.util.Map.entry("F", 1), java.util.Map.entry("G", 2));
        assertThat(context.columnLabelsByColumn()).containsExactly(
                java.util.Map.entry(6, "FY 2025"), java.util.Map.entry(7, "FY 2025"));
    }

    @Test
    void joinsSectionStubWithRowDescription() {
        List<NormalizedCell> cells = List.of(
                text("A15", 15, 1, "(A)"),
                text("B15", 15, 2, "Grand Total Cost of Civil Works"),
                number("E15", 15, 5, "242353576.58"),
                text("F15", 15, 6, "see note"));

        RegionHeaderContext context = analyzer.analyze(cells,
                new RegionHeaderAnalyzer.Bounds(15, 15, 1, 6));

        assertThat(context.rowLabelsByRow())
                .containsEntry(15, "(A) Grand Total Cost of Civil Works");
    }

    @Test
    void skipsNumericSerialsWhenLabellingARow() {
        List<NormalizedCell> cells = List.of(
                number("A3", 3, 1, "1.0"),
                text("B3", 3, 2, "Basement Floor"),
                number("E3", 3, 5, "63763632.66"));

        RegionHeaderContext context = analyzer.analyze(cells,
                new RegionHeaderAnalyzer.Bounds(3, 3, 1, 5));

        assertThat(context.rowLabelsByRow()).containsEntry(3, "Basement Floor");
    }

    @Test
    void doesNotFoldAmountRowsIntoColumnLabels() {
        List<NormalizedCell> cells = List.of(
                text("A1", 1, 1, "CIVIL Works"),
                text("A2", 2, 1, "Sl.No"),
                text("B2", 2, 2, "Floor"),
                text("C2", 2, 3, "Area (In sqm.)"),
                text("D2", 2, 4, "Rate Rs/Sqm"),
                text("E2", 2, 5, "Amount in Rs"),
                number("A3", 3, 1, "1.0"),
                text("B3", 3, 2, "Basement Floor"),
                number("C3", 3, 3, "3110.0"),
                number("D3", 3, 4, "20502.775774919613"),
                number("E3", 3, 5, "63763632.66"),
                number("A4", 4, 1, "2.0"),
                text("B4", 4, 2, "Ground Floor"),
                number("C4", 4, 3, "3014.0"),
                number("D4", 4, 4, "12200.774163901791"),
                number("E4", 4, 5, "36773133.33"),
                text("B13", 13, 2, "Total"),
                number("C13", 13, 3, "17571.71"),
                number("E13", 13, 5, "242353576.58"),
                text("A15", 15, 1, "(A)"),
                text("B15", 15, 2, "Grand Total Cost of Civil Works"),
                number("E15", 15, 5, "242353576.58"));

        RegionHeaderContext context = analyzer.analyze(cells,
                new RegionHeaderAnalyzer.Bounds(1, 15, 1, 5));

        assertThat(context.headerRows()).containsExactly(1, 2);
        assertThat(context.columnLabelsByColumn())
                .containsEntry(1, "CIVIL Works / Sl.No")
                .containsEntry(2, "Floor")
                .containsEntry(3, "Area (In sqm.)")
                .containsEntry(4, "Rate Rs/Sqm")
                .containsEntry(5, "Amount in Rs");
        assertThat(context.rowLabelsByRow())
                .containsEntry(3, "Basement Floor")
                .containsEntry(13, "Total")
                .containsEntry(15, "(A) Grand Total Cost of Civil Works");
    }

    @Test
    void recognizesOrdinaryTableHeadersWithoutPeriodLabels() {
        List<NormalizedCell> cells = List.of(
                text("A1", 1, 1, "Particulars"),
                text("B1", 1, 2, "Amount"),
                text("A2", 2, 1, "Civil works"),
                number("B2", 2, 2, "100"));

        RegionHeaderContext context = analyzer.analyze(cells,
                new RegionHeaderAnalyzer.Bounds(1, 2, 1, 2));

        assertThat(context.headerRows()).containsExactly(1);
        assertThat(context.columnLabelsByColumn()).containsExactly(
                java.util.Map.entry(1, "Particulars"), java.util.Map.entry(2, "Amount"));
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

    private static NormalizedCell mergedText(String coord, int row, int col, String value,
            String mergedRange, boolean anchor) {
        return new NormalizedCell(coord, row, col, value, "text", "text", value, value,
                null, null, null, null, null, null, null, null, false, null, false, null,
                null, null, anchor, !anchor, mergedRange, "merged_anchor", false, false, false);
    }
}
