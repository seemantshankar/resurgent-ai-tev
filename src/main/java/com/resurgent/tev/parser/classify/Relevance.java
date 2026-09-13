package com.resurgent.tev.parser.classify;

/** How much weight downstream tools should give a Packet. */
public final class Relevance {

    public static final String PRIMARY = "primary";
    public static final String SUPPORTING = "supporting";
    public static final String NOISE = "noise";

    private Relevance() {}

    public static boolean isKnown(String value) {
        return PRIMARY.equals(value) || SUPPORTING.equals(value) || NOISE.equals(value);
    }
}
