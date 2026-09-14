package com.resurgent.tev.parser.classify;

/** How a bound money amount participates economically on a Layer B line. */
public final class AmountRole {

    public static final String ADD = "add";
    public static final String DEDUCT = "deduct";
    public static final String TOTAL = "total";
    public static final String HELPER = "helper";

    private AmountRole() {}

    public static boolean isKnown(String value) {
        return ADD.equals(value)
                || DEDUCT.equals(value)
                || TOTAL.equals(value)
                || HELPER.equals(value);
    }
}
