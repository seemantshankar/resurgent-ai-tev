package com.resurgent.tev.parser.ingest;

import com.resurgent.tev.parser.config.RegionWeights;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure, deterministic region scorer. It intentionally has no persistence or ingestion dependency:
 * callers supply the already detected cells, bounds, and header context.
 */
public final class RegionClassifier {
    private static final Set<String> HEADER_TOKENS = Set.of(
            "year", "yr", "fy", "construction", "particulars", "sl.no", "amount", "rate", "qty");
    private final RegionWeights weights;
    private final int evidenceFloor;

    public RegionClassifier() {
        this(RegionWeights.defaults(), 3);
    }

    public RegionClassifier(RegionWeights weights, int evidenceFloor) {
        this.weights = Objects.requireNonNull(weights, "weights");
        if (evidenceFloor < 1) {
            throw new IllegalArgumentException("evidenceFloor must be positive");
        }
        this.evidenceFloor = evidenceFloor;
    }

    public RegionClassification classify(RegionBounds bounds, List<RegionCell> cells, HeaderContext headers) {
        Objects.requireNonNull(bounds, "bounds");
        cells = Objects.requireNonNull(cells, "cells").stream()
                .sorted(Comparator.comparingInt(RegionCell::row).thenComparingInt(RegionCell::col))
                .toList();
        Objects.requireNonNull(headers, "headers");

        Map<RegionType, Integer> scores = new EnumMap<>(RegionType.class);
        Map<RegionType, List<DetectionReason>> reasons = new EnumMap<>(RegionType.class);
        for (RegionType type : RegionType.values()) {
            scores.put(type, 0);
            reasons.put(type, new ArrayList<>());
        }

        List<String> labels = labels(cells, headers);
        List<VerticalFormLayout.Cell> layout = layoutCells(cells);
        boolean numberedForm = VerticalFormLayout.isNumberedKeyValueForm(layout);
        if (numberedForm) {
            scoreVerticalForm(bounds, cells, scores, reasons, true);
        } else {
            scoreCostHead(cells, headers, scores, reasons);
            scoreHeaderTokens(headers, scores, reasons);
            scoreStatementShape(labels, bounds, scores, reasons);
            scoreVerticalForm(bounds, cells, scores, reasons, false);
            scoreScratch(headers, cells, scores, reasons);
            scoreSerialPattern(bounds, cells, scores, reasons);
        }

        List<RegionType> ranked = scores.entrySet().stream()
                .filter(e -> e.getKey() != RegionType.UNKNOWN)
                .sorted(Map.Entry.<RegionType, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(e -> e.getKey().ordinal()))
                .map(Map.Entry::getKey)
                .toList();
        RegionType top = ranked.getFirst();
        int topScore = scores.get(top);
        int runnerUp = scores.get(ranked.get(1));
        if (topScore < evidenceFloor) {
            return new RegionClassification(RegionType.UNKNOWN, 0.0,
                    List.of(new DetectionReason(DetectionReason.Code.INSUFFICIENT_EVIDENCE, 0,
                            Map.of("top_score", (long) topScore))), null);
        }
        double confidence = Math.clamp((double) (topScore - runnerUp) / topScore, 0.0, 1.0);
        String code = top == RegionType.COST_HEAD ? matchedCostHead(cells, headers).orElseThrow() : null;
        return new RegionClassification(top, confidence, orderedReasons(reasons.get(top)), code);
    }

    private void scoreCostHead(List<RegionCell> cells, HeaderContext headers,
            Map<RegionType, Integer> scores, Map<RegionType, List<DetectionReason>> reasons) {
        Optional<String> code = matchedCostHead(cells, headers);
        if (code.isEmpty()) {
            return;
        }
        add(scores, reasons, RegionType.COST_HEAD, weights.classification("costHeadAlias"),
                DetectionReason.Code.COST_HEAD_ALIAS, Map.of("match_count", 1L));
    }

    private static Optional<String> matchedCostHead(List<RegionCell> cells, HeaderContext headers) {
        return java.util.stream.Stream.concat(headers.labels().stream(), cells.stream().map(RegionCell::text))
                .filter(Objects::nonNull)
                .filter(label -> !isAmbiguousCostHeadAlias(label))
                .map(CostHeadVocabulary::exactMatch)
                .flatMap(Optional::stream)
                .sorted()
                .findFirst();
    }

    private static boolean isAmbiguousCostHeadAlias(String label) {
        String normalized = label.strip().toLowerCase(Locale.ROOT);
        return normalized.equals("equipment") || normalized.equals("miscellaneous");
    }

    private void scoreHeaderTokens(HeaderContext headers, Map<RegionType, Integer> scores,
            Map<RegionType, List<DetectionReason>> reasons) {
        Set<String> tokens = headers.labels().stream().flatMap(RegionClassifier::words).collect(java.util.stream.Collectors.toSet());
        int matches = (int) tokens.stream().filter(HEADER_TOKENS::contains).count();
        if (matches == 0) {
            return;
        }
        Map<String, Long> params = Map.of("token_count", (long) matches, "header_row_count", (long) headers.rows().size());
        if (tokens.contains("year") || tokens.contains("yr") || tokens.contains("fy")) {
            add(scores, reasons, RegionType.TIMELINE, weights.classification("headerToken"), DetectionReason.Code.HEADER_TOKEN, params);
        }
        if (tokens.contains("qty") || tokens.contains("rate") || tokens.contains("amount")) {
            add(scores, reasons, RegionType.VENDOR_BLOCK, weights.classification("headerToken"), DetectionReason.Code.HEADER_TOKEN, params);
        }
    }

