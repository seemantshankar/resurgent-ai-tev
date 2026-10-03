package com.resurgent.tev.parser.nomenclature;

import java.util.List;

/**
 * Om Arham bank-facing heads as the first frozen global spine: CapEx + Means of
 * Finance + working-capital margin, with a thin P&amp;L / BS / CF top level.
 * Hotel leaves and aliases sit in the industry pack, not the frozen spine.
 */
public final class NomenclatureSeed {

    public static final List<String> SPINE_PATHS = List.of(
            "Project Cost",
            "Project Cost > Land & Site Development",
            "Project Cost > Civil Works",
            "Project Cost > Plant & Machinery",
            "Project Cost > Misc. Fixed Assets / Furniture & Fixtures",
            "Project Cost > Electrical Installations",
            "Project Cost > Preliminary & Pre-operative Expenses",
            "Project Cost > Contingency",
            "Project Cost > Margin Money / Working Capital Margin",
            "Means of Finance",
            "Means of Finance > Promoter / Partners' Capital",
            "Means of Finance > Term Loan",
            "Means of Finance > Unsecured Loans",
            "Means of Finance > Working Capital Assistance",
            "Profit & Loss",
            "Profit & Loss > Revenue",
            "Profit & Loss > Cost of Sales",
            "Profit & Loss > Gross Profit",
            "Profit & Loss > Operating Expenses",
            "Profit & Loss > EBITDA",
            "Profit & Loss > Depreciation & Amortisation",
            "Profit & Loss > EBIT",
            "Profit & Loss > Interest & Finance Charges",
            "Profit & Loss > PBT",
            "Profit & Loss > Tax",
            "Profit & Loss > PAT",
            "Balance Sheet",
            "Balance Sheet > Net Block",
            "Balance Sheet > Current Assets",
            "Balance Sheet > Cash & Bank Balance",
            "Balance Sheet > Current Liabilities",
            "Balance Sheet > Total Debt",
            "Balance Sheet > Net Worth",
            "Balance Sheet > Capital Employed",
            "Cash Flow",
            "Cash Flow > Cash Flow from Operations",
            "Cash Flow > Cash Flow from Investing",
            "Cash Flow > Cash Flow from Financing",
            "Key Ratios",
            "Key Ratios > Profitability",
            "Key Ratios > Profitability > Gross Margin",
            "Key Ratios > Profitability > EBITDA Margin",
            "Key Ratios > Profitability > PAT Margin",
            "Key Ratios > Profitability > Return on Capital Employed",
            "Key Ratios > Profitability > Return on Equity",
            "Key Ratios > Leverage",
            "Key Ratios > Leverage > Debt Equity",
            "Key Ratios > Coverage",
            "Key Ratios > Coverage > DSCR",
            "Key Ratios > Coverage > Interest Coverage",
            "Key Ratios > Liquidity",
            "Key Ratios > Liquidity > Current Ratio",
            "Key Ratios > Growth",
            "Key Ratios > Growth > Sales Growth");

