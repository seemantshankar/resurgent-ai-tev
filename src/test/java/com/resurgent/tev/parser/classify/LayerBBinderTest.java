package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.BindCellRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Layer B paths: one root per eligible cell, none for errors. */
class LayerBBinderTest {

    @Test
    void sumMembersAreAddsResidualsStayUnboundAndRescaleIsHelper() {
        List<BindCellRow> cells = List.of(
                cell(1, "C5", 5, 3, "text", "PRELIMINARY & PRE-OPERATIVE EXPENSES", null, false, false, null),
                cell(2, "D6", 6, 4, "text", "(Amt. in Rs.)", null, false, false, null),
                cell(3, "C9", 9, 3, "text", "Deposits", null, false, false, null),
                cell(4, "D9", 9, 4, "number", null, null, false, false, null),
                cell(5, "C10", 10, 3, "text", "Establishment", null, false, false, null),
                cell(6, "D10", 10, 4, "number", null, null, false, false, null),
                cell(7, "C20", 20, 3, "text", "TOTAL", null, false, false, null),
                cell(8, "D20", 20, 4, "number", null, "SUM(D9:D10)", false, false, null),
                cell(9, "F19", 19, 6, "number", null, "D20-D9", false, false, null),
                cell(10, "H14", 14, 8, "number", null, "D20/100000", false, false, null));
        List<LayerBAssignment> llm = List.of(
                new LayerBAssignment(9, null, "economic",
                        "Project Cost > Preliminary & Pre-operative Expenses"),
                new LayerBAssignment(10, null, "economic",
                        "Project Cost > Preliminary & Pre-operative Expenses"),
                new LayerBAssignment(20, null, "economic",
                        "Project Cost > Preliminary & Pre-operative Expenses"),
                new LayerBAssignment(null, "F19", "economic",
                        "Project Cost > Preliminary & Pre-operative Expenses"),
                new LayerBAssignment(null, "H14", "economic",
                        "Project Cost > Preliminary & Pre-operative Expenses"));
        List<LayerBBinder.Draft> drafts = LayerBBinder.bind(cells, llm, LayerBBinder.aliasIndex());
        Map<String, LayerBBinder.Draft> byCoord = drafts.stream()
                .collect(java.util.stream.Collectors.toMap(LayerBBinder.Draft::coord, d -> d));

        assertThat(byCoord.get("D9").path())
                .isEqualTo("Project Cost > Preliminary & Pre-operative Expenses");
        assertThat(byCoord.get("D9").amountRole()).isEqualTo("add");
        assertThat(byCoord.get("D10").amountRole()).isEqualTo("add");
        assertThat(byCoord.get("D20").amountRole()).isEqualTo("total");
        assertThat(byCoord.get("C9").amountRole()).isNull();
        assertThat(byCoord).doesNotContainKey("F19");
        assertThat(byCoord.get("H14").path())
                .isEqualTo("Project Cost > Preliminary & Pre-operative Expenses");
        assertThat(byCoord.get("H14").amountRole()).isEqualTo("helper");
        assertThat(byCoord.get("D6").path()).isEqualTo(LayerBBinder.FRAME_SCALE);
    }

    @Test
    void aLabelledRowOffEverySumKeepsItsPathWithNoRoleButSideArithmeticStaysUnbound() {
        String path = "Profit & Loss > Operating Expenses";
        List<BindCellRow> cells = List.of(
                cell(1, "B4", 4, 2, "text", "Salaries", null, false, false, null),
                cell(2, "C4", 4, 3, "number", null, null, false, false, null),
                cell(3, "B5", 5, 2, "text", "Rent", null, false, false, null),
                cell(4, "C5", 5, 3, "number", null, null, false, false, null),
                cell(5, "B6", 6, 2, "text", "Total Expenses", null, false, false, null),
                cell(6, "C6", 6, 3, "number", null, "SUM(C4:C5)", false, false, null),
                // A labelled row whose amounts are a difference, and a figure typed in: both off the SUM.
                cell(7, "B7", 7, 2, "text", "Other Expenses", null, false, false, null),
                cell(8, "C7", 7, 3, "number", null, "C6-C4", false, false, null),
                cell(9, "D7", 7, 4, "number", null, null, false, false, null),
                // Arithmetic with no label on its row, beside the table.
                cell(10, "F9", 9, 6, "number", null, "C6-C4", false, false, null));
        List<LayerBAssignment> llm = List.of(
                new LayerBAssignment(4, null, "economic", path),
                new LayerBAssignment(5, null, "economic", path),
                new LayerBAssignment(6, null, "economic", path),
                new LayerBAssignment(7, null, "economic", path),
                new LayerBAssignment(null, "F9", "economic", path));
        Map<String, LayerBBinder.Draft> byCoord = LayerBBinder.bind(cells, llm, LayerBBinder.aliasIndex()).stream()
                .collect(java.util.stream.Collectors.toMap(LayerBBinder.Draft::coord, d -> d));

        assertThat(byCoord.get("C4").amountRole()).isEqualTo("add");
        assertThat(byCoord.get("C6").amountRole()).isEqualTo("total");
        assertThat(byCoord.get("C7").path()).isEqualTo(path);
        assertThat(byCoord.get("C7").amountRole()).isNull();
        assertThat(byCoord.get("D7").path()).isEqualTo(path);
        assertThat(byCoord).doesNotContainKey("F9");
    }

