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
