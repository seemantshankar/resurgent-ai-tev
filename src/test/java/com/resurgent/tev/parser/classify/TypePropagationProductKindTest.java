package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.CellReferenceEdge;
import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression for the poisoned revenue block. A product whose dimension rests on a
 * single typed operand while hardcoded literals carry the rest
 * ({@code (F25*0.15*500*300)/100000}) cannot settle count-vs-money on its own, so it
 * used to read {@code quantity} and split its group. A group's non-weak members now
 * outvote it, and only that: a unanimous group of counts stays a count, and a count
 * multiplied by another count or by a unit conversion is still a count.
 */
class TypePropagationProductKindTest {

    private static final long SHEET = 9L;
    private final List<InterpretationCellView> cells = new ArrayList<>();
    private final List<CellReferenceEdge> edges = new ArrayList<>();
    private long next = 1L;

    private long literal(String coord, int row, int col, String v) {
        long id = next++;
        cells.add(view(id, coord, row, col, "number", null, v, v, null, v));
        return id;
    }

    private long label(String coord, int row, int col, String t) {
        long id = next++;
        cells.add(view(id, coord, row, col, "string", t, null, t, null, null));
        return id;
    }

    private long formula(String coord, int row, int col, String f, String cached) {
        long id = next++;
        cells.add(view(id, coord, row, col, "number", null, cached, cached, f, cached));
        return id;
    }

    private static InterpretationCellView view(
            long id, String coord, int row, int col, String vt, String tv,
            String nv, String dv, String f, String cv) {
        return new InterpretationCellView(
                id, SHEET, coord, row, col, vt, tv, dv, nv, null, null, f,
                f == null ? null : "ok", cv, cv == null ? null : "cached", false, null,
                false, false, null, "cell");
    }

    private void edge(long from, int idx, String token) {
        edges.add(new CellReferenceEdge(
                from, idx, token, "cell", null, null, token, null, null,
                false, false, null, null, false, false, null));
    }

    private CellTypes resolve() {
        return new TypePropagation().resolve(new CellGraphBuilder().build(1L, cells, edges));
    }

    /** The count F25 and the weak Game Parlour product E62, as in the workbook. */
    private long weakGameParlour() {
        label("A62", 62, 1, "Game Parlour");
        label("A15", 15, 1, "No. of Rooms");
        long roomCount = literal("F15", 15, 6, "380");
        long roomDays = formula("F25", 25, 6, "=SUM(F15:F15)", "380");
        edge(roomDays, 0, "F15:F15");
        long game = formula("E62", 62, 5, "=(F25*0.15*500*300)/100000", "87.75");
        edge(game, 0, "F25");
        return game;
    }

    @Test
    void aMoneyGroupOutvotesOneWeakCountProduct() {
        // SALESPROJECTION group 7047: E65 = SUM(E57:E63), five known money lines and
        // the Game Parlour product. The group is what settles E62 as money.
        long game = weakGameParlour();
        label("A58", 58, 1, "Room Sales (Rs. In Lacs)");
        label("A59", 59, 1, "F & B Sales (Rs. In Lacs)");
        label("A60", 60, 1, "Banquet Hall Receipts (Rs. In Lacs)");
        label("A61", 61, 1, "Fitness Centre (Rs. In Lacs)");
        label("A63", 63, 1, "Conference Hall Receipts (Rs. In Lacs)");
        literal("E58", 58, 5, "3668.25");
        literal("E59", 59, 5, "884.34");
        literal("E60", 60, 5, "195.00");
        literal("E61", 61, 5, "24.00");
        literal("E63", 63, 5, "45.00");
        long head = formula("E65", 65, 5, "=SUM(E57:E63)", "4904.34");
        edge(head, 0, "E57:E63");

        CellTypes types = resolve();

        ResolvedUnit unit = types.unitOf(game).orElseThrow();
        assertThat(unit.kind())
                .as("five known money lines outvote one count-times-constants product")
                .isEqualTo(CellKind.MONEY);
        assertThat(unit.scale()).isEqualTo(CellScale.LAKH);
        assertThat(types.unitOf(head).orElseThrow().kind()).isEqualTo(CellKind.MONEY);
        assertThat(types.unitOf(head).orElseThrow().scale()).isEqualTo(CellScale.LAKH);
    }