    @Test
    void promoterAliasesShareOnePathAndMachineryAliasDoesNotPaintSideArithmetic() {
        Map<String, String> aliases = new java.util.LinkedHashMap<>(LayerBBinder.aliasIndex());
        aliases.put(LayerBBinder.norm("Promoter Name"), LayerBBinder.PARTNERS);
        aliases.put(LayerBBinder.norm("P&M Cost"), "Project Cost > Plant & Machinery");
        aliases = java.util.Map.copyOf(aliases);

        List<BindCellRow> identity = List.of(
                cell(1, "C5", 5, 3, "text", "Name of Partners", null, false, false, null),
                cell(2, "G5", 5, 7, "text", "A. Kumar", null, false, false, null),
                cell(3, "C6", 6, 3, "text", "Promoter Name", null, false, false, null),
                cell(4, "G6", 6, 7, "text", "B. Shah", null, false, false, null));
        List<LayerBBinder.Draft> identityDrafts = LayerBBinder.bind(identity, List.of(), aliases);
        Map<String, LayerBBinder.Draft> identityBy = identityDrafts.stream()
                .collect(java.util.stream.Collectors.toMap(LayerBBinder.Draft::coord, d -> d));
        assertThat(identityBy.get("G5").path()).isEqualTo(LayerBBinder.PARTNERS);
        assertThat(identityBy.get("G6").path()).isEqualTo(LayerBBinder.PARTNERS);
        assertThat(identityBy.get("G5").amountRole()).isNull();

        List<BindCellRow> machinery = List.of(
                cell(10, "C8", 8, 3, "text", "DETAILS OF PLANT & MACHINERIES", null, false, false, null),
                cell(11, "C12", 12, 3, "text", "Elevator", null, false, false, null),
                cell(12, "D12", 12, 4, "number", null, null, false, false, null),
                cell(13, "C13", 13, 3, "text", "P&M Cost", null, false, false, null),
                cell(14, "D13", 13, 4, "number", null, null, false, false, null),
                cell(15, "C20", 20, 3, "text", "TOTAL", null, false, false, null),
                cell(16, "D20", 20, 4, "number", null, "SUM(D12:D13)", false, false, null),
                cell(17, "F18", 18, 6, "number", null, "D20-D12", false, false, null));
        List<LayerBBinder.Draft> machineryDrafts =
                LayerBBinder.bind(machinery, List.of(), aliases);
        Map<String, LayerBBinder.Draft> machineryBy = machineryDrafts.stream()
                .collect(java.util.stream.Collectors.toMap(LayerBBinder.Draft::coord, d -> d));
        assertThat(machineryBy.get("D12").path())
                .isEqualTo("Project Cost > Plant & Machinery > Elevator / Lift");
        assertThat(machineryBy.get("D12").amountRole()).isEqualTo("add");
        assertThat(machineryBy.get("D13").path()).isEqualTo("Project Cost > Plant & Machinery");
        assertThat(machineryBy.get("D13").amountRole()).isEqualTo("add");
        assertThat(machineryBy.get("D20").amountRole()).isEqualTo("total");
        assertThat(machineryBy).doesNotContainKey("F18");
    }