    private void scoreStatementShape(List<String> labels, RegionBounds bounds,
            Map<RegionType, Integer> scores, Map<RegionType, List<DetectionReason>> reasons) {
        if (containsAll(labels, "assets", "liabilities")) {
            add(scores, reasons, RegionType.BS, weights.classification("statementShape"), DetectionReason.Code.STATEMENT_SHAPE,
                    Map.of("row_span", (long) bounds.rowSpan()));
        }
        if (containsAny(labels, "revenue", "income") && containsAny(labels, "expense", "expenditure")) {
            add(scores, reasons, RegionType.PNL, weights.classification("statementShape"), DetectionReason.Code.STATEMENT_SHAPE,
                    Map.of("row_span", (long) bounds.rowSpan()));
        }
        if (containsAny(labels, "inflow", "cash inflow") && containsAny(labels, "outflow", "cash outflow")) {
            add(scores, reasons, RegionType.CASH_FLOW, weights.classification("statementShape"), DetectionReason.Code.STATEMENT_SHAPE,
                    Map.of("row_span", (long) bounds.rowSpan()));
        }
        if (containsAny(labels, "debt", "loan") && containsAny(labels, "month", "months")) {
            add(scores, reasons, RegionType.DEBT_SCHEDULE, weights.classification("statementShape"), DetectionReason.Code.STATEMENT_SHAPE,
                    Map.of("row_span", (long) bounds.rowSpan()));
        }
        if (containsAny(labels, "capacity", "production")) {
            add(scores, reasons, RegionType.CAPACITY, weights.classification("statementShape"), DetectionReason.Code.STATEMENT_SHAPE,
                    Map.of("row_span", (long) bounds.rowSpan()));
        }
        if (containsAny(labels, "power", "water", "utility")) {
            add(scores, reasons, RegionType.UTILITY, weights.classification("statementShape"), DetectionReason.Code.STATEMENT_SHAPE,
                    Map.of("row_span", (long) bounds.rowSpan()));
        }
    }

    private void scoreVerticalForm(RegionBounds bounds, List<RegionCell> cells,
            Map<RegionType, Integer> scores, Map<RegionType, List<DetectionReason>> reasons,
            boolean numberedForm) {
        long text = cells.stream().filter(c -> c.text() != null && !c.text().isBlank()).count();
        long numeric = cells.stream().filter(RegionCell::numeric).count();
        boolean thin = bounds.colSpan() <= 2 && text >= 2 && numeric >= 1;
        if (thin || numberedForm) {
            add(scores, reasons, RegionType.VERTICAL_FORM, weights.classification("verticalForm"),
                    DetectionReason.Code.VERTICAL_FORM, Map.of("column_span", (long) bounds.colSpan()));
        }
    }

    private static List<VerticalFormLayout.Cell> layoutCells(List<RegionCell> cells) {
        List<VerticalFormLayout.Cell> result = new ArrayList<>(cells.size());
        for (RegionCell cell : cells) {
            result.add(new VerticalFormLayout.Cell(
                    cell.row(), cell.col(), cell.text(), cell.numeric(), cell.formula()));
        }
        return result;
    }

    private void scoreScratch(HeaderContext headers, List<RegionCell> cells,
            Map<RegionType, Integer> scores, Map<RegionType, List<DetectionReason>> reasons) {
        long formulas = cells.stream().filter(RegionCell::formula).count();
        long labels = cells.stream().filter(c -> c.text() != null && !c.text().isBlank()).count();
        if (headers.labels().isEmpty() && formulas > 0 && labels == 0) {
            add(scores, reasons, RegionType.SCRATCH, weights.classification("scratchPattern"),
                    DetectionReason.Code.SCRATCH_PATTERN, Map.of("formula_count", formulas));
        }
    }

    private void scoreSerialPattern(RegionBounds bounds, List<RegionCell> cells,
            Map<RegionType, Integer> scores, Map<RegionType, List<DetectionReason>> reasons) {
        int serialColumn = bounds.startCol();
        List<String> values = cells.stream().filter(cell -> cell.col() == serialColumn)
                .map(RegionCell::text).filter(Objects::nonNull).map(String::strip)
                .filter(value -> !value.isEmpty()).toList();
        SerialPattern pattern = serialPattern(values);
        if (pattern == SerialPattern.NONE) {
            return;
        }
        add(scores, reasons, RegionType.VENDOR_BLOCK, weights.classification("serialPattern"),
                DetectionReason.Code.SERIAL_PATTERN,
                Map.of("serial_count", (long) values.size(), "serial_pattern", (long) pattern.code));
    }

