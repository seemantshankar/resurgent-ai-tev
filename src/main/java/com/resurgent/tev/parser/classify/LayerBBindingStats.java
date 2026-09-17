package com.resurgent.tev.parser.classify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Counters for Layer B materialization: proposed vs accepted vs rejected vs
 * duplicate, leaf-selection outcomes, and sample rejection reasons for live reports.
 */
public final class LayerBBindingStats {

    private int proposed;
    private int accepted;
    private int rejected;
    private int duplicate;
    private int failedCalls;
    private int catalogPreferred;
    private int softGenericKept;
    private int leafAmbiguous;
    private int deterministic;
    private final Map<String, Integer> rejectReasons = new LinkedHashMap<>();
    private final List<String> rejectSamples = new ArrayList<>();
    private final List<String> failedCallSamples = new ArrayList<>();

    public void addProposed(int count) {
        proposed += count;
    }

    public void addAccepted() {
        accepted++;
    }

    public void addDuplicate() {
        duplicate++;
    }

    /** Soft/generic proposal replaced by an unambiguous hard catalog leaf from evidence. */
    public void addCatalogPreferred() {
        catalogPreferred++;
    }

    /** Soft leaf kept because evidence did not uniquely name a hard catalog leaf. */
    public void addSoftGenericKept() {
        softGenericKept++;
    }

    /** Soft leaf kept because evidence named more than one hard catalog leaf. */
    public void addLeafAmbiguous() {
        leafAmbiguous++;
    }

    /** Bindings from one group-level answer applied across every cell sharing a label. */
    public void addLabelBindings(int count) {
        accepted += count;
    }

    /** Bindings the cell graph proved without asking anything. */
    public void addDeterministic(int count) {
        deterministic += count;
    }

    public int deterministic() {
        return deterministic;
    }

    /** A Layer B call or chunk the model never answered usably; its lines are lost. */
    public void addFailedCall(String reason) {
        failedCalls++;
        if (failedCallSamples.size() < 12) {
            failedCallSamples.add(reason == null || reason.isBlank() ? "unknown" : reason);
        }
    }

    public void addRejected(String reason) {
        rejected++;
        String key = reason == null || reason.isBlank() ? "unknown" : shorten(reason);
        rejectReasons.merge(key, 1, Integer::sum);
        if (rejectSamples.size() < 12) {
            rejectSamples.add(key);
        }
    }

    public int proposed() {
        return proposed;
    }

    public int accepted() {
        return accepted;
    }

    public int rejected() {
        return rejected;
    }

    public int duplicate() {
        return duplicate;
    }

    public int catalogPreferred() {
        return catalogPreferred;
    }

    public int softGenericKept() {
        return softGenericKept;
    }

    public int leafAmbiguous() {
        return leafAmbiguous;
    }

    public Map<String, Integer> rejectReasons() {
        return Map.copyOf(rejectReasons);
    }

    public List<String> rejectSamples() {
        return List.copyOf(rejectSamples);
    }

    public int failedCalls() {
        return failedCalls;
    }

    public List<String> failedCallSamples() {
        return List.copyOf(failedCallSamples);
    }

    public String summaryLine() {
        return "layerB proposed=" + proposed
                + " accepted=" + accepted
                + " rejected=" + rejected
                + " duplicate=" + duplicate
                + " failedCalls=" + failedCalls
                + " catalogPreferred=" + catalogPreferred
                + " softGenericKept=" + softGenericKept
                + " leafAmbiguous=" + leafAmbiguous
                + " deterministic=" + deterministic
                + " reasons=" + formatRejectReasons();
    }

    /** Plain-text tallies for CLI output (no map/JSON braces). */
    private String formatRejectReasons() {
        if (rejectReasons.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Integer> entry : rejectReasons.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        return sb.toString();
    }

    private static String shorten(String reason) {
        String trimmed = Objects.requireNonNull(reason).replaceAll("\\s+", " ").trim();
        if (trimmed.startsWith("non_money_numeric")) {
            return "non_money_cost_role";
        }
        if (trimmed.startsWith("Layer B binding requires an amount cell")) {
            return "non_amount_or_formula_role";
        }
        if (trimmed.startsWith("Layer B coord not in Packet")) {
            return "coord_not_in_packet";
        }
        if (trimmed.startsWith("invalid amount_role")) {
            return "invalid_amount_role";
        }
        if (trimmed.startsWith("Layer B path must be a leaf")) {
            return "path_not_leaf";
        }
        if (trimmed.startsWith("cannot invent mid-level")) {
            return "invented_mid_level";
        }
        if (trimmed.contains("formula") && trimmed.contains("role")) {
            return "formula_role_mismatch";
        }
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80);
    }
}
