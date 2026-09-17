package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.discover.Packet;
import com.resurgent.tev.parser.discover.PacketCell;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayerBAmountSupportTest {

    @Test
    void formulaNumericsArePromptEligibleButOnlyBindableAsHelperOrTotal() {
        PacketCell formula = new PacketCell(
                1L, 1L, "B10", 10, 2, PacketCell.ROLE_CORE, "number",
                null, "100", "100", "SUM(B2:B9)", false, false);
        PacketCell literal = new PacketCell(
                2L, 1L, "B2", 2, 2, PacketCell.ROLE_CORE, "number",
                null, "40", "40", null, false, false);
        PacketCell label = new PacketCell(
                3L, 1L, "A10", 10, 1, PacketCell.ROLE_CORE, "string",
                "Total", "Total", null, null, false, false);

        assertThat(LayerBAmountSupport.isFormulaNumeric(formula)).isTrue();
        assertThat(LayerBAmountSupport.isLiteralNumeric(formula)).isFalse();
        assertThat(LayerBAmountSupport.isBindableForRole(formula, AmountRole.HELPER)).isTrue();
        assertThat(LayerBAmountSupport.isBindableForRole(formula, AmountRole.TOTAL)).isTrue();
        assertThat(LayerBAmountSupport.isBindableForRole(formula, AmountRole.ADD)).isFalse();
        assertThat(LayerBAmountSupport.isBindableForRole(literal, AmountRole.ADD)).isTrue();
        assertThat(LayerBAmountSupport.isPromptNumeric(label)).isFalse();

        Packet packet = new Packet(1L, 1L, 1L, "child", List.of(label, literal, formula), List.of(), true);
        assertThat(LayerBAmountSupport.amountCells(packet)).containsExactly(literal, formula);
    }

    @Test
    void numberOfUnitsIsQuantityNotMoneyCostBinding() {
        Packet packet = new Packet(1L, 1L, 1L, "child", List.of(
                new PacketCell(1L, 1L, "A1", 1, 1, PacketCell.ROLE_CORE, "string",
                        "Number of Units", "Number of Units", null, null, false, false),
                new PacketCell(2L, 1L, "B1", 1, 2, PacketCell.ROLE_CORE, "number",
                        null, "3", "3", null, false, false),
                new PacketCell(3L, 1L, "A2", 2, 1, PacketCell.ROLE_CORE, "string",
                        "Construction cost", "Construction cost", null, null, false, false),
                new PacketCell(4L, 1L, "B2", 2, 2, PacketCell.ROLE_CORE, "number",
                        null, "125000", "125000", null, false, false),
                new PacketCell(5L, 1L, "A3", 3, 1, PacketCell.ROLE_CORE, "string",
                        "Rate per unit", "Rate per unit", null, null, false, false),
                new PacketCell(6L, 1L, "B3", 3, 2, PacketCell.ROLE_CORE, "number",
                        null, "4500", "4500", null, false, false)),
                List.of(), true);

        PacketCell units = packet.cells().get(1);
        PacketCell cost = packet.cells().get(3);
        PacketCell rate = packet.cells().get(5);

        assertThat(LayerBAmountSupport.classifyKind(packet, units)).isEqualTo(NumericKind.QUANTITY);
        assertThat(LayerBAmountSupport.classifyKind(packet, cost)).isEqualTo(NumericKind.MONEY);
        assertThat(LayerBAmountSupport.classifyKind(packet, rate)).isEqualTo(NumericKind.RATE);

        assertThat(LayerBAmountSupport.isBindableForRole(packet, units, AmountRole.ADD)).isFalse();
        assertThat(LayerBAmountSupport.isBindableForRole(packet, units, AmountRole.HELPER)).isTrue();
        assertThat(LayerBAmountSupport.isBindableForRole(packet, cost, AmountRole.ADD)).isTrue();
        assertThat(LayerBAmountSupport.isBindableForRole(packet, rate, AmountRole.ADD)).isFalse();
        assertThat(LayerBAmountSupport.isBindableForRole(packet, rate, AmountRole.HELPER)).isTrue();

        String user = LayerBPromptAssembler.userMessage(new LayerBPrompt(
                packet,
                new com.resurgent.tev.parser.nomenclature.OntologySlice(
                        com.resurgent.tev.parser.nomenclature.IndustryResolution.confirmed("hotel"),
                        List.of(), List.of()),
                new LayerAJudgment(ScheduleFamily.CAPEX_DETAIL, Triage.MAIN, Relevance.PRIMARY,
                        List.of(), List.of(), null),
                null));
        assertThat(user).contains("\"kind\":\"quantity\"");
        assertThat(user).contains("\"kind\":\"money\"");
        assertThat(user).contains("\"kind\":\"rate\"");
    }

    @Test
    void contextKeepsNearbyHeadersAndSectionLabels() {
        Packet packet = new Packet(1L, 1L, 1L, "child", List.of(
                new PacketCell(1L, 1L, "A1", 1, 1, PacketCell.ROLE_CONTEXT, "string",
                        "Project Cost", "Project Cost", null, null, false, false),
                new PacketCell(2L, 1L, "B1", 1, 2, PacketCell.ROLE_CONTEXT, "string",
                        "Amount (Rs)", "Amount (Rs)", null, null, false, false),
                new PacketCell(3L, 1L, "A2", 2, 1, PacketCell.ROLE_CORE, "string",
                        "Civil Works", "Civil Works", null, null, false, false),
                new PacketCell(4L, 1L, "B2", 2, 2, PacketCell.ROLE_CORE, "number",
                        null, "100", "100", null, false, false),
                new PacketCell(5L, 1L, "Z99", 99, 26, PacketCell.ROLE_CORE, "string",
                        "far away", "far away", null, null, false, false)),
                List.of(), true);
        List<PacketCell> context = LayerBAmountSupport.contextCells(packet);
        assertThat(context.stream().map(PacketCell::coord)).contains("A1", "B1", "A2");
        assertThat(context.stream().map(PacketCell::coord)).doesNotContain("Z99");
        assertThat(LayerBAmountSupport.resolveColumnHeader(packet,
                packet.cells().get(3))).isEqualTo("Amount (Rs)");
        assertThat(LayerBAmountSupport.classifyKind(packet, packet.cells().get(3)))
                .isEqualTo(NumericKind.MONEY);
    }

    @Test
    void periodHeadersDoNotMakeEveryColumnCellAQuantity() {
        Packet packet = new Packet(1L, 1L, 1L, "child", List.of(
                new PacketCell(1L, 1L, "J31", 31, 10, PacketCell.ROLE_CONTEXT, "string",
                        "Year 7", "Year 7", null, null, false, false),
                new PacketCell(2L, 1L, "A45", 45, 1, PacketCell.ROLE_CORE, "string",
                        "Insurance Premium", "Insurance Premium", null, null, false, false),
                new PacketCell(3L, 1L, "J45", 45, 10, PacketCell.ROLE_CORE, "number",
                        null, "125000", "125000", null, false, false),
                new PacketCell(4L, 1L, "A46", 46, 1, PacketCell.ROLE_CORE, "string",
                        "No. of Rooms", "No. of Rooms", null, null, false, false),
                new PacketCell(5L, 1L, "J46", 46, 10, PacketCell.ROLE_CORE, "number",
                        null, "40", "40", null, false, false)),
                List.of(), true);

        PacketCell premium = packet.cells().get(2);
        PacketCell rooms = packet.cells().get(4);

        assertThat(LayerBAmountSupport.resolveColumnHeader(packet, premium)).isEqualTo("Year 7");
        assertThat(LayerBAmountSupport.classifyKind(packet, premium)).isEqualTo(NumericKind.MONEY);
        assertThat(LayerBAmountSupport.classifyKind(packet, rooms)).isEqualTo(NumericKind.QUANTITY);
    }

    @Test
    void periodHeaderShapesAreRecognisedAndPlainDurationsAreNot() {
        assertThat(LayerBAmountSupport.isPeriodHeader("Year 1")).isTrue();
        assertThat(LayerBAmountSupport.isPeriodHeader("YR-3")).isTrue();
        assertThat(LayerBAmountSupport.isPeriodHeader("FY 2026")).isTrue();
        assertThat(LayerBAmountSupport.isPeriodHeader("FY 2025-26")).isTrue();
        assertThat(LayerBAmountSupport.isPeriodHeader("Q3")).isTrue();
        assertThat(LayerBAmountSupport.isPeriodHeader("Month 12")).isTrue();
        assertThat(LayerBAmountSupport.isPeriodHeader("2027")).isTrue();
        assertThat(LayerBAmountSupport.isPeriodHeader("Years")).isTrue();
        assertThat(LayerBAmountSupport.isPeriodHeader("No. of Years of Operation")).isFalse();
        assertThat(LayerBAmountSupport.isPeriodHeader("Rooms")).isFalse();
        assertThat(LayerBAmountSupport.isPeriodHeader("")).isFalse();
    }
}