    @Test
    void oneMoneyLineOutvotesOneWeakCountProduct() {
        // power cost group 8877: E36 = SUM(E33:E34), one known money line, one weak
        // count product. A single known line is still enough.
        label("A33", 33, 1, "Variable power charges (Rs. In Lacs)");
        literal("E33", 33, 5, "182.9672");
        label("A34", 34, 1, "Fixed Power charges");
        label("A19", 19, 1, "No. of Units");
        long units = literal("J19", 19, 10, "145");
        long fixed = formula("E34", 34, 5, "=(J19*12)/100000", "0.0174");
        edge(fixed, 0, "J19");
        long head = formula("E36", 36, 5, "=SUM(E33:E34)", "182.98");
        edge(head, 0, "E33:E34");

        CellTypes types = resolve();

        ResolvedUnit unit = types.unitOf(fixed).orElseThrow();
        assertThat(unit.kind())
                .as("one known money line outvotes one weak count product")
                .isEqualTo(CellKind.MONEY);
        assertThat(unit.scale()).isEqualTo(CellScale.LAKH);
        assertThat(types.unitOf(head).orElseThrow().kind()).isEqualTo(CellKind.MONEY);
    }

    @Test
    void aMoneyGroupOutvotesWeakRateAndQuantityMembersTransitively() {
        // ASSETS group 7000: I62 = I52+...+I60. Four money lines, a weak rate that
        // traces back through D145*1, and a weak quantity. Both yield.
        label("A52", 52, 1, "Genset (Rs. In Lacs)");
        label("A54", 54, 1, "Elevator (Rs. In Lacs)");
        label("A55", 55, 1, "Kitchen Equipments (Rs. In Lacs)");
        label("A60", 60, 1, "Air Conditioning (Rs. In Lacs)");
        literal("I52", 52, 9, "0.0");
        literal("I54", 54, 9, "120.30");
        literal("I55", 55, 9, "110.68");
        literal("I60", 60, 9, "210.6");

        label("A145", 145, 1, "Rate");
        long rateInput = literal("D145", 145, 4, "1300000");
        long rateProduct = formula("E145", 145, 5, "=D145*1", "1300000");
        edge(rateProduct, 0, "D145");
        long weakRate = formula("I57", 57, 9, "=E145/100000", "25.96");
        edge(weakRate, 0, "E145");

        label("A15", 15, 1, "No. of Rooms");
        long rooms = literal("F15", 15, 6, "380");
        long weakQuantity = formula("I59", 59, 9, "=(F15*0)/100000", "0.0");
        edge(weakQuantity, 0, "F15");

        long head = formula("I62", 62, 9, "=I52+I55+I57+I54+I59+I60", "442.58");
        edge(head, 0, "I52");
        edge(head, 1, "I55");
        edge(head, 2, "I57");
        edge(head, 3, "I54");
        edge(head, 4, "I59");
        edge(head, 5, "I60");

        CellTypes types = resolve();

        assertThat(types.unitOf(rateProduct).orElseThrow().kind())
                .as("rate times a literal is the weak shape")
                .isEqualTo(CellKind.RATE);
        assertThat(types.unitOf(weakRate).orElseThrow().kind())
                .as("the weak rate yields to the money majority")
                .isEqualTo(CellKind.MONEY);
        assertThat(types.unitOf(weakQuantity).orElseThrow().kind())
                .as("the weak quantity yields to the money majority")
                .isEqualTo(CellKind.MONEY);
        assertThat(types.unitOf(head).orElseThrow().kind()).isEqualTo(CellKind.MONEY);
    }

