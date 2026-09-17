package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.CellReferenceEdge;
import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit: {@link CellGraphBuilder}. Structure comes from the formula, never from how
 * the sheet looks, so every case here is stated as a formula plus the persisted
 * cells it can reach.
 */
class CellGraphBuilderTest {

    private static final long SHEET = 9L;

    private final List<InterpretationCellView> cells = new ArrayList<>();
    private final List<CellReferenceEdge> edges = new ArrayList<>();
    private long nextCellId = 1L;

    private long literal(String coord, int row, int col, String value) {
        long id = nextCellId++;
        cells.add(cell(id, SHEET, coord, row, col, "number", null, value, value, null, value));
        return id;
    }

    private long label(String coord, int row, int col, String text) {
        long id = nextCellId++;
        cells.add(cell(id, SHEET, coord, row, col, "string", text, null, text, null, null));
        return id;
    }

    private long formula(String coord, int row, int col, String formulaText, String cached) {
        long id = nextCellId++;
        cells.add(cell(id, SHEET, coord, row, col, "number", null, cached, cached, formulaText,
                cached));
        return id;
    }

    private static InterpretationCellView cell(
            long cellId,
            long worksheetId,
            String coord,
            int row,
            int col,
            String valueType,
            String textValue,
            String numericValue,
            String displayValue,
            String formulaText,
            String cachedValue) {
        return new InterpretationCellView(
                cellId, worksheetId, coord, row, col, valueType, textValue, displayValue,
                numericValue, null, null, formulaText, formulaText == null ? null : "ok",
                cachedValue, cachedValue == null ? null : "cached", false, null,
                false, false, null, "cell");
    }

    /** A local range/coord edge, which is how ingest records an unqualified reference. */
    private void edge(long fromCellId, int tokenIndex, String token) {
        edges.add(new CellReferenceEdge(
                fromCellId, tokenIndex, token, "cell", null, null, token, null, null,
                false, false, null, null, false, false, null));
    }

    private void externalEdge(long fromCellId, int tokenIndex, String token) {
        edges.add(new CellReferenceEdge(
                fromCellId, tokenIndex, token, "external", "[1]Other", null, token, null, 7L,
                false, false, null, null, false, false, null));
    }

    private void brokenEdge(long fromCellId, int tokenIndex, String token) {
        edges.add(new CellReferenceEdge(
                fromCellId, tokenIndex, token, "cell", null, null, null, null, null,
                false, false, null, null, false, false, "ref_error"));
    }

    private CellGraph build() {
        return new CellGraphBuilder().build(1L, cells, edges);
    }

    @Test
    void aConstantOnlyFormulaIsAnInputNotADerivedCell() {
        label("A1", 1, 1, "Power Factor");
        long constant = formula("B1", 1, 2, "=1500*0.8", "1200");
        label("A2", 2, 1, "Civil Works");
        long derived = formula("B2", 2, 2, "=B1", "1200");
        edge(derived, 0, "B1");

        CellGraph graph = build();

        assertThat(graph.inputs()).extracting(InputCell::cellId).contains(constant);
        assertThat(graph.inputs()).extracting(InputCell::cellId).doesNotContain(derived);
        assertThat(graph.inputs())
                .filteredOn(input -> input.cellId() == constant)
                .singleElement()
                .satisfies(input -> assertThat(input.rowLabel()).isEqualTo("Power Factor"));
    }

    @Test
    void aLocalRangeWithNoTargetWorksheetResolvesAgainstTheSourceSheet() {
        long first = literal("B2", 2, 2, "10");
        long second = literal("B3", 3, 2, "20");
        long head = formula("B4", 4, 2, "=SUM(B2:B3)", "30");
        edge(head, 0, "B2:B3");

        CellGraph graph = build();

        assertThat(graph.aggregationHeadedBy(head)).isPresent();
        assertThat(graph.aggregationHeadedBy(head).orElseThrow().members())
                .extracting(Aggregation.Member::cellId)
                .containsExactly(first, second);
    }