    @Test
    void copiesStayHelpersPeriodsStayFrameAndTheFirmNameIsTheAddressLine() {
        List<BindCellRow> glance = List.of(
                cell(1, "D3", 3, 4, "text", "PROJECT AT GLANCE", null, false, false, "D3:G3"),
                cell(2, "E3", 3, 5, "empty", null, null, false, true, "D3:G3"),
                cell(3, "B5", 5, 2, "text", "1)", null, false, false, null),
                cell(4, "C5", 5, 3, "text", "NAME OF THE FIRM", null, false, false, null),
                cell(5, "F5", 5, 6, "text", ":", null, false, false, null),
                cell(6, "G5", 5, 7, "text", "OM ARHAM VENTURES", null, false, false, null),
                cell(7, "B17", 17, 2, "text", "4)", null, false, false, null),
                cell(8, "C17", 17, 3, "text", "LOCATION", null, false, false, null),
                cell(9, "G17", 17, 7, "text", "OM ARHAM VENTURES", "G5", false, false, null),
                cell(10, "G19", 19, 7, "text", "ADMIN. OFF.: ROOM NO.1", null, false, false, null),
                cell(11, "G25", 25, 7, "text", "ASSAM, PIN-781122", null, false, false, null),
                cell(12, "C28", 28, 3, "text", "PROJECT COST", null, false, false, null),
                cell(13, "G28", 28, 7, "text", "Total", null, false, false, null),
                cell(14, "J28", 28, 10, "number", null, "'CAPITAL COST'!D28", false, false, null),
                cell(15, "G34", 34, 7, "text", "Term Loan From Financial Institutions", null, false, false, null),
                cell(16, "J34", 34, 10, "number", null, "'CAPITAL COST'!D50", false, false, null));
        List<BindCellRow> flow = List.of(
                cell(17, "B7", 7, 2, "text", "Year", null, false, false, "B7:C7"),
                cell(18, "C7", 7, 3, "empty", null, null, false, true, "B7:C7"),
                cell(19, "E7", 7, 5, "text", "Year 1", null, false, false, null),
                cell(20, "B11", 11, 2, "text", "Profit before Interest & Taxes", null, false, false, null),
                cell(21, "E11", 11, 5, "number", null, "'P  L '!D61", false, false, null),
                cell(22, "F11", 11, 6, "number", null, null, false, false, null),
                cell(23, "M12", 12, 13, "error", "#VALUE!", "'P  L '!L61", true, false, null),
                cell(24, "D20", 20, 4, "number", null, "SUM(D9:D19)", false, false, null),
                cell(25, "B20", 20, 2, "text", "TOTAL INFLOWS", null, false, false, null));

        List<LayerBAssignment> llm = List.of(
                new LayerBAssignment(11, null, "economic", "Profit & Loss"),
                new LayerBAssignment(20, null, "economic", "Cash Flow > Total Inflows"));

        List<BindCellRow> cells = new java.util.ArrayList<>();
        cells.addAll(glance);
        cells.addAll(flow);
        List<LayerBBinder.Draft> drafts = new java.util.ArrayList<>();
        drafts.addAll(LayerBBinder.bind(glance, List.of(), LayerBBinder.aliasIndex()));
        drafts.addAll(LayerBBinder.bind(flow, llm, LayerBBinder.aliasIndex()));
        Map<String, LayerBBinder.Draft> byCoord = drafts.stream()
                .collect(java.util.stream.Collectors.toMap(LayerBBinder.Draft::coord, d -> d));

        assertThat(byCoord).doesNotContainKey("M12");
        assertThat(byCoord.get("E7").pathRoot()).isEqualTo("frame");
        assertThat(byCoord.get("E7").path()).isEqualTo(LayerBBinder.FRAME_PERIOD);
        assertThat(byCoord.get("C7").path()).isEqualTo(LayerBBinder.FRAME_PERIOD);
        assertThat(byCoord.get("E11").pathRoot()).isEqualTo("economic");
        assertThat(byCoord.get("E11").path()).isNotEqualTo(LayerBBinder.FRAME_PERIOD);
        assertThat(byCoord.get("E11").amountRole()).isEqualTo("helper");
        assertThat(byCoord.get("F11").path()).isEqualTo("Profit & Loss");
        assertThat(byCoord.get("F11").amountRole()).isEqualTo("add");
        assertThat(byCoord.get("B11").amountRole()).isNull();

        assertThat(byCoord.get("J34").path()).isEqualTo("Means of Finance > Term Loan");
        assertThat(byCoord.get("J34").amountRole()).isEqualTo("helper");
        assertThat(byCoord.get("J28").path()).isEqualTo("Project Cost");
        assertThat(byCoord.get("J28").amountRole()).isEqualTo("helper");
        assertThat(byCoord.get("C28").path()).isEqualTo("Project Cost");
        assertThat(byCoord.get("C28").amountRole()).isNull();
        assertThat(byCoord.get("G28").path()).isEqualTo("Project Cost");

        assertThat(byCoord.get("G5").path()).isEqualTo(LayerBBinder.LEGAL_NAME);
        assertThat(List.of("G17", "G19", "G25"))
                .allSatisfy(coord -> {
                    assertThat(byCoord.get(coord).pathRoot()).isEqualTo("identity");
                    assertThat(byCoord.get(coord).path()).isEqualTo(LayerBBinder.ADDRESS);
                });
        assertThat(byCoord.get("G17").path()).isNotEqualTo(LayerBBinder.LEGAL_NAME);
        assertThat(byCoord.get("D20").amountRole()).isEqualTo("total");
        assertThat(byCoord.get("D3").path()).isEqualTo(LayerBBinder.FRAME_TITLE);
        assertThat(byCoord.get("E3").path()).isEqualTo(LayerBBinder.FRAME_TITLE);
        assertThat(LayerBBinder.unbound(cells, drafts)).isEmpty();
    }

