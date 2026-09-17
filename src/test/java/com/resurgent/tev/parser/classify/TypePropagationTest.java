package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.CellReferenceEdge;
import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.ArrayList;
import java.util.List;
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
}