    @Test
    void aUnanimousGroupOfCountsIsNotOverridden() {
        // SALESPROJECTION group 7030: H25 = SUM(H12:H24) over H15/H16/H17 = D*365.
        // Every member is a weak count, so there is no money majority and the rule
        // never fires.
        label("A15", 15, 1, "No. of Deluxe Rooms");
        label("A16", 16, 1, "No. of Executive Suite Rooms");
        label("A17", 17, 1, "No. of Presidential Suite");
        long rooms15 = literal("D15", 15, 4, "190");
        long rooms16 = literal("D16", 16, 4, "4");
        long rooms17 = literal("D17", 17, 4, "1");
        long guestNights15 = formula("H15", 15, 8, "=D15*365", "69350");
        long guestNights16 = formula("H16", 16, 8, "=D16*365", "1460");
        long guestNights17 = formula("H17", 17, 8, "=D17*365", "365");
        edge(guestNights15, 0, "D15");
        edge(guestNights16, 0, "D16");
        edge(guestNights17, 0, "D17");
        long head = formula("H25", 25, 8, "=SUM(H12:H24)", "71175");
        edge(head, 0, "H12:H24");

        CellTypes types = resolve();

        assertThat(types.unitOf(guestNights15).orElseThrow().kind())
                .as("a count times a period is still a count")
                .isEqualTo(CellKind.QUANTITY);
        assertThat(types.unitOf(head).orElseThrow().kind()).isEqualTo(CellKind.QUANTITY);
    }

    @Test
    void aCountTimesAnotherCountStaysACount() {
        label("A1", 1, 1, "No. of Rooms");
        long rooms = literal("B1", 1, 2, "190");
        label("A2", 2, 1, "No. of Beds per Room");
        long beds = literal("B2", 2, 2, "2");
        long bedCount = formula("B3", 3, 2, "=B1*B2", "380");
        edge(bedCount, 0, "B1");
        edge(bedCount, 1, "B2");

        CellTypes types = resolve();

        assertThat(types.unitOf(bedCount).orElseThrow().kind())
                .as("counting a count is still a count")
                .isEqualTo(CellKind.QUANTITY);
    }

    @Test
    void aCountTimesAnAreaConversionStaysACount() {
        label("A1", 1, 1, "Plot Area");
        long sqm = literal("B1", 1, 2, "2763.04");
        long sqft = formula("B2", 2, 2, "=B1*10.764", "29741.36");
        edge(sqft, 0, "B1");

        CellTypes types = resolve();

        assertThat(types.unitOf(sqft).orElseThrow().kind())
                .as("a unit conversion is not a monetary amount")
                .isEqualTo(CellKind.QUANTITY);
    }

    @Test
    void aCountTimesAPercentStaysACount() {
        label("A1", 1, 1, "No. of Rooms");
        long rooms = literal("B1", 1, 2, "390");
        long share = formula("B2", 2, 2, "=B1*0.15", "58.5");
        edge(share, 0, "B1");

        CellTypes types = resolve();

        assertThat(types.unitOf(share).orElseThrow().kind())
                .as("a share of a count is still a count")
                .isEqualTo(CellKind.QUANTITY);
    }

    @Test
    void aCountTimesATypedRateIsMoney() {
        label("A1", 1, 1, "No. of Rooms");
        long rooms = literal("B1", 1, 2, "40");
        label("A2", 2, 1, "Room Rate");
        long rate = literal("B2", 2, 2, "5000");
        long revenue = formula("B3", 3, 2, "=B1*B2", "200000");
        edge(revenue, 0, "B1");
        edge(revenue, 1, "B2");

        CellTypes types = resolve();

        assertThat(types.unitOf(revenue).orElseThrow().kind()).isEqualTo(CellKind.MONEY);
    }
}
