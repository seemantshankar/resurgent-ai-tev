package com.resurgent.tev.parser;

/**
 * Coarse phase progress for long parse runs, written to stderr.
 *
 * <p>A large workbook can spend many minutes inside one phase with nothing printed
 * until it finishes, which is indistinguishable from a hang. Progress is deliberately
 * coarse — phase boundaries, per-worksheet and every-nth-Candidate counts, never
 * per Cell — so reporting can never itself become a cost on a hot path.
 *
 * <p>Silence it with {@code -Dtev.progress=off}.
 */
public final class Progress {

    private static final boolean ENABLED =
            !"off".equalsIgnoreCase(System.getProperty("tev.progress", "on"));

    private Progress() {}

    /** A phase boundary, e.g. {@code phase("classify", "building packets for 312 candidates")}. */
    public static void phase(String phase, String detail) {
        if (!ENABLED) {
            return;
        }
        System.err.println("[" + phase + "] " + detail);
        System.err.flush();
    }

    /**
     * A counted step, emitted only every {@code every} items and on the final item, so a
     * loop over thousands of items prints a bounded number of lines.
     */
    public static void step(String phase, String what, int done, int total, int every) {
        if (!ENABLED || (done % every != 0 && done != total)) {
            return;
        }
        System.err.println("[" + phase + "] " + what + " " + done + "/" + total);
        System.err.flush();
    }
}
