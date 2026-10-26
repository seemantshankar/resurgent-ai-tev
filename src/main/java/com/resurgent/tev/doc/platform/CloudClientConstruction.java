package com.resurgent.tev.doc.platform;

import java.util.ArrayList;
import java.util.List;

/**
 * Records cloud-client construction for the offline drill. Production
 * constructors call {@link #constructed(String)}. The drill watches the
 * calling thread and fails when the list is not empty. A thread that is not
 * watching is unaffected.
 */
public final class CloudClientConstruction {

    private static final ThreadLocal<List<String>> SEEN = new ThreadLocal<>();

    private CloudClientConstruction() {}

    /** Starts a fresh list on this thread. */
    public static void watch() {
        SEEN.set(new ArrayList<>());
    }

    /** Drops this thread's list. */
    public static void clear() {
        SEEN.remove();
    }

    /** Clients constructed on this thread since {@link #watch()}, or empty when not watching. */
    public static List<String> seen() {
        List<String> seen = SEEN.get();
        return seen == null ? List.of() : List.copyOf(seen);
    }

    /** Notes one constructed cloud client when this thread is watching. */
    public static void constructed(String client) {
        List<String> seen = SEEN.get();
        if (seen != null) {
            seen.add(client);
        }
    }
}