    @Test
    void workingFmGlanceIsFullyProvedAndCashFlowPeriodsAreNotAmounts() throws Exception {
        Path db = Path.of("Project Docs/om_arham_xlsx_output/workspace-om-arham-visible-layer-a.db");
        Assumptions.assumeTrue(Files.isRegularFile(db), "working FM database is local");
        try (WorkspaceDatabase database = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(database.connection());
            long glance = candidate(database.connection(), "AT GLANCE", "main");
            long cashFlow = candidate(database.connection(), "CASH FLOW", "main");
            List<BindCellRow> glanceCells = repo.selectBindCells(glance);
            List<LayerBBinder.Draft> glanceDrafts =
                    LayerBBinder.bind(glanceCells, List.of(), LayerBBinder.aliasIndex());
            assertThat(LayerBBinder.unbound(glanceCells, glanceDrafts)).isEmpty();

            Map<String, LayerBBinder.Draft> glanceByCoord = glanceDrafts.stream()
                    .collect(java.util.stream.Collectors.toMap(LayerBBinder.Draft::coord, d -> d));
            assertThat(List.of("G17", "G19", "G20", "G21", "G22", "G23", "G24", "G25"))
                    .allSatisfy(coord -> assertThat(glanceByCoord.get(coord).path())
                            .isEqualTo(LayerBBinder.ADDRESS));
            assertThat(glanceByCoord.get("G5").path()).isEqualTo(LayerBBinder.LEGAL_NAME);
            assertThat(glanceByCoord.get("J34").path()).isEqualTo("Means of Finance > Term Loan");
            assertThat(glanceByCoord.get("J34").amountRole()).isEqualTo("helper");
            assertThat(List.of("J28", "J30", "J32", "J34", "J37"))
                    .allSatisfy(coord -> assertThat(glanceByCoord.get(coord).amountRole()).isEqualTo("helper"));

            List<BindCellRow> flowCells = repo.selectBindCells(cashFlow);
            List<LayerBBinder.Draft> flowDrafts =
                    LayerBBinder.bind(flowCells, List.of(), LayerBBinder.aliasIndex());
            Map<String, LayerBBinder.Draft> flowByCoord = flowDrafts.stream()
                    .collect(java.util.stream.Collectors.toMap(LayerBBinder.Draft::coord, d -> d));
            for (String header : List.of(
                    "B7", "C7", "D7", "E7", "F7", "G7", "H7", "I7", "J7", "K7", "L7", "M7", "N7")) {
                assertThat(flowByCoord.get(header).path()).isEqualTo(LayerBBinder.FRAME_PERIOD);
            }
            assertThat(flowByCoord).doesNotContainKey("M12");
            assertThat(flowByCoord).doesNotContainKey("N45");
            long scratch = candidate(database.connection(), "CASH FLOW", "scratch");
            assertThat(repo.selectBindCells(scratch)).isNotEmpty();
        }
    }

    private static long candidate(Connection connection, String sheet, String role) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT c.candidate_id FROM candidate c"
                        + " JOIN worksheet w ON w.worksheet_id = c.worksheet_id"
                        + " WHERE w.sheet_name = ? AND c.structural_role = ?")) {
            ps.setString(1, sheet);
            ps.setString(2, role);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        }
    }

    private static BindCellRow cell(
            long id,
            String coord,
            int row,
            int col,
            String type,
            String text,
            String formula,
            boolean error,
            boolean mergedParticipant,
            String mergedRange) {
        return new BindCellRow(
                id, coord, row, col, type, text, formula, error, mergedParticipant,                 mergedRange);
    }
}
