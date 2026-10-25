package com.resurgent.tev.doc.pack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pack codes that must keep resolving after a rename. The shipped net-worth
 * certificate code is {@code identity.net_worth_certificate}; documents and
 * checklists that still say {@code identity.promoter_net_worth} are the same pack.
 */
public final class PackCodes {

    public static final String NET_WORTH = "identity.net_worth_certificate";

    public static final String NET_WORTH_LEGACY = "identity.promoter_net_worth";

    /** Pack code of a project report. The spine checklist still says {@code project.dpr}. */
    public static final String PROJECT_REPORT = "report.dpr";

    /** Checklist spelling of {@link #PROJECT_REPORT}. The same document. */
    public static final String PROJECT_REPORT_LEGACY = "project.dpr";

    private static final Map<String, String> CANONICAL_OF;
    private static final Map<String, List<String>> EQUIVALENTS_OF;

    static {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put(NET_WORTH, List.of(NET_WORTH, NET_WORTH_LEGACY));
        groups.put(PROJECT_REPORT, List.of(PROJECT_REPORT, PROJECT_REPORT_LEGACY));
        Map<String, String> canonical = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            for (String alias : group.getValue()) {
                canonical.put(alias, group.getKey());
            }
        }
        CANONICAL_OF = Map.copyOf(canonical);
        EQUIVALENTS_OF = Map.copyOf(groups);
    }

    private PackCodes() {}

    /** The code stored on the seed pack. A legacy alias maps to that code. */
    public static String canonical(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        return CANONICAL_OF.getOrDefault(trimmed, trimmed);
    }

    /** True when both strings name the same pack, including the net-worth alias. */
    public static boolean same(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return canonical(left).equals(canonical(right));
    }

    /** Every spelling that should hit the same pack row. */
    public static List<String> equivalents(String code) {
        String canonical = canonical(code);
        if (canonical == null) {
            return List.of();
        }
        List<String> group = EQUIVALENTS_OF.get(canonical);
        return group != null ? group : List.of(code);
    }
}
