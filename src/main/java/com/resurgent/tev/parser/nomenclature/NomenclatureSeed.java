package com.resurgent.tev.parser.nomenclature;

import java.util.List;

/**
 * Om Arham bank-facing heads as the first frozen global spine: CapEx + Means of
 * Finance + working-capital margin, with a thin P&amp;L / BS / CF top level.
 * Hotel leaves and aliases sit in the industry pack, not the frozen spine.
 */
final class NomenclatureSeed {

    static final List<String> SPINE_PATHS = List.of(
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
            "Balance Sheet",
            "Cash Flow");

    static final List<NomenclatureAlias> SPINE_ALIASES = List.of(
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
                    "Means of Finance > Promoter / Partners' Capital"));

    static final String HOTEL = "hotel";

    static final List<String> HOTEL_LEAVES = List.of(
            "Project Cost > Plant & Machinery > Elevator / Lift",
            "Project Cost > Plant & Machinery > Kitchen Equipments",
            "Project Cost > Plant & Machinery > Wastewater / ETP",
            "Project Cost > Plant & Machinery > Air Conditioning",
            "Profit & Loss > F & B Sales");

    static final List<NomenclatureAlias> HOTEL_ALIASES = List.of(
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

    static String nameOf(String path) {
        int sep = path.lastIndexOf(" > ");
        return sep < 0 ? path : path.substring(sep + 3);
    }

    static String parentOf(String path) {
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
