package com.resurgent.tev.doc.residency;

/**
 * Mandate data residency. {@code local_only} keeps reading, extraction, and
 * similarity on this machine. {@code cloud_allowed} is the default.
 *
 * <p>{@code TEV_DATA_RESIDENCY=local_only} is a process-wide floor: it overrides
 * a database row that still says {@code cloud_allowed}. An unset variable does
 * not force a mode; a missing policy row stays {@code cloud_allowed}.
 */
public final class DataResidency {

    public static final String ENV = "TEV_DATA_RESIDENCY";

    /** Request option carrying the residency already resolved for this call. */
    public static final String OPTION = "data_residency";

    public static final String CLOUD_ALLOWED = "cloud_allowed";

    public static final String LOCAL_ONLY = "local_only";

    private DataResidency() {}

    public static boolean isLocalOnly(String value) {
        return value != null && LOCAL_ONLY.equalsIgnoreCase(value.trim());
    }

    /**
     * Effective mode. The environment floor wins over a more permissive row.
     * Anything other than {@code local_only} on either side leaves the row, or
     * {@code cloud_allowed} when the row is absent.
     */
    public static String effective(String envValue, String mandateRow) {
        if (isLocalOnly(envValue)) {
            return LOCAL_ONLY;
        }
        if (isLocalOnly(mandateRow)) {
            return LOCAL_ONLY;
        }
        return CLOUD_ALLOWED;
    }

    /**
     * True when the process floor or any explicit value is {@code local_only}.
     * Adapters call this before opening a socket.
     */
    public static boolean locked(String... extras) {
        if (isLocalOnly(System.getenv(ENV))) {
            return true;
        }
        if (extras == null) {
            return false;
        }
        for (String extra : extras) {
            if (isLocalOnly(extra)) {
                return true;
            }
        }
        return false;
    }
}