    @Test
    void sumOverARangeYieldsOneMemberPerPersistedCellAndInventsNoneForGaps() {
        long first = literal("B2", 2, 2, "10");
        // B3 is a gap: no persisted cell at that coordinate.
        long third = literal("B4", 4, 2, "30");
        long head = formula("B5", 5, 2, "=SUM(B2:B4)", "40");
        edge(head, 0, "B2:B4");

        CellGraph graph = build();

        assertThat(graph.aggregationHeadedBy(head).orElseThrow().members())
                .extracting(Aggregation.Member::cellId)
                .containsExactly(first, third);
    }

    @Test
    void aPlusMinusChainYieldsAddForSummandsAndDeductForSubtractedTerms() {
        long revenue = literal("B2", 2, 2, "100");
        long cost = literal("B3", 3, 2, "40");
        long head = formula("B4", 4, 2, "=B2-B3", "60");
        edge(head, 0, "B2");
        edge(head, 1, "B3");

        CellGraph graph = build();

        Aggregation aggregation = graph.aggregationHeadedBy(head).orElseThrow();
        assertThat(aggregation.members())
                .extracting(Aggregation.Member::cellId, Aggregation.Member::amountRole)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple(revenue, AmountRole.ADD),
                        org.assertj.core.api.Assertions.tuple(cost, AmountRole.DEDUCT));
    }

    @Test
    void anOperandOfTimesOrDivideIsADriverAndNeverASummand() {
        long tariff = literal("B2", 2, 2, "5000");
        long nights = literal("B3", 3, 2, "69350");
        long revenue = formula("B4", 4, 2, "=B2*B3", "346750000");
        edge(revenue, 0, "B2");
        edge(revenue, 1, "B3");

        CellGraph graph = build();

        assertThat(graph.aggregationHeadedBy(revenue)).isEmpty();
        assertThat(graph.isDriverOnly(tariff)).isTrue();
        assertThat(graph.isDriverOnly(nights)).isTrue();
        assertThat(graph.dependenciesOf(revenue))
                .extracting(CellDependency::role)
                .containsExactly(DependencyRole.FACTOR, DependencyRole.FACTOR);
    }

    @Test
    void theDenominatorOfADivisionIsRecordedAsADivisor() {
        literal("B2", 2, 2, "100000");
        literal("B3", 3, 2, "4");
        long perUnit = formula("B4", 4, 2, "=B2/B3", "25000");
        edge(perUnit, 0, "B2");
        edge(perUnit, 1, "B3");

        CellGraph graph = build();

        assertThat(graph.dependenciesOf(perUnit))
                .extracting(CellDependency::role)
                .containsExactly(DependencyRole.FACTOR, DependencyRole.DIVISOR);
    }

    @Test
    void aCellCanAddIntoOneAggregationAndBeDeductedInAnother() {
        long depreciation = formula("B2", 2, 2, "=0.1*D1", "100");
        long other = literal("B3", 3, 2, "50");
        long total = formula("B4", 4, 2, "=SUM(B2:B3)", "150");
        long netBlock = formula("B5", 5, 2, "=B3-B2", "-50");
        edge(total, 0, "B2:B3");
        edge(netBlock, 0, "B3");
        edge(netBlock, 1, "B2");

        CellGraph graph = build();

        assertThat(graph.membershipsOf(depreciation)).hasSize(2);
        assertThat(graph.membershipsOf(depreciation))
                .extracting(Aggregation::headCellId)
                .containsExactlyInAnyOrder(total, netBlock);
        assertThat(graph.membershipsOf(other)).hasSize(2);
    }

    @Test
    void aChainThroughAnExternalLinkOrABrokenReferenceIsRecordedAsABarrier() {
        long external = formula("B2", 2, 2, "=[1]Other!A1", "10");
        externalEdge(external, 0, "[1]Other!A1");
        long broken = formula("B3", 3, 2, "=#REF!+1", "0");
        brokenEdge(broken, 0, "#REF!");

        CellGraph graph = build();

        assertThat(graph.dependenciesOf(external))
                .singleElement()
                .satisfies(dependency -> {
                    assertThat(dependency.isBarrier()).isTrue();
                    assertThat(dependency.barrier())
                            .isEqualTo(UnboundReason.EXTERNAL_DEPENDENCY);
                });
        assertThat(graph.dependenciesOf(broken))
                .singleElement()
                .satisfies(dependency -> assertThat(dependency.barrier())
                        .isEqualTo(UnboundReason.BROKEN_DEPENDENCY));
    }

    @Test
    void aRepeatedRowSeriesCollapsesToOneInputDecision() {
        label("A2", 2, 1, "Occupancy");
        long yearOne = formula("B2", 2, 2, "=0.6*1.0", "0.6");
        long yearTwo = formula("C2", 2, 3, "=0.6*1.0", "0.6");
        long yearThree = formula("D2", 2, 4, "=0.6*1.0", "0.6");

        CellGraph graph = build();

        assertThat(graph.inputs()).extracting(InputCell::cellId)
                .contains(yearOne, yearTwo, yearThree);
        assertThat(graph.inputs()).extracting(InputCell::seriesKey)
                .as("three period columns of one row are one decision")
                .containsOnly(graph.inputs().get(0).seriesKey());
        assertThat(graph.inputs().stream().map(InputCell::seriesKey).distinct().toList())
                .hasSize(1);
        assertThat(graph.inputs().get(0).seriesKey()).contains("occupancy");
    }

    @Test
    void aPeriodSeriesOfIdenticalRelativeFormulasSharesOneSignature() {
        literal("B2", 2, 2, "10");
        literal("C2", 2, 3, "20");
        long yearOne = formula("B3", 3, 2, "=B2*2", "20");
        long yearTwo = formula("C3", 3, 3, "=C2*2", "40");
        edge(yearOne, 0, "B2");
        edge(yearTwo, 0, "C2");

        CellGraph graph = build();

        String first = CellGraphBuilder.relativeSignature(
                "=B2*2", graph.cell(yearOne).orElseThrow());
        String second = CellGraphBuilder.relativeSignature(
                "=C2*2", graph.cell(yearTwo).orElseThrow());
        assertThat(first).isEqualTo(second).isEqualTo("R[-1]C*2");
    }

    @Test
    void aReferenceCycleTerminatesAndDoesNotHang() {
        long first = formula("B2", 2, 2, "=B3", "0");
        long second = formula("B3", 3, 2, "=B2", "0");
        edge(first, 0, "B3");
        edge(second, 0, "B2");

        CellGraph graph = build();

        assertThat(graph.dependenciesOf(first))
                .extracting(CellDependency::cellId).containsExactly(second);
        assertThat(graph.dependenciesOf(second))
                .extracting(CellDependency::cellId).containsExactly(first);
    }

    @Test
    void theRowLabelIsTheNearestTextToTheLeftAndNeverACoordinate() {
        label("A45", 45, 1, "Insurance Premium");
        long premium = literal("J45", 45, 10, "125000");
        long unlabelled = literal("J90", 90, 10, "5");

        CellGraph graph = build();

        assertThat(graph.cell(premium).orElseThrow().rowLabel()).isEqualTo("Insurance Premium");
        assertThat(graph.cell(unlabelled).orElseThrow().rowLabel()).isNull();
    }

    @Test
    void anAggregationCarriesItsHeadLabelAndRelativeSignature() {
        label("A4", 4, 1, "Total Operating Cost");
        literal("B2", 2, 2, "10");
        literal("B3", 3, 2, "20");
        long head = formula("B4", 4, 2, "=SUM(B2:B3)", "30");
        edge(head, 0, "B2:B3");

        CellGraph graph = build();

        Aggregation aggregation = graph.aggregationHeadedBy(head).orElseThrow();
        assertThat(aggregation.headLabel()).isEqualTo("Total Operating Cost");
        assertThat(aggregation.relativeSignature()).isEqualTo("SUM(R[-2]C:R[-1]C)");
        assertThat(aggregation.worksheetId()).isEqualTo(SHEET);
    }
}