    public static final List<NomenclatureAlias> SPINE_ALIASES = List.of(
            new NomenclatureAlias("Building Cost",
                    "Project Cost > Civil Works"),
            new NomenclatureAlias("Civil - Building",
                    "Project Cost > Civil Works"),
            new NomenclatureAlias("DETAILS OF PLANT & MACHINERIES",
                    "Project Cost > Plant & Machinery"),
            new NomenclatureAlias("PLANT & MACHINERIES",
                    "Project Cost > Plant & Machinery"),
            new NomenclatureAlias("PRELIMINARY & PREOPERATIVE EXPENSES",
                    "Project Cost > Preliminary & Pre-operative Expenses"),
            new NomenclatureAlias("PRELIMINARY & PRE-OPERATIVE EXPENSES",
                    "Project Cost > Preliminary & Pre-operative Expenses"),
            new NomenclatureAlias("MARGIN MONEY / INVSTT. FOR WORKING CAPITAL",
                    "Project Cost > Margin Money / Working Capital Margin"),
            new NomenclatureAlias("DETAILS OF MISCELLANEOUS FIXED ASSETS / FURNITURES & FIXTURES",
                    "Project Cost > Misc. Fixed Assets / Furniture & Fixtures"),
            new NomenclatureAlias("ELECTRIFICATIONS & ELECTRICAL INSTALLATIONS",
                    "Project Cost > Electrical Installations"),
            new NomenclatureAlias("MEANS OF FINANCES",
                    "Means of Finance"),
            new NomenclatureAlias("Term Loan From Financial Institutions",
                    "Means of Finance > Term Loan"),
            new NomenclatureAlias("TERM LOAN FROM BANKS",
                    "Means of Finance > Term Loan"),
            new NomenclatureAlias("Partners' Capital",
                    "Means of Finance > Promoter / Partners' Capital"),
            new NomenclatureAlias("PARTNERS' CAPITAL",
                    "Means of Finance > Promoter / Partners' Capital"),
            new NomenclatureAlias("Sales", "Profit & Loss > Revenue"),
            new NomenclatureAlias("Net Sales", "Profit & Loss > Revenue"),
            new NomenclatureAlias("Total Revenue", "Profit & Loss > Revenue"),
            new NomenclatureAlias("Turnover", "Profit & Loss > Revenue"),
            new NomenclatureAlias("Revenue from Operations", "Profit & Loss > Revenue"),
            new NomenclatureAlias("Cost of Goods Sold", "Profit & Loss > Cost of Sales"),
            new NomenclatureAlias("COGS", "Profit & Loss > Cost of Sales"),
            new NomenclatureAlias("Operating Profit", "Profit & Loss > EBITDA"),
            new NomenclatureAlias("Depreciation", "Profit & Loss > Depreciation & Amortisation"),
            new NomenclatureAlias("Interest", "Profit & Loss > Interest & Finance Charges"),
            new NomenclatureAlias("Finance Cost", "Profit & Loss > Interest & Finance Charges"),
            new NomenclatureAlias("Profit Before Tax", "Profit & Loss > PBT"),
            new NomenclatureAlias("Income Tax", "Profit & Loss > Tax"),
            new NomenclatureAlias("Profit After Tax", "Profit & Loss > PAT"),
            new NomenclatureAlias("Net Profit", "Profit & Loss > PAT"),
            new NomenclatureAlias("Fixed Assets", "Balance Sheet > Net Block"),
            new NomenclatureAlias("Cash and Bank Balance", "Balance Sheet > Cash & Bank Balance"),
            new NomenclatureAlias("Total Borrowings", "Balance Sheet > Total Debt"),
            new NomenclatureAlias("Tangible Net Worth", "Balance Sheet > Net Worth"),
            new NomenclatureAlias("Cash from Operations", "Cash Flow > Cash Flow from Operations"),
            new NomenclatureAlias("Net Cash from Operating Activities", "Cash Flow > Cash Flow from Operations"),
            new NomenclatureAlias("Net Cash from Investing Activities", "Cash Flow > Cash Flow from Investing"),
            new NomenclatureAlias("Net Cash from Financing Activities", "Cash Flow > Cash Flow from Financing"),
            new NomenclatureAlias("EBITDA Margin (before LF)", "Key Ratios > Profitability > EBITDA Margin"),
            new NomenclatureAlias("EBITDA Margin (after LF)", "Key Ratios > Profitability > EBITDA Margin"),
            new NomenclatureAlias("ROCE", "Key Ratios > Profitability > Return on Capital Employed"),
            new NomenclatureAlias("ROCE (EBIT/ Capital Employed)",
                    "Key Ratios > Profitability > Return on Capital Employed"),
            new NomenclatureAlias("ROE", "Key Ratios > Profitability > Return on Equity"),
            new NomenclatureAlias("Debt Equity (in times)", "Key Ratios > Leverage > Debt Equity"),
            new NomenclatureAlias("Debt Equity Ratio", "Key Ratios > Leverage > Debt Equity"),
            new NomenclatureAlias("TOL/TNW", "Key Ratios > Leverage > Debt Equity"),
            new NomenclatureAlias("Debt Service Coverage Ratio", "Key Ratios > Coverage > DSCR"),
            new NomenclatureAlias("Interest Coverage Ratio", "Key Ratios > Coverage > Interest Coverage"),
            new NomenclatureAlias("ICR", "Key Ratios > Coverage > Interest Coverage"));

    static final String HOTEL = "hotel";

    public static final List<String> HOTEL_LEAVES = List.of(
            "Project Cost > Plant & Machinery > Elevator / Lift",
            "Project Cost > Plant & Machinery > Kitchen Equipments",
            "Project Cost > Plant & Machinery > Wastewater / ETP",
            "Project Cost > Plant & Machinery > Air Conditioning",
            "Profit & Loss > F & B Sales");

    public static final List<NomenclatureAlias> HOTEL_ALIASES = List.of(
            new NomenclatureAlias("Elevator",
                    "Project Cost > Plant & Machinery > Elevator / Lift"),
            new NomenclatureAlias("Lift",
                    "Project Cost > Plant & Machinery > Elevator / Lift"),
            new NomenclatureAlias("Kitchen Equipment",
                    "Project Cost > Plant & Machinery > Kitchen Equipments"),
            new NomenclatureAlias("Wastewater Engineering Services",
                    "Project Cost > Plant & Machinery > Wastewater / ETP"),
            new NomenclatureAlias("Less : AC as per Quotation included Below",
                    "Project Cost > Plant & Machinery > Air Conditioning"),
            new NomenclatureAlias("AC",
                    "Project Cost > Plant & Machinery > Air Conditioning"),
            new NomenclatureAlias("F&B Sales",
                    "Profit & Loss > F & B Sales"),
            new NomenclatureAlias("Food & Beverage Sales",
                    "Profit & Loss > F & B Sales"));

    private NomenclatureSeed() {}

    public static String nameOf(String path) {
        int sep = path.lastIndexOf(" > ");
        return sep < 0 ? path : path.substring(sep + 3);
    }

    public static String parentOf(String path) {
        int sep = path.lastIndexOf(" > ");
        return sep < 0 ? null : path.substring(0, sep);
    }

    static boolean frozen(String path) {
        return path.equals("Project Cost")
                || path.startsWith("Project Cost > ")
                || path.equals("Means of Finance")
                || path.startsWith("Means of Finance > ");
    }
}
