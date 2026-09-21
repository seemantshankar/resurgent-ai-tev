package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.CellReferenceEdge;
import com.resurgent.tev.parser.db.InterpretationCellView;
import com.resurgent.tev.parser.nomenclature.IndustryResolution;
import com.resurgent.tev.parser.nomenclature.NomenclatureNode;
import com.resurgent.tev.parser.nomenclature.OntologySlice;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit: {@link DeterministicBinder}. Nothing is written unless the graph proves the
 * role and the label names exactly one hard catalog leaf.
 */
class DeterministicBinderTest {

    private static final long SHEET = 9L;
    private static final long CANDIDATE = 40L;
    private static final String COSTS = "Project Cost > Operating Costs";
    private static final String TOTAL_LEAF = "Project Cost > Operating Costs > Total Operating Cost";
    private static final String INSURANCE = "Project Cost > Operating Costs > Insurance Premium";

    private final List<InterpretationCellView> cells = new ArrayList<>();
    private final List<CellReferenceEdge> edges = new ArrayList<>();
    private long nextCellId = 1L;

    private long literal(String coord, int row, int col, String value) {
        long id = nextCellId++;
        cells.add(view(id, coord, row, col, "number", null, value, value, null, value));
        return id;
    }

    private long literal(long sheet, String coord, int row, int col, String value) {
        long id = nextCellId++;
        cells.add(new InterpretationCellView(
                id, sheet, coord, row, col, "number", null, value, value, null, null,
                null, null, value, "cached", false, null, false, false, null, "cell"));
        return id;
    }