    private static SerialPattern serialPattern(List<String> values) {
        if (values.size() < 3) {
            return SerialPattern.NONE;
        }
        List<SerialKind> kinds = values.stream().map(RegionClassifier::serialKind).toList();
        if (kinds.stream().anyMatch(kind -> kind == SerialKind.NONE)) {
            return SerialPattern.NONE;
        }
        boolean allSame = kinds.stream().distinct().count() == 1;
        if (allSame && consecutive(values, kinds.getFirst())) {
            return switch (kinds.getFirst()) {
                case NUMERIC -> SerialPattern.NUMERIC;
                case ALPHA_DOT -> SerialPattern.ALPHA_DOT;
                case ALPHA_DASH -> SerialPattern.ALPHA_DASH;
                case NONE -> SerialPattern.NONE;
            };
        }
        // Mixed serials are accepted only when each contiguous run is itself a sequence,
        // preventing a few arbitrary serial-looking values from becoming evidence.
        int runStart = 0;
        boolean hasTransition = false;
        for (int index = 1; index <= values.size(); index++) {
            if (index == values.size() || kinds.get(index) != kinds.get(runStart)) {
                if (!consecutive(values.subList(runStart, index), kinds.get(runStart))) {
                    return SerialPattern.NONE;
                }
                hasTransition |= index < values.size();
                runStart = index;
            }
        }
        return hasTransition ? SerialPattern.MIXED : SerialPattern.NONE;
    }

    private static SerialKind serialKind(String value) {
        if (value.matches("\\d+[.)]?")) return SerialKind.NUMERIC;
        if (value.matches("(?i)[a-z]+\\.")) return SerialKind.ALPHA_DOT;
        if (value.matches("(?i)[a-z]+-")) return SerialKind.ALPHA_DASH;
        return SerialKind.NONE;
    }

    private static boolean consecutive(List<String> values, SerialKind kind) {
        if (values.size() < 2) {
            return true;
        }
        int previous = serialValue(values.getFirst(), kind);
        for (int index = 1; index < values.size(); index++) {
            int current = serialValue(values.get(index), kind);
            if (current != previous + 1) return false;
            previous = current;
        }
        return true;
    }

    private static int serialValue(String value, SerialKind kind) {
        String token = switch (kind) {
            case NUMERIC -> value.replaceAll("[.)]$", "");
            case ALPHA_DOT, ALPHA_DASH -> value.substring(0, value.length() - 1).toUpperCase(Locale.ROOT);
            case NONE -> throw new IllegalArgumentException("not a serial");
        };
        if (kind == SerialKind.NUMERIC) return Integer.parseInt(token);
        int result = 0;
        for (int index = 0; index < token.length(); index++) result = result * 26 + token.charAt(index) - 'A' + 1;
        return result;
    }

    private enum SerialKind { NUMERIC, ALPHA_DOT, ALPHA_DASH, NONE }
    private enum SerialPattern {
        NUMERIC(1), ALPHA_DOT(2), ALPHA_DASH(3), MIXED(4), NONE(5);
        private final int code;
        SerialPattern(int code) { this.code = code; }
    }

    private static List<String> labels(List<RegionCell> cells, HeaderContext headers) {
        return java.util.stream.Stream.concat(headers.labels().stream(), cells.stream().map(RegionCell::text))
                .filter(Objects::nonNull).map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    private static boolean containsAll(List<String> labels, String... expected) {
        return java.util.Arrays.stream(expected).allMatch(value -> containsAny(labels, value));
    }

    private static boolean containsAny(List<String> labels, String... expected) {
        return labels.stream().anyMatch(label -> java.util.Arrays.stream(expected)
                .anyMatch(value -> words(label).anyMatch(value::equals)));
    }

    private static java.util.stream.Stream<String> words(String text) {
        return java.util.Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9.]+"))
                .filter(token -> !token.isBlank());
    }

    private static void add(Map<RegionType, Integer> scores, Map<RegionType, List<DetectionReason>> reasons,
            RegionType type, int weight, DetectionReason.Code code, Map<String, Long> params) {
        scores.merge(type, weight, Integer::sum);
        reasons.get(type).add(new DetectionReason(code, weight, params));
    }

    private static List<DetectionReason> orderedReasons(List<DetectionReason> reasons) {
        return reasons.stream().sorted(Comparator.comparing(reason -> reason.code().name())).toList();
    }

    public record RegionBounds(int startRow, int endRow, int startCol, int endCol) {
        public RegionBounds {
            if (startRow > endRow || startCol > endCol) {
                throw new IllegalArgumentException("bounds must be ordered");
            }
        }
        public int rowSpan() { return endRow - startRow + 1; }
        public int colSpan() { return endCol - startCol + 1; }
    }

    /** Text belongs here only as transient classifier input; DetectionReason never stores it. */
    public record RegionCell(int row, int col, String text, boolean formula, boolean numeric) {}

    public record HeaderContext(List<Integer> rows, List<String> labels) {
        public HeaderContext {
            rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
            labels = List.copyOf(Objects.requireNonNull(labels, "labels"));
        }
    }
}
