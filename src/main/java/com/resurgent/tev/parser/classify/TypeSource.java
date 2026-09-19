package com.resurgent.tev.parser.classify;

/** Where a cell's type came from. */
public final class TypeSource {

    /** Typed from the cell's own row label or number format. */
    public static final String INPUT_LABEL = "input_label";
    /** Typed by dimensional arithmetic over the cell's formula operands. */
    public static final String PROPAGATED = "propagated";
    /** Typed from the aggregation it heads. */
    public static final String AGGREGATION = "aggregation";

    private TypeSource() {}

    public static boolean isKnown(String value) {
        return INPUT_LABEL.equals(value)
                || PROPAGATED.equals(value)
                || AGGREGATION.equals(value);
    }
}
