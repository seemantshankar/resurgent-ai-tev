package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CellContextEnricher}: row and column header label inference.
 */
class CellContextEnricherTest {

    private final CellContextEnricher enricher = new CellContextEnricher();

    private NormalizedCell textCell(String coord, int row, int col, String value) {
        return new NormalizedCell(
                coord, row, col,
                value,
                "text", "text",
                value, value,
                null, null, null,
                null, null, null, null, null,
                false, null, false, null,
                null, null,
                false, false, null, "cell", false, false, false,
                null, null, null, null);
    }

    private NormalizedCell numberCell(String coord, int row, int col, String value) {
        return new NormalizedCell(
                coord, row, col,
                value,
                "number", "number",
                value, value,
                new BigDecimal(value), null, null,
                null, null, null, null, null,
                false, null, false, null,
                null, null,
                false, false, null, "cell", false, false, false,
                null, null, null, null);
    }

    private NormalizedCell formulaCell(String coord, int row, int col,
            String rawFormula, String formulaText, String cachedValue) {
        return new NormalizedCell(
                coord, row, col,
                rawFormula,
                "formula", "number",
                cachedValue, cachedValue,
                new BigDecimal(cachedValue), null, null,
                formulaText, formulaText, "ok", cachedValue, "fresh",
                false, null, false, null,
                null, null,
                false, false, null, "cell", false, false, false,
                null, null, null, null);
    }

    private Map<String, NormalizedCell> byCoord(List<NormalizedCell> cells) {
        return cells.stream().collect(Collectors.toMap(NormalizedCell::coord, Function.identity()));
    }

    @Test
    void plStyleGridLabelsRowsAndYearColumns() {
        List<NormalizedCell> cells = new ArrayList<>();
        // Row 8: year headers
        cells.add(textCell("D8", 8, 4, "Year1"));
        cells.add(textCell("E8", 8, 5, "Year2"));
        cells.add(textCell("F8", 8, 6, "Year3"));
        // Row 15: PBIT row with data
        cells.add(textCell("A15", 15, 1, "PBIT"));
        cells.add(numberCell("D15", 15, 4, "100"));
        cells.add(numberCell("E15", 15, 5, "200"));
        cells.add(formulaCell("F15", 15, 6, "=D15+E15", "D15+E15", "300"));

        List<NormalizedCell> enriched = enricher.enrich(cells);
        Map<String, NormalizedCell> byCoord = byCoord(enriched);

        assertThat(byCoord.get("D15").rowLabel()).isEqualTo("PBIT");
        assertThat(byCoord.get("D15").colLabel()).isEqualTo("Year1");
        assertThat(byCoord.get("E15").colLabel()).isEqualTo("Year2");
        assertThat(byCoord.get("F15").colLabel()).isEqualTo("Year3");
        assertThat(byCoord.get("F15").rowLabel()).isEqualTo("PBIT");
    }

    @Test
    void rowLabelFallsBackToFirstNonEmptyTextCell() {
        List<NormalizedCell> cells = new ArrayList<>();
        // Column A blank, label in B
        cells.add(textCell("B3", 3, 2, "Revenue"));
        cells.add(numberCell("C3", 3, 3, "99"));

        List<NormalizedCell> enriched = enricher.enrich(cells);

        assertThat(enriched.get(1).rowLabel()).isEqualTo("Revenue");
    }

    @Test
    void columnLabelFallsBackToTopmostTextWhenNoYearHeaders() {
        List<NormalizedCell> cells = new ArrayList<>();
        cells.add(textCell("A1", 1, 1, "Product"));
        cells.add(textCell("B1", 1, 2, "Sales"));
        cells.add(textCell("A2", 2, 1, "Widget"));
        cells.add(numberCell("B2", 2, 2, "42"));

        List<NormalizedCell> enriched = enricher.enrich(cells);
        Map<String, NormalizedCell> byCoord = byCoord(enriched);

        assertThat(byCoord.get("B2").colLabel()).isEqualTo("Sales");
        assertThat(byCoord.get("A2").colLabel()).isEqualTo("Product");
    }

    @Test
    void headerCellsThemselvesGetTheirOwnRowAndColumnLabels() {
        List<NormalizedCell> cells = new ArrayList<>();
        cells.add(textCell("A1", 1, 1, "Metric"));
        cells.add(textCell("B1", 1, 2, "Year1"));
        cells.add(textCell("A2", 2, 1, "Revenue"));
        cells.add(numberCell("B2", 2, 2, "10"));

        List<NormalizedCell> enriched = enricher.enrich(cells);
        Map<String, NormalizedCell> byCoord = byCoord(enriched);

        // B1 is a header cell; its row label is A1, its col label is itself (Year1)
        assertThat(byCoord.get("B1").rowLabel()).isEqualTo("Metric");
        assertThat(byCoord.get("B1").colLabel()).isEqualTo("Year1");
    }
}
