package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.CellReferenceEdge;
import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit: {@link TypePropagation}. Inputs are typed from their own labels and the
 * formulas carry that forward; anything the arithmetic cannot settle is refused
 * with a reason rather than guessed.
 */
class TypePropagationTest {

    private static final long SHEET = 9L;

    private final List<InterpretationCellView> cells = new ArrayList<>();
    private final List<CellReferenceEdge> edges = new ArrayList<>();
    private long nextCellId = 1L;

    private long literal(String coord, int row, int col, String value) {
        long id = nextCellId++;
        cells.add(view(id, coord, row, col, "number", null, value, value, null, value));
        return id;
    }

    private long label(String coord, int row, int col, String text) {
        long id = nextCellId++;
        cells.add(view(id, coord, row, col, "string", text, null, text, null, null));
        return id;
    }

    private long formula(String coord, int row, int col, String formulaText, String cached) {
        long id = nextCellId++;
        cells.add(view(id, coord, row, col, "number", null, cached, cached, formulaText, cached));
        return id;
    }

    private static InterpretationCellView view(
            long cellId,
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
                cellId, SHEET, coord, row, col, valueType, textValue, displayValue,
                numericValue, null, null, formulaText, formulaText == null ? null : "ok",
                cachedValue, cachedValue == null ? null : "cached", false, null,
                false, false, null, "cell");
    }

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

    private CellTypes resolve() {
        return new TypePropagation().resolve(new CellGraphBuilder().build(1L, cells, edges));
    }

    @Test
    void quantityTimesRateTypesAsMoney() {
        label("A1", 1, 1, "No. of Rooms");
        long rooms = literal("B1", 1, 2, "40");
        label("A2", 2, 1, "Room Rate");
        long tariff = literal("B2", 2, 2, "5000");
        long revenue = formula("B3", 3, 2, "=B1*B2", "200000");
        edge(revenue, 0, "B1");
        edge(revenue, 1, "B2");

        CellTypes types = resolve();

        assertThat(types.unitOf(rooms).orElseThrow().kind()).isEqualTo(CellKind.QUANTITY);
        assertThat(types.unitOf(tariff).orElseThrow().kind()).isEqualTo(CellKind.RATE);
        assertThat(types.unitOf(revenue).orElseThrow().kind()).isEqualTo(CellKind.MONEY);
        assertThat(types.isMoney(revenue)).isTrue();
    }

    @Test
    void moneyOverMoneyTypesAsARatio() {
        label("A1", 1, 1, "Total Cost");
        literal("B1", 1, 2, "100");
        label("A2", 2, 1, "Project Cost");
        literal("B2", 2, 2, "400");
        long ratio = formula("B3", 3, 2, "=B1/B2", "0.25");
        edge(ratio, 0, "B1");
        edge(ratio, 1, "B2");

        CellTypes types = resolve();

        assertThat(types.unitOf(ratio).orElseThrow().kind()).isEqualTo(CellKind.RATIO);
        assertThat(types.isMoney(ratio)).isFalse();
    }

    @Test
    void aSumOfMismatchedKindsRefusesWithKindConflict() {
        label("A1", 1, 1, "Construction cost");
        literal("B1", 1, 2, "100");
        label("A2", 2, 1, "No. of Rooms");
        literal("B2", 2, 2, "40");
        long mixed = formula("B3", 3, 2, "=B1+B2", "140");
        edge(mixed, 0, "B1");
        edge(mixed, 1, "B2");

        CellTypes types = resolve();

        assertThat(types.unitOf(mixed)).isEmpty();
        assertThat(types.refusalOf(mixed)).contains(UnboundReason.KIND_CONFLICT);
    }

    @Test
    void aSumOfTheSameKindAtDifferentScalesRefusesWithScaleConflict() {
        label("A1", 1, 1, "Power cost (Rs)");
        literal("B1", 1, 2, "100000");
        label("A2", 2, 1, "Fuel cost (Rs. in Lakhs)");
        literal("B2", 2, 2, "2");
        long mixed = formula("B3", 3, 2, "=B1+B2", "100002");
        edge(mixed, 0, "B1");
        edge(mixed, 1, "B2");

        CellTypes types = resolve();

        assertThat(types.refusalOf(mixed)).contains(UnboundReason.SCALE_CONFLICT);
    }

    @Test
    void aSeriesTypesEachCellByItsOwnDisplayBeforeFallingBackToTheSeries() {
        // Row 45 of the P&L: the driver column holds a premium rate (percent display),
        // the amount columns hold money, all under one row label.
        label("A45", 45, 1, "Building");
        long rate = literalWithDisplay("B45", 45, 2, "0.05", "5.00%");
        long yearSix = literalWithDisplay("I45", 45, 9, "9.50", "9.50");
        long yearSeven = literalWithDisplay("J45", 45, 10, "9.50", "9.50");

        CellTypes types = resolve();

        assertThat(types.unitOf(rate).orElseThrow().kind()).isEqualTo(CellKind.PERCENT);
        assertThat(types.unitOf(yearSix).orElseThrow().kind())
                .as("the amount columns type from their own display, not the rate column")
                .isEqualTo(CellKind.MONEY);
        assertThat(types.unitOf(yearSeven).orElseThrow().kind()).isEqualTo(CellKind.MONEY);
    }

    @Test
    void aSeriesOfIdenticalCellsKeepsOneTypingDecision() {
        label("A2", 2, 1, "Insurance Premium");
        long yearOne = literal("B2", 2, 2, "100");
        long yearTwo = literal("C2", 2, 3, "110");

        CellTypes types = resolve();

        assertThat(types.unitOf(yearOne).orElseThrow().kind()).isEqualTo(CellKind.MONEY);
        assertThat(types.unitOf(yearTwo).orElseThrow().kind()).isEqualTo(CellKind.MONEY);
    }

    @Test
    void rateTimesMoneyTypesAsMoney() {
        label("A31", 31, 1, "Opening Balance (WDV)");
        long opening = literal("B31", 31, 2, "3178.28");
        label("A33", 33, 1, "Less: Depreciation @ 10 %");
        long depreciation = formula("J33", 33, 10, "=0.1*B31", "317.83");
        edge(depreciation, 0, "B31");

        CellTypes types = resolve();

        assertThat(types.unitOf(depreciation).orElseThrow().kind())
                .as("rate * money must type as money")
                .isEqualTo(CellKind.MONEY);
    }

    private long literalWithDisplay(
            String coord, int row, int col, String numeric, String display) {
        long id = nextCellId++;
        cells.add(view(id, coord, row, col, "number", null, numeric, display, null, numeric));
        return id;
    }

    @Test
    void dividingMoneyByLakhMovesTheScaleRatherThanTheKind() {
        label("A1", 1, 1, "Power cost");
        long rupees = literal("B1", 1, 2, "500000");
        long lakhs = formula("B2", 2, 2, "=B1/100000", "5");
        edge(lakhs, 0, "B1");

        CellTypes types = resolve();

        assertThat(types.unitOf(rupees).orElseThrow().scale()).isEqualTo(CellScale.UNIT);
        ResolvedUnit unit = types.unitOf(lakhs).orElseThrow();
        assertThat(unit.kind()).isEqualTo(CellKind.MONEY);
        assertThat(unit.scale()).isEqualTo(CellScale.LAKH);
    }

    @Test
    void aConstantFactorMovesTheScaleRegardlessOfOperandOrder() {
        label("A1", 1, 1, "Power cost");
        long rupees = literal("B1", 1, 2, "500000");
        long constantFirst = formula("C1", 1, 3, "=0.00001*B1", "5");
        edge(constantFirst, 0, "B1");
        long constantLast = formula("D1", 1, 4, "=B1*0.00001", "5");
        edge(constantLast, 0, "B1");

        CellTypes types = resolve();

        assertThat(types.unitOf(rupees).orElseThrow().scale()).isEqualTo(CellScale.UNIT);
        assertThat(types.unitOf(constantFirst).orElseThrow())
                .as("a leading constant factor must move the scale the same as a trailing one")
                .isEqualTo(types.unitOf(constantLast).orElseThrow());
        assertThat(types.unitOf(constantFirst).orElseThrow().scale()).isEqualTo(CellScale.LAKH);
    }

    @Test
    void anUnstatedLiteralAdoptsTheScaleItsSubtractingConsumerStates() {
        label("A1", 1, 1, "Estimate");
        long rupees = literal("B1", 1, 2, "12000000");
        long estimate = formula("C1", 1, 3, "=B1/100000", "120");
        edge(estimate, 0, "B1");
        label("A2", 2, 1, "Elevator (Supplier - Kone Elevator India Pvt. Ltd.)");
        long quotation = literal("D1", 1, 4, "120.3");
        long net = formula("E1", 1, 5, "=C1-D1", "0");
        edge(net, 0, "C1");
        edge(net, 1, "D1");

        CellTypes types = resolve();

        assertThat(types.unitOf(quotation).orElseThrow().scale())
                .as("the quotation is lakh, or the net could not be zero")
                .isEqualTo(CellScale.LAKH);
        assertThat(types.scaleProvenanceOf(quotation)).contains(ScaleProvenance.ADOPTED);
        assertThat(types.unitOf(net).orElseThrow().scale()).isEqualTo(CellScale.LAKH);
        assertThat(types.scaleProvenanceOf(net))
                .as("the net's own scale comes from the estimate's divisor, so it is stated")
                .contains(ScaleProvenance.STATED);
    }

    @Test
    void aProductDoesNotMoveItsScaleOntoItsOperands() {
        label("A1", 1, 1, "Qty");
        long quantity = literal("B1", 1, 2, "610");
        label("A2", 2, 1, "Rate");
        long rate = literal("B2", 2, 2, "33000");
        long amount = formula("B3", 3, 2, "=B1*B2/100000", "201.3");
        edge(amount, 0, "B1");
        edge(amount, 1, "B2");

        CellTypes types = resolve();

        assertThat(types.unitOf(amount).orElseThrow().scale()).isEqualTo(CellScale.LAKH);
        assertThat(types.unitOf(quantity).orElseThrow().scale())
                .as("a quantity times a rate proves nothing about the quantity's scale")
                .isEqualTo(CellScale.UNIT);
        assertThat(types.scaleProvenanceOf(quantity)).contains(ScaleProvenance.UNSTATED);
        assertThat(types.scaleProvenanceOf(rate)).contains(ScaleProvenance.UNSTATED);
    }

    @Test
    void anAdoptedScaleConvergesThroughAHeadToItsUnstatedMembers() {
        label("A1", 1, 1, "Quoted part one");
        long partOne = literal("B1", 1, 2, "10");
        label("A2", 2, 1, "Quoted part two");
        long partTwo = literal("B2", 2, 2, "20");
        long quotedTotal = formula("B3", 3, 2, "=B1+B2", "30");
        edge(quotedTotal, 0, "B1");
        edge(quotedTotal, 1, "B2");
        label("A4", 4, 1, "Estimate");
        long rupees = literal("B4", 4, 2, "5000000");
        long estimate = formula("B5", 5, 2, "=B4/100000", "50");
        edge(estimate, 0, "B4");
        long net = formula("B6", 6, 2, "=B5-B3", "20");
        edge(net, 0, "B5");
        edge(net, 1, "B3");

        CellTypes types = resolve();

        assertThat(types.scaleProvenanceOf(quotedTotal)).contains(ScaleProvenance.ADOPTED);
        assertThat(types.unitOf(partOne).orElseThrow().scale())
                .as("the scale converges from the total down to the members it sums")
                .isEqualTo(CellScale.LAKH);
        assertThat(types.unitOf(partTwo).orElseThrow().scale()).isEqualTo(CellScale.LAKH);
        assertThat(types.scaleProvenanceOf(partOne)).contains(ScaleProvenance.ADOPTED);
    }

    @Test
    void twoClaimedScalesStillConflictRatherThanAdopting() {
        label("A1", 1, 1, "Estimate");
        long rupees = literal("B1", 1, 2, "12000000");
        long lakhs = formula("B2", 2, 2, "=B1/100000", "120");
        edge(lakhs, 0, "B1");
        label("A3", 3, 1, "Amount in Rs");
        long rupeeLiteral = literal("B3", 3, 2, "5000");
        long net = formula("B4", 4, 2, "=B2-B3", "0");
        edge(net, 0, "B2");
        edge(net, 1, "B3");

        CellTypes types = resolve();

        assertThat(types.scaleProvenanceOf(rupeeLiteral))
                .as("a stated currency cue earns the unit scale")
                .contains(ScaleProvenance.STATED);
        assertThat(types.refusalOf(net))
                .as("a stated rupee amount against a stated lakh one is a conflict")
                .contains(UnboundReason.SCALE_CONFLICT);
    }

    @Test
    void aScaleWithNoAdditiveConsumerStaysUnstated() {
        label("A1", 1, 1, "F & B Sales");
        long bare = literal("B1", 1, 2, "216.664");

        CellTypes types = resolve();

        assertThat(types.unitOf(bare).orElseThrow().scale()).isEqualTo(CellScale.UNIT);
        assertThat(types.scaleProvenanceOf(bare))
                .as("nothing names a scale, so the unit default is not a claim")
                .contains(ScaleProvenance.UNSTATED);
    }

    @Test
    void aChainThroughAnExternalLinkRefusesWithExternalDependency() {
        long external = formula("B1", 1, 2, "=[1]Other!A1", "10");
        externalEdge(external, 0, "[1]Other!A1");
        long downstream = formula("B2", 2, 2, "=B1", "10");
        edge(downstream, 0, "B1");

        CellTypes types = resolve();

        assertThat(types.refusalOf(external)).contains(UnboundReason.EXTERNAL_DEPENDENCY);
        assertThat(types.refusalOf(downstream))
                .as("the refusal travels downstream instead of typing optimistically")
                .contains(UnboundReason.EXTERNAL_DEPENDENCY);
        assertThat(types.unitOf(downstream)).isEmpty();
    }

    @Test
    void aChainThroughABrokenReferenceRefusesWithBrokenDependency() {
        long broken = formula("B1", 1, 2, "=#REF!+1", "0");
        brokenEdge(broken, 0, "#REF!");
        long downstream = formula("B2", 2, 2, "=B1*2", "0");
        edge(downstream, 0, "B1");

        CellTypes types = resolve();

        assertThat(types.refusalOf(broken)).contains(UnboundReason.BROKEN_DEPENDENCY);
        assertThat(types.refusalOf(downstream)).contains(UnboundReason.BROKEN_DEPENDENCY);
    }

    @Test
    void aReferenceCycleTerminatesWithCycleAndDoesNotHang() {
        long first = formula("B1", 1, 2, "=B2", "0");
        long second = formula("B2", 2, 2, "=B1", "0");
        edge(first, 0, "B2");
        edge(second, 0, "B1");

        CellTypes types = resolve();

        assertThat(types.refusalOf(first)).contains(UnboundReason.CYCLE);
        assertThat(types.refusalOf(second)).contains(UnboundReason.CYCLE);
    }

    @Test
    void propagationRunsToAFixpointRatherThanAFixedNumberOfPasses() {
        label("A1", 1, 1, "Construction cost");
        literal("B1", 1, 2, "100");
        long previous = 2L;
        long last = 0L;
        for (int row = 2; row <= 120; row++) {
            String source = "B" + (row - 1);
            last = formula("B" + row, row, 2, "=" + source, "100");
            edge(last, 0, source);
            previous = last;
        }
        assertThat(previous).isEqualTo(last);

        CellTypes types = resolve();

        ResolvedUnit unit = types.unitOf(last).orElseThrow();
        assertThat(unit.kind()).isEqualTo(CellKind.MONEY);
        assertThat(types.typed().get(last).depth()).isEqualTo(119);
    }

    @Test
    void anAggregationResolvesItsUnitOnceFromItsMembers() {
        label("A1", 1, 1, "No. of Guests");
        literal("B1", 1, 2, "380");
        label("A2", 2, 1, "No. of Rooms");
        literal("B2", 2, 2, "40");
        long head = formula("B3", 3, 2, "=SUM(B1:B2)", "420");
        edge(head, 0, "B1:B2");

        CellGraph graph = new CellGraphBuilder().build(1L, cells, edges);
        CellTypes types = new TypePropagation().resolve(graph);

        Aggregation aggregation = graph.aggregationHeadedBy(head).orElseThrow();
        ResolvedUnit unit = types.unitOf(aggregation);
        assertThat(unit.kind())
                .as("a guest-count group is a quantity group, so no member is a cost")
                .isEqualTo(CellKind.QUANTITY);
        assertThat(unit.isMoney()).isFalse();
    }

    @Test
    void anAggregationWhoseMembersDisagreeIsRefusedNotAveraged() {
        label("A1", 1, 1, "Construction cost");
        literal("B1", 1, 2, "100");
        label("A2", 2, 1, "No. of Rooms");
        literal("B2", 2, 2, "40");
        long head = formula("B3", 3, 2, "=SUM(B1:B2)", "140");
        edge(head, 0, "B1:B2");

        CellGraph graph = new CellGraphBuilder().build(1L, cells, edges);
        CellTypes types = new TypePropagation().resolve(graph);

        ResolvedUnit unit = types.unitOf(graph.aggregationHeadedBy(head).orElseThrow());
        assertThat(unit.isResolved()).isFalse();
        assertThat(unit.refusal()).isEqualTo(UnboundReason.KIND_CONFLICT);
    }

    @Test
    void everyNumericCellEndsWithEitherAUnitOrAReason() {
        label("A1", 1, 1, "Construction cost");
        long cost = literal("B1", 1, 2, "100");
        long orphan = literal("Z50", 50, 26, "7");

        CellTypes types = resolve();

        for (long cellId : List.of(cost, orphan)) {
            boolean settled = types.unitOf(cellId).isPresent()
                    || types.refusalOf(cellId).isPresent();
            assertThat(settled).as("cell " + cellId + " is either typed or refused").isTrue();
        }
        assertThat(types.refusalOf(orphan)).contains(UnboundReason.NO_LABEL);
    }

    @Test
    void aRepeatedRowSeriesIsOneTypingDecision() {
        label("A2", 2, 1, "Room Rate");
        long yearOne = literal("B2", 2, 2, "5000");
        long yearTwo = literal("C2", 2, 3, "5500");
        long yearThree = literal("D2", 2, 4, "6000");

        CellGraph graph = new CellGraphBuilder().build(1L, cells, edges);
        assertThat(graph.inputs().stream().map(InputCell::seriesKey).distinct()).hasSize(1);

        CellTypes types = new TypePropagation().resolve(graph);

        assertThat(types.unitOf(yearOne).orElseThrow().kind()).isEqualTo(CellKind.RATE);
        assertThat(types.unitOf(yearTwo).orElseThrow()).isEqualTo(types.unitOf(yearOne).orElseThrow());
        assertThat(types.unitOf(yearThree).orElseThrow()).isEqualTo(types.unitOf(yearOne).orElseThrow());
    }

    @Test
    void aSeriesFallbackNeverAppliesWhenItsOwnMembersDisagree() {
        long percentCell = literal("B2", 2, 2, "0.1");
        long moneyCell = literal("B3", 3, 2, "500");
        long unlabelled = literal("B4", 4, 2, "9");
        Map<Long, String> numberFormats = Map.of(
                percentCell, "0%",
                moneyCell, "₹0.00");

        CellGraph graph = new CellGraphBuilder().build(1L, cells, edges, numberFormats);
        assertThat(graph.inputs().stream().map(InputCell::seriesKey).distinct())
                .as("blank-labelled literals on one sheet collapse to one series")
                .hasSize(1);

        CellTypes types = new TypePropagation().resolve(graph);

        assertThat(types.unitOf(percentCell).orElseThrow().kind()).isEqualTo(CellKind.PERCENT);
        assertThat(types.unitOf(moneyCell).orElseThrow().kind()).isEqualTo(CellKind.MONEY);
        assertThat(types.unitOf(unlabelled))
                .as("percent and money disagree, so the series has no safe fallback")
                .isEmpty();
        assertThat(types.refusalOf(unlabelled)).contains(UnboundReason.NO_LABEL);
    }

    @Test
    void aCellTypesFromAtLeastOneUsableInputWhenASiblingHasNoLabel() {
        label("A1", 1, 1, "Construction cost");
        long cost = literal("B1", 1, 2, "100");
        long orphan = literal("Z50", 50, 26, "7");
        long sum = formula("B2", 2, 2, "=B1+Z50", "107");
        edge(sum, 0, "B1");
        edge(sum, 1, "Z50");

        CellTypes types = resolve();

        assertThat(types.refusalOf(orphan)).contains(UnboundReason.NO_LABEL);
        assertThat(types.unitOf(sum).orElseThrow().kind()).isEqualTo(CellKind.MONEY);
    }

    @Test
    void twoRunsOverTheSameGraphProduceTheSameTypes() {
        label("A1", 1, 1, "Construction cost");
        literal("B1", 1, 2, "100");
        label("A2", 2, 1, "No. of Rooms");
        literal("B2", 2, 2, "40");
        label("A3", 3, 1, "Room Rate");
        literal("B3", 3, 2, "5000");
        long revenue = formula("B4", 4, 2, "=B2*B3", "200000");
        edge(revenue, 0, "B2");
        edge(revenue, 1, "B3");
        long mixed = formula("B5", 5, 2, "=B1+B2", "140");
        edge(mixed, 0, "B1");
        edge(mixed, 1, "B2");

        CellTypes first = resolve();
        CellTypes second = resolve();

        assertThat(second.typed()).isEqualTo(first.typed());
        assertThat(second.refusals()).isEqualTo(first.refusals());
        assertThat(second.rows(1L)).isEqualTo(first.rows(1L));
    }

    @Test
    void aProductIsNeverTypedFromASubsetWhileAnOperandIsStillPending() {
        label("A1", 1, 1, "Occupancy %");
        long occupancy = literalWithDisplay("B1", 1, 2, "0.4", "40.00%");
        label("A2", 2, 1, "Construction cost");
        long cost = literal("B2", 2, 2, "100");
        label("A3", 3, 1, "No. of Rooms");
        long rooms = literal("B3", 3, 2, "40");
        // The product is ordered before the divisor that will refuse, so a subset
        // guess in the first pass would freeze "percent" and never be revisited.
        long product = formula("B5", 5, 2, "=B1*B4", "0");
        edge(product, 0, "B1");
        edge(product, 1, "B4");
        long mixed = formula("B4", 4, 2, "=B2+B3", "140");
        edge(mixed, 0, "B2");
        edge(mixed, 1, "B3");

        CellTypes types = resolve();

        assertThat(types.refusalOf(cost)).isEmpty();
        assertThat(types.refusalOf(rooms)).isEmpty();
        assertThat(types.unitOf(occupancy).orElseThrow().kind()).isEqualTo(CellKind.PERCENT);
        assertThat(types.refusalOf(mixed)).contains(UnboundReason.KIND_CONFLICT);
        assertThat(types.unitOf(product))
                .as("a product must not type from the percent factor alone")
                .isEmpty();
        assertThat(types.refusalOf(product)).contains(UnboundReason.KIND_CONFLICT);
    }
}
