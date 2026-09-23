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

    /** A sheet-qualified edge, as ingest records a 'SHEET'!ref reference. */
    private void sheetEdge(
            long fromCellId,
            int tokenIndex,
            String token,
            String targetSheetName,
            long targetWorksheetId,
            String range,
            long resolvedCellId) {
        edges.add(new CellReferenceEdge(
                fromCellId, tokenIndex, token, "cell", targetSheetName, targetWorksheetId, range,
                resolvedCellId, null, false, false, null, null, false, false, null));
    }

    private long literalOn(long worksheetId, String coord, int row, int col, String value) {
        long id = nextCellId++;
        cells.add(cell(id, worksheetId, coord, row, col, "number", null, value, value, null, value));
        return id;
    }

    private void edgeTo(long fromCellId, int tokenIndex, String token, long resolvedCellId) {
        edges.add(new CellReferenceEdge(
                fromCellId, tokenIndex, token, "cell", null, null, token, resolvedCellId, null,
                false, false, null, null, false, false, null));
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
    void aRangeLargerThanTheScanCapIsRecordedAsABarrierNotPartialEvidence() {
        literal("A1", 1, 1, "10");
        literal("A300", 300, 1, "30");
        long head = formula("B1", 1, 2, "=SUM(A1:A300)", "9999");
        edge(head, 0, "A1:A300");

        CellGraph graph = build();

        assertThat(graph.dependenciesOf(head))
                .as("a range too large to scan in full must not pass as complete evidence")
                .singleElement()
                .satisfies(dependency -> {
                    assertThat(dependency.isBarrier()).isTrue();
                    assertThat(dependency.barrier()).isEqualTo(UnboundReason.RANGE_TRUNCATED);
                });
        assertThat(graph.aggregationHeadedBy(head)).isEmpty();
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
    void aFormulaSummingTheSameCellTwiceIsAPassThroughNotAnAggregation() {
        long revenue = literal("B2", 2, 2, "100");
        long head = formula("B3", 3, 2, "=B2+B2", "200");
        edge(head, 0, "B2");
        edge(head, 1, "B2");

        CellGraph graph = build();

        assertThat(graph.aggregationHeadedBy(head))
                .as("one distinct cell counted twice is a pass-through, not a group")
                .isEmpty();
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
    void aRateBetweenTwoTextsNamesTheRowFromTheFarText() {
        label("A33", 33, 1, "Line name");
        literal("B33", 33, 2, "0.4");
        label("C33", 33, 3, "Driver note");
        long year = formula("E33", 33, 5, "=B33*E23", "141");

        CellGraph graph = build();

        assertThat(graph.cell(year).orElseThrow().rowLabel()).isEqualTo("Line name");
    }

    @Test
    void anAmountAboveOneDoesNotSplitTheRowName() {
        label("A7", 7, 1, "Section");
        label("B7", 7, 2, "Line name");
        literal("C7", 7, 3, "12");
        long year = formula("E7", 7, 5, "=C7*12", "144");

        CellGraph graph = build();

        assertThat(graph.cell(year).orElseThrow().rowLabel()).isEqualTo("Line name");
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

    @Test
    void aSheetPrefixedReferenceInAMultiplicativeTermIsAFactor() {
        long otherSheet = 77L;
        long remote = literalOn(otherSheet, "D20", 20, 4, "100");
        long local = literal("B46", 46, 2, "0.005");
        long head = formula("D46", 46, 4, "=B46*'CAPITAL COST'!D20", "0.5");
        edgeTo(head, 0, "B46", local);
        sheetEdge(head, 1, "'CAPITAL COST'!D20", "CAPITAL COST", otherSheet, "D20", remote);

        CellGraph graph = build();

        assertThat(graph.dependenciesOf(head))
                .extracting(CellDependency::role)
                .containsExactly(DependencyRole.FACTOR, DependencyRole.FACTOR);
        assertThat(graph.aggregationHeadedBy(head)).isEmpty();
    }

    @Test
    void aSheetPrefixedReferenceOnEitherSideOfTimesIsAFactor() {
        long otherSheet = 77L;
        long remote = literalOn(otherSheet, "D20", 20, 4, "100");
        long local = literal("B46", 46, 2, "0.005");
        long head = formula("D46", 46, 4, "='CAPITAL COST'!D20*B46", "0.5");
        sheetEdge(head, 0, "'CAPITAL COST'!D20", "CAPITAL COST", otherSheet, "D20", remote);
        edgeTo(head, 1, "B46", local);

        CellGraph graph = build();

        assertThat(graph.dependenciesOf(head))
                .extracting(CellDependency::role)
                .containsExactly(DependencyRole.FACTOR, DependencyRole.FACTOR);
    }

    @Test
    void aSheetPrefixedReferenceAloneIsABareReference() {
        long otherSheet = 77L;
        long remote = literalOn(otherSheet, "D20", 20, 4, "100");
        long head = formula("D46", 46, 4, "='CAPITAL COST'!D20", "100");
        sheetEdge(head, 0, "'CAPITAL COST'!D20", "CAPITAL COST", otherSheet, "D20", remote);

        CellGraph graph = build();

        assertThat(graph.dependenciesOf(head))
                .extracting(CellDependency::role)
                .containsExactly(DependencyRole.SUMMAND_PLUS);
        assertThat(graph.aggregationHeadedBy(head)).isEmpty();
    }

    @Test
    void aSheetNameContainingASlashIsNotAnOperator() {
        long otherSheet = 78L;
        long remote = literalOn(otherSheet, "A1", 1, 1, "7");
        long head = formula("B1", 1, 2, "='P/L'!A1", "7");
        sheetEdge(head, 0, "'P/L'!A1", "P/L", otherSheet, "A1", remote);

        CellGraph graph = build();

        assertThat(graph.dependenciesOf(head))
                .extracting(CellDependency::role)
                .containsExactly(DependencyRole.SUMMAND_PLUS);
    }

    @Test
    void aPowerOfTenDivisorIsOneConstant() {
        long amount = literal("F21", 21, 6, "242353576.58");
        long head = formula("I9", 9, 9, "=F21/10^5", "2423.54");
        edgeTo(head, 0, "F21", amount);

        CellGraph graph = build();

        assertThat(graph.dependenciesOf(head))
                .filteredOn(CellDependency::isConstant)
                .extracting(CellDependency::constant, CellDependency::role)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple(
                                100000.0, DependencyRole.DIVISOR));
    }

    @Test
    void aPowerOfTenDivisorOverASumIsOneConstant() {
        long first = literal("F38", 38, 6, "10");
        long second = literal("F42", 42, 6, "20");
        long head = formula("I34", 34, 9, "=(F38+F42)/10^5", "0.0003");
        edgeTo(head, 0, "F38", first);
        edgeTo(head, 1, "F42", second);

        CellGraph graph = build();

        assertThat(graph.dependenciesOf(head))
                .filteredOn(CellDependency::isConstant)
                .extracting(CellDependency::constant)
                .containsExactly(100000.0);
    }

    @Test
    void repeatedEqualConstantsTakeRolesByPosition() {
        long amount = literal("A1", 1, 1, "100");
        long head = formula("B1", 1, 2, "=A1/2*2", "100");
        edgeTo(head, 0, "A1", amount);

        CellGraph graph = build();

        assertThat(graph.dependenciesOf(head))
                .filteredOn(CellDependency::isConstant)
                .extracting(CellDependency::constant, CellDependency::role)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple(2.0, DependencyRole.DIVISOR),
                        org.assertj.core.api.Assertions.tuple(2.0, DependencyRole.FACTOR));
    }

    @Test
    void aGrowthFactorKeepsBothTheConstantAndTheDependencyEdge() {
        long prior = literal("I75", 75, 9, "100");
        long growth = literal("D75", 75, 4, "0.05");
        long head = formula("J75", 75, 10, "=I75*(1+D75)", "105");
        edgeTo(head, 0, "I75", prior);
        edgeTo(head, 1, "D75", growth);

        CellGraph graph = build();

        assertThat(graph.dependenciesOf(head))
                .filteredOn(CellDependency::isConstant)
                .extracting(CellDependency::constant, CellDependency::role)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple(1.0, DependencyRole.FACTOR));
        assertThat(graph.dependenciesOf(head))
                .filteredOn(dependency -> !dependency.isConstant())
                .extracting(CellDependency::cellId, CellDependency::role)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple(prior, DependencyRole.FACTOR),
                        org.assertj.core.api.Assertions.tuple(growth, DependencyRole.FACTOR));
    }

    @Test
    void aSubtractedParenthesisedGroupDistributesItsSignOverEachInnerTerm() {
        long gross = literal("B2", 2, 2, "100");
        long tax = literal("B3", 3, 2, "30");
        long credit = literal("B4", 4, 2, "5");
        long head = formula("B5", 5, 2, "=B2-(B3-B4)", "75");
        edge(head, 0, "B2");
        edge(head, 1, "B3");
        edge(head, 2, "B4");

        CellGraph graph = build();

        assertThat(graph.aggregationHeadedBy(head).orElseThrow().members())
                .as("the minus in front of the group flips the sign of each term inside it")
                .extracting(Aggregation.Member::cellId, Aggregation.Member::amountRole)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple(gross, AmountRole.ADD),
                        org.assertj.core.api.Assertions.tuple(tax, AmountRole.DEDUCT),
                        org.assertj.core.api.Assertions.tuple(credit, AmountRole.ADD));
    }

    @Test
    void aCellSubtractedBackOutOfItsOwnSumNetsOutOfTheMembers() {
        long excluded = literal("B2", 2, 2, "10");
        long second = literal("B3", 3, 2, "20");
        long third = literal("B4", 4, 2, "30");
        long head = formula("B5", 5, 2, "=SUM(B2:B4)-B2", "50");
        edge(head, 0, "B2:B4");
        edge(head, 1, "B2");

        CellGraph graph = build();

        assertThat(graph.aggregationHeadedBy(head).orElseThrow().members())
                .as("a cell added and subtracted once contributes nothing to its head")
                .extracting(Aggregation.Member::cellId, Aggregation.Member::amountRole)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple(second, AmountRole.ADD),
                        org.assertj.core.api.Assertions.tuple(third, AmountRole.ADD));
        assertThat(graph.aggregationHeadedBy(head).orElseThrow().members())
                .extracting(Aggregation.Member::cellId)
                .doesNotContain(excluded);
    }
}
