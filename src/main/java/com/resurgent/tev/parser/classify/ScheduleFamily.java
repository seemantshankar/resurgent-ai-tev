package com.resurgent.tev.parser.classify;

/**
 * Schedule family for Layer A. The seven names are the seed. A run may admit a
 * new short category when none of the seed fits, and later packets are offered
 * that name.
 */
public final class ScheduleFamily {

    public static final String CAPEX_DETAIL = "capex_detail";
    public static final String MEANS_OF_FINANCE = "means_of_finance";
    public static final String PROFIT_AND_LOSS = "profit_and_loss";
    public static final String BALANCE_SHEET = "balance_sheet";
    public static final String CASH_FLOW = "cash_flow";
    public static final String ASSUMPTIONS = "assumptions";
    public static final String PROJECT_SUMMARY = "project_summary";
    /** The model uses this when no listed family fits and supplies a new name. */
    public static final String NONE = "none";

    private ScheduleFamily() {}

    public static java.util.List<String> seeds() {
        return java.util.List.of(
                CAPEX_DETAIL,
                MEANS_OF_FINANCE,
                PROFIT_AND_LOSS,
                BALANCE_SHEET,
                CASH_FLOW,
                ASSUMPTIONS,
                PROJECT_SUMMARY);
    }

    /**
     * A new category name: a short snake_case slug, not one of the seed families
     * and not {@code none}. Anything else is refused.
     */
    public static String newFamily(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toLowerCase(java.util.Locale.ROOT)
                .replace('&', ' ')
                .replace('-', '_')
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        if (key.isBlank() || NONE.equals(key) || isKnown(key)) {
            return null;
        }
        if (!key.matches("[a-z][a-z0-9_]{1,31}")) {
            return null;
        }
        int underscores = 0;
        for (int i = 0; i < key.length(); i++) {
            if (key.charAt(i) == '_') {
                underscores++;
            }
        }
        return underscores <= 3 ? key : null;
    }

    public static boolean isKnown(String value) {
        return CAPEX_DETAIL.equals(value)
                || MEANS_OF_FINANCE.equals(value)
                || PROFIT_AND_LOSS.equals(value)
                || BALANCE_SHEET.equals(value)
                || CASH_FLOW.equals(value)
                || ASSUMPTIONS.equals(value)
                || PROJECT_SUMMARY.equals(value);
    }
}