    private long label(long sheet, String coord, int row, int col, String text) {
        long id = nextCellId++;
        cells.add(new InterpretationCellView(
                id, sheet, coord, row, col, "string", text, text, null, null, null,
                null, null, null, null, false, null, false, false, null, "cell"));
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
            long cellId, String coord, int row, int col, String valueType, String textValue,
            String numericValue, String displayValue, String formulaText, String cachedValue) {
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

    private static OntologySlice slice() {
        return new OntologySlice(
                IndustryResolution.confirmed("hotel"),
                List.of(
                        new NomenclatureNode("Project Cost", "Project Cost", null,
                                NomenclatureNode.LAYER_SPINE, true, false, null, null),
                        new NomenclatureNode(COSTS, "Operating Costs", "Project Cost",
                                NomenclatureNode.LAYER_SPINE, true, false, null, null),
                        new NomenclatureNode(TOTAL_LEAF, "Total Operating Cost", COSTS,
                                NomenclatureNode.LAYER_INDUSTRY, false, true, "hotel", null),
                        new NomenclatureNode(INSURANCE, "Insurance Premium", COSTS,
                                NomenclatureNode.LAYER_INDUSTRY, false, true, "hotel", null),
                        new NomenclatureNode(COSTS + " > Soft Line", "Soft Line", COSTS,
                                NomenclatureNode.LAYER_MANDATE_SOFT, false, true, null, 1L)),
                List.of());
    }

    private DeterministicBinder.Result bind() {
        CellGraph graph = new CellGraphBuilder().build(1L, cells, edges);
        CellTypes types = new TypePropagation().resolve(graph);
        Map<Long, Long> candidateByCell = new HashMap<>();
        for (InterpretationCellView cell : cells) {
            candidateByCell.put(cell.cellId(), CANDIDATE);
        }
        return new DeterministicBinder().bind(
                1L, graph, types, slice(), candidateByCell, Set.of());
    }

    @Test
    void anAggregationHeadBindsAsTotalAndPointsAtItsGroup() {
        label("A2", 2, 1, "Insurance Premium");
        literal("B2", 2, 2, "100");
        label("A3", 3, 1, "Insurance Premium");
        literal("B3", 3, 2, "200");
        label("A4", 4, 1, "Total Operating Cost");
        long head = formula("B4", 4, 2, "=SUM(B2:B3)", "300");
        edge(head, 0, "B2:B3");

        DeterministicBinder.Result result = bind();

        assertThat(result.bindings())
                .filteredOn(binding -> binding.cellId() == head)
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.path()).isEqualTo(TOTAL_LEAF);
                    assertThat(binding.amountRole()).isEqualTo(AmountRole.TOTAL);
                    assertThat(binding.source()).isEqualTo(BindingSource.AGGREGATION_HEAD);
                    assertThat(result.headCellByCell()).containsEntry(head, head);
                    assertThat(binding.labelKey())
                            .isEqualTo("9|total operating cost > total operating cost");
                });
    }

    @Test
    void aSubtractedMemberTakesDeductFromTheOperator() {
        label("A2", 2, 1, "Total Operating Cost");
        long gross = literal("B2", 2, 2, "300");
        label("A3", 3, 1, "Insurance Premium");
        long recovery = literal("B3", 3, 2, "50");
        label("A4", 4, 1, "Total Operating Cost");
        long net = formula("B4", 4, 2, "=B2-B3", "250");
        edge(net, 0, "B2");
        edge(net, 1, "B3");

        DeterministicBinder.Result result = bind();

        assertThat(result.bindings())
                .filteredOn(binding -> binding.cellId() == recovery)
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.path()).isEqualTo(INSURANCE);
                    assertThat(binding.amountRole()).isEqualTo(AmountRole.DEDUCT);
                });
        assertThat(result.bindings())
                .filteredOn(binding -> binding.cellId() == gross)
                .singleElement()
                .satisfies(binding -> assertThat(binding.amountRole()).isEqualTo(AmountRole.ADD));
    }

    @Test
    void aCellMinusInItsOwnRowNetKeepsDeductEvenWhenAColumnTotalAddsIt() {
        // ASSETS rows 54/60: I54 is the gross, J54 the deduction, K54 = I54-J54 the
        // row net; J62 = J52+J54 is a column total over other rows that also adds J54.
        // The row's own rollup owns the role, so J54 stays a deduction.
        label("A54", 54, 1, "Insurance Premium");
        long gross = literal("I54", 54, 9, "120.3");
        long deduction = literal("J54", 54, 10, "120.3");
        long rowNet = formula("K54", 54, 11, "=I54-J54", "0");
        edge(rowNet, 0, "I54");
        edge(rowNet, 1, "J54");
        label("A62", 62, 1, "Total Operating Cost");
        long other = literal("J52", 52, 10, "10");
        long columnTotal = formula("J62", 62, 10, "=J52+J54", "130.3");
        edge(columnTotal, 0, "J52");
        edge(columnTotal, 1, "J54");

        DeterministicBinder.Result result = bind();

        assertThat(result.bindings())
                .filteredOn(binding -> binding.cellId() == deduction)
                .singleElement()
                .satisfies(binding -> assertThat(binding.amountRole())
                        .as("a cross-row column total does not overwrite the row's operator")
                        .isEqualTo(AmountRole.DEDUCT));
        assertThat(result.bindings())
                .filteredOn(binding -> binding.cellId() == gross)
                .singleElement()
                .satisfies(binding -> assertThat(binding.amountRole()).isEqualTo(AmountRole.ADD));
        assertThat(other).isPositive();
    }

    @Test
    void aSoftLeafIsNotAHardCatalogLeafSoTheLabelIsQueuedInstead() {
        label("A2", 2, 1, "Soft Line");
        long soft = literal("B2", 2, 2, "100");

        DeterministicBinder.Result result = bind();

        assertThat(result.bindings()).noneMatch(binding -> binding.cellId() == soft);
        assertThat(result.queued())
                .extracting(DeterministicBinder.QueuedGroup::cellId)
                .contains(soft);
        assertThat(result.unboundReasons()).containsEntry(soft, UnboundReason.LLM_UNAVAILABLE);
    }

    @Test
    void theSameLabelOnTwoWorksheetsQueuesTwoWorksheetScopedQuestions() {
        label("A2", 2, 1, "Soft Line");
        long caseOne = literal(9L, "B2", 2, 2, "100");
        label(10L, "A2", 2, 1, "Soft Line");
        long caseTwo = literal(10L, "B2", 2, 2, "100");

        DeterministicBinder.Result result = bind();

        assertThat(result.queued())
                .extracting(DeterministicBinder.QueuedGroup::label)
                .satisfies(labels -> {
                    assertThat(labels).hasSize(2);
                    assertThat(labels.stream().map(QualifiedLabel::key).distinct().count())
                            .as("one question per label per worksheet")
                            .isEqualTo(2);
                });
        assertThat(result.queued())
                .extracting(DeterministicBinder.QueuedGroup::cellId)
                .containsExactlyInAnyOrder(caseOne, caseTwo);
    }

    @Test
    void aDriverIsNeverBoundAndCarriesDriverOnly() {
        label("A2", 2, 1, "Insurance Premium");
        long rate = literal("B2", 2, 2, "0.8");
        label("A3", 3, 1, "Insurance Premium");
        long base = literal("B3", 3, 2, "1000");
        long product = formula("B4", 4, 2, "=B2*B3", "800");
        edge(product, 0, "B2");
        edge(product, 1, "B3");

        DeterministicBinder.Result result = bind();

        assertThat(result.bindings()).noneMatch(binding -> binding.cellId() == rate);
        assertThat(result.unboundReasons()).containsEntry(rate, UnboundReason.DRIVER_ONLY);
        assertThat(result.unboundReasons()).containsEntry(base, UnboundReason.DRIVER_ONLY);
    }

    @Test
    void aCellAlreadyBoundByTheModelIsLeftAlone() {
        label("A2", 2, 1, "Insurance Premium");
        long premium = literal("B2", 2, 2, "100");

        CellGraph graph = new CellGraphBuilder().build(1L, cells, edges);
        CellTypes types = new TypePropagation().resolve(graph);
        Map<Long, Long> candidateByCell = new HashMap<>();
        candidateByCell.put(premium, CANDIDATE);

        DeterministicBinder.Result result = new DeterministicBinder().bind(
                1L, graph, types, slice(), candidateByCell, Set.of(premium));

        assertThat(result.bindings()).isEmpty();
        assertThat(result.unboundReasons()).doesNotContainKey(premium);
    }

    @Test
    void aMemberOfANonMoneyGroupNeverTakesACostRole() {
        label("A2", 2, 1, "No. of Guests");
        long guests = literal("B2", 2, 2, "380");
        label("A3", 3, 1, "No. of Rooms");
        long rooms = literal("B3", 3, 2, "40");
        label("A4", 4, 1, "Total Operating Cost");
        long head = formula("B4", 4, 2, "=SUM(B2:B3)", "420");
        edge(head, 0, "B2:B3");

        DeterministicBinder.Result result = bind();

        assertThat(result.bindings()).isEmpty();
        for (long cellId : List.of(guests, rooms, head)) {
            assertThat(result.unboundReasons().get(cellId))
                    .isEqualTo(UnboundReason.NON_MONEY_GROUP);
        }
    }
}
