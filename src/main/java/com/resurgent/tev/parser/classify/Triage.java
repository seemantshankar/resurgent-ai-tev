package com.resurgent.tev.parser.classify;

/** Scratch / Orphan / main-line triage on a Packet. */
public final class Triage {

    public static final String MAIN = "main";
    public static final String SCRATCH = "scratch";
    public static final String ORPHAN = "orphan";

    private Triage() {}

    public static boolean isKnown(String value) {
        return MAIN.equals(value) || SCRATCH.equals(value) || ORPHAN.equals(value);
    }

    /** Scratch and Orphan are soft-triage: downstream defaults treat them as noise. */
    public static boolean isSoft(String value) {
        return SCRATCH.equals(value) || ORPHAN.equals(value);
    }
}
