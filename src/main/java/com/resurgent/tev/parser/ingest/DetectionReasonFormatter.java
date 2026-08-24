package com.resurgent.tev.parser.ingest;

import java.util.Map;
import java.util.StringJoiner;

/** Renders persisted, text-free detection reasons for human-facing read paths. */
public final class DetectionReasonFormatter {

    private DetectionReasonFormatter() {}

    public static String format(DetectionReason reason) {
        StringJoiner parameters = new StringJoiner(", ");
        reason.params().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> parameters.add(entry.getKey() + "=" + entry.getValue()));
        return reason.code().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                + " (weight " + signed(reason.weight())
                + (reason.params().isEmpty() ? ")" : "; " + parameters + ")");
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }
}
