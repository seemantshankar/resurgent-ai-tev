package com.resurgent.tev.parser.classify;

import java.util.Locale;

/** Why two amount bindings are linked. Only {@link #ANTI_DOUBLE_COUNT} is supported initially. */
public final class PeerReason {

    public static final String ANTI_DOUBLE_COUNT = "anti_double_count";

    private PeerReason() {}

    public static boolean isKnown(String reason) {
        return ANTI_DOUBLE_COUNT.equals(normalize(reason));
    }

    static String normalize(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return reason.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
