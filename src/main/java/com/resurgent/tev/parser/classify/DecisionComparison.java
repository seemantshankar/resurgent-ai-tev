package com.resurgent.tev.parser.classify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Shadow comparison of the decision model against the chat model on the same cells: how often
 * they agree, by the decision model's confidence. Used to pick a safe confidence threshold
 * before letting the decision model settle cells on its own.
 */
final class DecisionComparison {

    /** {@code chatKind} and {@code chatScale} are null when the chat model left the cell untyped. */
    record Row(long cellId, String coord, String rowLabel, String columnLabel,
            CellDecisionClient.Decision decision,
            String chatKind, String chatScale,
            String sheet, boolean hasRegion, String statedScale) {
        Row(long cellId, String coord, String rowLabel, String columnLabel,
                CellDecisionClient.Decision decision, String chatKind, String chatScale) {
            this(cellId, coord, rowLabel, columnLabel, decision, chatKind, chatScale, "", false, "");
        }

        boolean chatSettled() {
            return chatKind != null;
        }

        boolean kindAgrees() {
            return decision.kind().equals(chatKind);
        }

        /** Scale only means something for money; for other kinds both models say "unit". */
        boolean scaleMatters() {
            return ReadingOutcome.MONEY.equals(chatKind);
        }

        boolean scaleAgrees() {
            return decision.scale() != null && decision.scale().equals(chatScale);
        }
    }

    private static final double[] EDGES = {0.0, 0.5, 0.7, 0.8, 0.9, 0.95, 0.99, 1.0001};
    private static final double[] CUTS = {0.5, 0.7, 0.8, 0.9, 0.95, 0.99};

    private final List<Row> rows;

    DecisionComparison(List<Row> rows) {
        this.rows = List.copyOf(rows);
    }

    String report() {
        List<Row> settled = rows.stream().filter(Row::chatSettled).toList();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[d1-compare] %d cells compared; chat left %d untyped (excluded below)%n",
                rows.size(), rows.size() - settled.size()));
        sb.append("[d1-compare] by D1 confidence band (min of kind and scale confidence):\n");
        sb.append(String.format("[d1-compare] %-12s %6s %8s %8s %8s%n", "band", "cells", "kind=", "scale=*", "both=*"));
        for (int b = 0; b < EDGES.length - 1; b++) {
            final double lo = EDGES[b];
            final double hi = EDGES[b + 1];
            sb.append(line(String.format(Locale.ROOT, "%.2f-%.2f", lo, Math.min(hi, 1.0)),
                    settled.stream().filter(r -> r.decision().confidence() >= lo && r.decision().confidence() < hi).toList()));
        }
        sb.append("[d1-compare] if D1 settled every cell at or above a threshold:\n");
        sb.append(String.format("[d1-compare] %-12s %6s %8s %8s %8s%n", "threshold", "cells", "kind=", "scale=*", "both=*"));
        for (double cut : CUTS) {
            sb.append(line(String.format(Locale.ROOT, ">= %.2f", cut),
                    settled.stream().filter(r -> r.decision().confidence() >= cut).toList()));
        }
        sb.append("[d1-compare] (* scale compared only where chat typed the cell as money)\n");
        sb.append("[d1-compare] most common kind disagreements (D1 -> chat):\n");
        Map<String, Integer> pairs = new TreeMap<>();
        for (Row r : settled) {
            if (!r.kindAgrees()) {
                pairs.merge(r.decision().kind() + " -> " + r.chatKind(), 1, Integer::sum);
            }
        }
        pairs.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(8)
                .forEach(e -> sb.append(String.format("[d1-compare]   %4d  %s%n", e.getValue(), e.getKey())));
        return sb.toString();
    }

    private static String line(String label, List<Row> group) {
        if (group.isEmpty()) {
            return String.format("[d1-compare] %-12s %6d %8s %8s %8s%n", label, 0, "-", "-", "-");
        }
        long kind = group.stream().filter(Row::kindAgrees).count();
        List<Row> money = group.stream().filter(Row::scaleMatters).toList();
        long scale = money.stream().filter(Row::scaleAgrees).count();
        long both = group.stream().filter(r -> r.kindAgrees() && (!r.scaleMatters() || r.scaleAgrees())).count();
        return String.format(Locale.ROOT, "[d1-compare] %-12s %6d %7.1f%% %8s %7.1f%%%n", label, group.size(),
                100.0 * kind / group.size(),
                money.isEmpty() ? "-" : String.format(Locale.ROOT, "%.1f%%", 100.0 * scale / money.size()),
                100.0 * both / group.size());
    }

    void writeCsv(Path path) throws IOException {
        boolean fresh = !Files.exists(path); // the classifier runs twice per parse; the second call appends
        List<String> lines = new ArrayList<>();
        if (fresh) {
            lines.add("cell_id,coord,row_label,column_label,d1_kind,d1_kind_conf,d1_scale,d1_scale_conf,chat_kind,chat_scale,sheet,has_region,stated_scale,d1_kind_top2");
        }
        for (Row r : rows) {
            lines.add(String.join(",",
                    Long.toString(r.cellId()), csv(r.coord()), csv(r.rowLabel()), csv(r.columnLabel()),
                    r.decision().kind(), Double.toString(r.decision().kindConfidence()),
                    r.decision().scale(), Double.toString(r.decision().scaleConfidence()),
                    r.chatKind() == null ? "" : r.chatKind(), r.chatScale() == null ? "" : r.chatScale(),
                    csv(r.sheet()), Boolean.toString(r.hasRegion()), r.statedScale() == null ? "" : r.statedScale(),
                    csv(topTwo(r.decision().kindProbabilities()))));
        }
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.write(path, lines, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    /** "money:0.93|rate:0.05": the two likeliest kinds, so a near miss shows what it was close to. */
    private static String topTwo(Map<String, Double> probabilities) {
        return probabilities.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(2)
                .map(e -> e.getKey() + ":" + String.format(Locale.ROOT, "%.2f", e.getValue()))
                .collect(java.util.stream.Collectors.joining("|"));
    }

    private static String csv(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\"";
    }
}
