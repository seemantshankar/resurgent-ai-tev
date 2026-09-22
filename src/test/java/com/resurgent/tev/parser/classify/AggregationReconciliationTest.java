package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.CellReferenceEdge;
import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit: {@link AggregationReconciliation}. The check is the arithmetic backstop for
 * scale typing: a group whose tagged values cannot add up is proof that at least one
 * assigned scale is wrong.
 */
class AggregationReconciliationTest {

    private static final long SHEET = 9L;

    private final List<InterpretationCellView> cells = new ArrayList<>();
    private final List<CellReferenceEdge> edges = new ArrayList<>();
    private long nextCellId = 1L;

    private void label(String coord, int row, int col, String text) {
        long id = nextCellId++;
        cells.add(view(id, coord, row, col, "string", text, null, text, null));
    }

    private long literal(String coord, int row, int col, String value) {
        long id = nextCellId++;
        cells.add(view(id, coord, row, col, "number", null, value, value, null));
        return id;
    }

    private long formula(String coord, int row, int col, String formulaText, String cached) {
        long id = nextCellId++;
        cells.add(view(id, coord, row, col, "number", null, cached, cached, formulaText));
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
            String formulaText) {
        return new InterpretationCellView(
                cellId, SHEET, coord, row, col, valueType, textValue, displayValue,
                numericValue, null, null, formulaText, formulaText == null ? null : "ok",
                numericValue, numericValue == null ? null : "cached", false, null,
                false, false, null, "cell");
    }

    private void edge(long fromCellId, int tokenIndex, String token) {
        edges.add(new CellReferenceEdge(
                fromCellId, tokenIndex, token, "cell", null, null, token, null, null,
                false, false, null, null, false, false, null));
    }

    @Test
    void aGroupWhoseNumbersDoNotAddUpIsReported() {
        label("A1", 1, 1, "Amount in Rs");
        long first = literal("B1", 1, 2, "10");
        label("A2", 2, 1, "Amount in Rs");
        long second = literal("B2", 2, 2, "20");
        // The cached head claims 999 where its members add to 30: no scale assignment
        // can make those agree, so the group is reported rather than trusted.
        long head = formula("B3", 3, 2, "=B1+B2", "999");
        edge(head, 0, "B1");
        edge(head, 1, "B2");

        CellGraph graph = new CellGraphBuilder().build(1L, cells, edges);
        CellTypes types = new TypePropagation().resolve(graph);

        AggregationReconciliation.Report report = AggregationReconciliation.check(graph, types);

        assertThat(report.checked()).isEqualTo(1);
        assertThat(report.mismatches()).hasSize(1);
        assertThat(report.mismatches().get(0).headCellId()).isEqualTo(head);
        assertThat(report.mismatches().get(0).headNormalized()).isEqualTo(999d);
        assertThat(report.mismatches().get(0).membersNormalized()).isEqualTo(30d);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
    }

    @Test
    void aGroupWhoseNumbersAddUpIsNotReported() {
        label("A1", 1, 1, "Amount in Rs");
        long first = literal("B1", 1, 2, "1000000");
        label("A2", 2, 1, "Amount in Rs");
        long second = literal("B2", 2, 2, "2000000");
        long head = formula("B3", 3, 2, "=B1+B2", "3000000");
        edge(head, 0, "B1");
        edge(head, 1, "B2");

        CellGraph graph = new CellGraphBuilder().build(1L, cells, edges);
        CellTypes types = new TypePropagation().resolve(graph);

        AggregationReconciliation.Report report = AggregationReconciliation.check(graph, types);

        assertThat(report.reconciled()).isTrue();
        assertThat(head).isNotNull();
    }
}
