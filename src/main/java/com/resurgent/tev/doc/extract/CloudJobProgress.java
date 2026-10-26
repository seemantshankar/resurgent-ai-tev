package com.resurgent.tev.doc.extract;

/**
 * Line-oriented progress on stderr for long cloud parse/extract jobs.
 * Set {@code TEV_QUIET=1} to suppress (tests, CI).
 */
public final class CloudJobProgress {

    /** Env var: {@code 1} or {@code true} disables progress lines. */
    public static final String ENV_QUIET = "TEV_QUIET";

    private CloudJobProgress() {}

    public static boolean quiet() {
        String v = System.getenv(ENV_QUIET);
        return v != null && ("1".equals(v.trim()) || "true".equalsIgnoreCase(v.trim()));
    }

    /** One-shot status (upload started, pass A, etc.). */
    public static void note(String message) {
        if (quiet()) {
            return;
        }
        System.err.println(message);
        System.err.flush();
    }

    /** Poll heartbeat while a job is non-terminal. */
    public static void pollStatus(String product, String jobId, String status, long elapsedMs) {
        if (quiet()) {
            return;
        }
        long sec = Math.max(0L, elapsedMs / 1000L);
        System.err.printf("%s job %s … %s (%ds)%n", product, jobId, status, sec);
        System.err.flush();
    }
}
