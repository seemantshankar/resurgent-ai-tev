package com.resurgent.tev.parser.nomenclature;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The P&amp;L, balance sheet, cash flow and key-ratio spine goes down to leaves, not just a mid-level. */
class StatementSpineTest {

    @Test
    void spineHasNoDuplicatePathsAndEveryParentExists() {
        List<String> paths = NomenclatureSeed.SPINE_PATHS;
        assertThat(new HashSet<>(paths)).hasSameSizeAs(paths);
        for (String path : paths) {
            String parent = NomenclatureSeed.parentOf(path);
            if (parent != null) {
                assertThat(paths).as("parent of " + path).contains(parent);
            }
        }
    }

    @Test
    void statementsAndRatiosHaveLeavesUnderTheirMidLevels() {
        assertThat(NomenclatureSeed.SPINE_PATHS).contains(
                "Profit & Loss > Revenue",
                "Profit & Loss > EBITDA",
                "Profit & Loss > PAT",
                "Balance Sheet > Net Worth",
                "Balance Sheet > Total Debt",
                "Cash Flow > Cash Flow from Operations",
                "Key Ratios > Profitability > EBITDA Margin",
                "Key Ratios > Leverage > Debt Equity",
                "Key Ratios > Coverage > DSCR");
    }

    @Test
    void renewableEquipmentFreightAndOperatingExpenseHeadsAreOnTheSpine() {
        assertThat(NomenclatureSeed.SPINE_PATHS).contains(
                "Project Cost > Freight",
                "Project Cost > Plant & Machinery > Solar PV Modules",
                "Project Cost > Plant & Machinery > Inverters / PCU",
                "Project Cost > Plant & Machinery > Module Mounting Structures",
                "Project Cost > Plant & Machinery > Cables",
                "Project Cost > Plant & Machinery > Earthing",
                "Project Cost > Plant & Machinery > Transformer",
                "Project Cost > Plant & Machinery > Switchgear",
                "Profit & Loss > Operating Expenses > Salaries & Wages",
                "Profit & Loss > Operating Expenses > Power & Fuel",
                "Profit & Loss > Operating Expenses > Repairs & Maintenance",
                "Profit & Loss > Operating Expenses > Rent",
                "Profit & Loss > Operating Expenses > Insurance",
                "Profit & Loss > Operating Expenses > Administrative Expenses",
                "Profit & Loss > Operating Expenses > Selling & Distribution");
    }

    @Test
    void unqualifiedTransportationAndModuleAreNotGlobalAliases() {
        Map<String, String> aliases = com.resurgent.tev.parser.classify.LayerBBinderAccess.aliasIndex();
        assertThat(aliases.get(com.resurgent.tev.parser.classify.LayerBBinderAccess.norm("Transportation")))
                .isNull();
        assertThat(aliases.get(com.resurgent.tev.parser.classify.LayerBBinderAccess.norm("Module")))
                .isNull();
    }

    @Test
    void shortRenewableAndExpenseLabelsResolveToThoseHeads() {
        Map<String, String> aliases = com.resurgent.tev.parser.classify.LayerBBinderAccess.aliasIndex();
        Map<String, String> expected = Map.ofEntries(
                Map.entry("Freight Charges", "Project Cost > Freight"),
                Map.entry("Modules", "Project Cost > Plant & Machinery > Solar PV Modules"),
                Map.entry("Solar Module", "Project Cost > Plant & Machinery > Solar PV Modules"),
                Map.entry("Inverter", "Project Cost > Plant & Machinery > Inverters / PCU"),
                Map.entry("Inverters", "Project Cost > Plant & Machinery > Inverters / PCU"),
                Map.entry("PCU", "Project Cost > Plant & Machinery > Inverters / PCU"),
                Map.entry("Mounting Structure", "Project Cost > Plant & Machinery > Module Mounting Structures"),
                Map.entry("Cable", "Project Cost > Plant & Machinery > Cables"),
                Map.entry("Transformers", "Project Cost > Plant & Machinery > Transformer"),
                Map.entry("Switch Gear", "Project Cost > Plant & Machinery > Switchgear"),
                Map.entry("Salaries", "Profit & Loss > Operating Expenses > Salaries & Wages"),
                Map.entry("Power and Fuel", "Profit & Loss > Operating Expenses > Power & Fuel"),
                Map.entry("Repairs and Maintenance", "Profit & Loss > Operating Expenses > Repairs & Maintenance"));
        expected.forEach((label, path) ->
                assertThat(aliases.get(com.resurgent.tev.parser.classify.LayerBBinderAccess.norm(label)))
                        .as(label).isEqualTo(path));
    }

    @Test
    void keyRatioSheetLabelsResolveToLeavesByAlias() {
        Map<String, String> aliases = com.resurgent.tev.parser.classify.LayerBBinderAccess.aliasIndex();
        Map<String, String> expected = Map.ofEntries(
                Map.entry("Revenue", "Profit & Loss > Revenue"),
                Map.entry("EBITDA", "Profit & Loss > EBITDA"),
                Map.entry("PAT", "Profit & Loss > PAT"),
                Map.entry("Net Worth", "Balance Sheet > Net Worth"),
                Map.entry("Total Debt", "Balance Sheet > Total Debt"),
                Map.entry("EBITDA Margin (after LF)", "Key Ratios > Profitability > EBITDA Margin"),
                Map.entry("PAT Margin", "Key Ratios > Profitability > PAT Margin"),
                Map.entry("ROCE (EBIT/ Capital Employed)", "Key Ratios > Profitability > Return on Capital Employed"),
                Map.entry("Debt Equity (in times)", "Key Ratios > Leverage > Debt Equity"),
                Map.entry("Sales Growth", "Key Ratios > Growth > Sales Growth"));
        expected.forEach((label, path) ->
                assertThat(aliases.get(com.resurgent.tev.parser.classify.LayerBBinderAccess.norm(label)))
                        .as(label).isEqualTo(path));
    }
}
