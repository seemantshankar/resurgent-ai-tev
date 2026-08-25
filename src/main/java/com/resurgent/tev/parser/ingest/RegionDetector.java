package com.resurgent.tev.parser.ingest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.apache.poi.ss.util.CellReference;

/**
 * Finds the coarse, geometry-only regions of one worksheet. Classification happens in a
 * later ticket; this detector deliberately emits every component as {@code unknown}.
 */
final class RegionDetector {

    private static final Pattern SKELETON_TOKEN = Pattern.compile("\\$ABS\\$|[A-Za-z_]+|\\d+(?:\\.\\d+)?|\\S");

    record RegionCell(NormalizedCell cell, String formulaSkeleton) {}

    record DetectedRegion(String key, int startRow, int endRow, int startCol, int endCol,
            List<Long> cellIds) {}

    /** Evidence for every horizontal boundary considered by the detector. */
    record BreakCandidate(int cutAfterRow, int score, boolean selected, String rejectionReason) {}

    private record OccupiedCell(long id, RegionCell source) {
        NormalizedCell cell() { return source.cell(); }
        String formulaSkeleton() { return source.formulaSkeleton(); }
    }

    List<DetectedRegion> detect(String sheetName, Map<Long, RegionCell> cellsById) {
        List<OccupiedCell> occupied = cellsById.entrySet().stream()
                .filter(e -> isOccupied(e.getValue().cell()))
                .map(e -> new OccupiedCell(e.getKey(), e.getValue()))
                .toList();
        List<List<OccupiedCell>> components = components(occupied);
        List<List<OccupiedCell>> split = new ArrayList<>();
        for (List<OccupiedCell> component : components) {
            for (List<OccupiedCell> bannerSplit : splitBanner(component)) {
                split.addAll(splitHorizontal(sheetName, bannerSplit));
            }
        }
        split.sort(Comparator.comparingInt((List<OccupiedCell> c) -> minRow(c))
                .thenComparingInt(RegionDetector::minCol));

        Map<String, Integer> occurrences = new HashMap<>();
        List<DetectedRegion> regions = new ArrayList<>();
        for (List<OccupiedCell> component : split) {
            String baseKey = sheetName + "!" + anchor(component).cell().coord();
            int ordinal = occurrences.merge(baseKey, 1, Integer::sum);
            String key = ordinal == 1 ? baseKey : baseKey + "#" + ordinal;
            regions.add(new DetectedRegion(key, minRow(component), maxRow(component),
                    minCol(component), maxCol(component),
                    component.stream().map(OccupiedCell::id).toList()));
        }
        return regions;
    }

    /**
     * Horizontal cuts inside a geometrically connected component. A shared column-header row
     * is the table identity; blank rows and value-profile changes never create a region.
     */
    private static List<List<OccupiedCell>> splitHorizontal(String sheetName,
            List<OccupiedCell> component) {
        if (component.size() < 2) {
            return List.of(component);
        }
        List<Integer> cuts = new ArrayList<>();
        for (BreakCandidate candidate : assessedBreakCandidates(sheetName, component)) {
            if (candidate.selected()) {
                cuts.add(candidate.cutAfterRow());
            }
        }
        if (cuts.isEmpty()) {
            return List.of(component);
        }
        List<List<OccupiedCell>> result = new ArrayList<>();
        int start = minRow(component);
        for (int cut : cuts) {
            addRowBand(result, component, start, cut);
            start = cut + 1;
        }
        addRowBand(result, component, start, maxRow(component));
        return result;
    }

    /** Package seam for #40: exposes accepted and rejected cut evidence. */
    static List<BreakCandidate> breakCandidates(String sheetName, Map<Long, RegionCell> cellsById) {
        List<OccupiedCell> occupied = cellsById.entrySet().stream()
                .filter(e -> isOccupied(e.getValue().cell()))
                .map(e -> new OccupiedCell(e.getKey(), e.getValue()))
                .toList();
        return components(occupied).stream()
                .flatMap(component -> assessedBreakCandidates(sheetName, component).stream())
                .toList();
    }

    private static List<BreakCandidate> assessedBreakCandidates(String sheetName,
            List<OccupiedCell> component) {
        List<BreakCandidate> candidates = new ArrayList<>();
        for (int row = minRow(component); row < maxRow(component); row++) {
            BreakAssessment assessment = breakAssessment(sheetName, component, row);
            boolean selected = assessment.rejectionReason() == null;
            candidates.add(new BreakCandidate(row, selected ? 1 : 0, selected,
                    assessment.rejectionReason()));
        }
        return candidates;
    }

    private static void addRowBand(List<List<OccupiedCell>> bands, List<OccupiedCell> component,
            int start, int end) {
        List<OccupiedCell> band = component.stream()
                .filter(cell -> cell.cell().rowNum() >= start && cell.cell().rowNum() <= end).toList();
        if (!band.isEmpty()) {
            bands.add(band);
        }
    }

    private record BreakAssessment(int score, String rejectionReason) {}

    private static BreakAssessment breakAssessment(String sheetName, List<OccupiedCell> component,
            int cutAfterRow) {
        if (splitsMergedRange(component, cutAfterRow)) {
            return new BreakAssessment(0, "merged_range");
        }
        if (computedFromRowsAbove(sheetName, component, cutAfterRow)) {
            return new BreakAssessment(0, "computed_from_rows_above");
        }
        if (!isColumnHeaderRow(firstPopulatedRowBelow(component, cutAfterRow))
                || !hasColumnHeaderRowAtOrAbove(component, cutAfterRow)) {
            return new BreakAssessment(0, "no_column_header_below");
        }
        return new BreakAssessment(1, null);
    }

    private static List<OccupiedCell> nearby(List<OccupiedCell> component, int first, int last) {
        return component.stream().filter(c -> c.cell().rowNum() >= first && c.cell().rowNum() <= last).toList();
    }

    private static List<OccupiedCell> firstPopulatedRowBelow(List<OccupiedCell> component, int cut) {
        int row = cut + 1;
        int last = maxRow(component);
        while (row <= last && !hasCellOnRow(component, row)) {
            row++;
        }
        return nearby(component, row, row);
    }

    private static boolean computedFromRowsAbove(String sheetName, List<OccupiedCell> component,
            int cut) {
        for (OccupiedCell cell : firstPopulatedRowBelow(component, cut)) {
            String formula = cell.cell().formulaText();
            if (formula == null || formula.isBlank()) {
                continue;
            }
            for (CellReference ref : FormulaReferenceExtractor.extractLocalRefs(formula, sheetName)) {
                int row = ref.getRow() + 1;
                if (row <= cut && hasCellOnRow(component, row)) {
                    return true;
                }
            }
            int[] range = summedRowRange(formula);
            if (range != null) {
                int from = Math.min(range[0], range[1]);
                int to = Math.max(range[0], range[1]);
                for (int row = from; row <= Math.min(to, cut); row++) {
                    if (hasCellOnRow(component, row)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isColumnHeaderRow(List<OccupiedCell> row) {
        if (row.isEmpty() || isTotalOrSubtotalRow(row)) {
            return false;
        }
        List<String> labels = new ArrayList<>();
        for (OccupiedCell cell : row) {
            NormalizedCell n = cell.cell();
            if ("text".equals(n.valueType()) && n.textValue() != null && !n.textValue().isBlank()) {
                labels.add(n.textValue().trim());
            }
        }
        if (labels.size() >= 2) {
            return true;
        }
        return labels.stream().anyMatch(RegionHeaderAnalyzer::isColumnHeaderPeriodLabel);
    }

    private static boolean hasColumnHeaderRowAtOrAbove(List<OccupiedCell> component, int cut) {
        for (Map.Entry<Integer, List<OccupiedCell>> row : byRow(component).entrySet()) {
            if (row.getKey() > cut) {
                break;
            }
            if (isColumnHeaderRow(row.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean splitsMergedRange(List<OccupiedCell> cells, int cut) {
        return cells.stream().map(c -> c.cell().mergedRange()).filter(java.util.Objects::nonNull)
                .map(RegionDetector::mergedRows).filter(java.util.Objects::nonNull)
                .anyMatch(rows -> rows[0] <= cut && rows[1] > cut);
    }

    private static int[] mergedRows(String range) {
        java.util.regex.Matcher matcher = Pattern.compile("[A-Z]+(\\d+):[A-Z]+(\\d+)").matcher(range);
        if (!matcher.matches()) return null;
        return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
    }

    private static boolean hasCellOnRow(List<OccupiedCell> component, int row) {
        return component.stream().anyMatch(cell -> cell.cell().rowNum() == row);
    }

    private static int[] summedRowRange(String formula) {
        java.util.regex.Matcher matcher = Pattern.compile(
                "(?is).*\\bSUM\\s*\\([^)]*[A-Z]+(\\d+):[A-Z]+(\\d+)[^)]*\\).*").matcher(formula);
        if (!matcher.matches()) return null;
        return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
    }

    private static boolean isTotalOrSubtotalRow(List<OccupiedCell> cells) {
        boolean labelledTotal = cells.stream().map(c -> c.cell().textValue()).filter(java.util.Objects::nonNull)
                .map(String::trim).anyMatch(v -> v.matches("(?i).*\\b(grand )?(sub)?total\\b.*"));
        return labelledTotal || cells.stream().map(c -> c.cell().formulaText())
                .filter(java.util.Objects::nonNull).anyMatch(RegionDetector::isSumFormula);
    }

    private static boolean isSumFormula(String formula) {
        return formula.matches("(?is).*\\bSUM\\s*\\(.*");
    }

    private static boolean isOccupied(NormalizedCell cell) {
        return cell.isMergedParticipant() || cell.isError()
                || cell.formulaText() != null
                || (cell.rawValue() != null && !cell.rawValue().isBlank());
    }

    /** Local four-direction coherence for a formula cell; only formula neighbours contribute. */
    static Map<String, Double> coherenceDirections(long cellId, Map<Long, RegionCell> cellsById) {
        RegionCell source = cellsById.get(cellId);
        if (source == null || source.formulaSkeleton() == null) {
            return Map.of();
        }
        NormalizedCell cell = source.cell();
        Map<String, Double> result = new LinkedHashMap<>();
        result.put("left", nearestSimilarity(cell, source.formulaSkeleton(), cellsById, 0, -1));
        result.put("right", nearestSimilarity(cell, source.formulaSkeleton(), cellsById, 0, 1));
        result.put("up", nearestSimilarity(cell, source.formulaSkeleton(), cellsById, -1, 0));
        result.put("down", nearestSimilarity(cell, source.formulaSkeleton(), cellsById, 1, 0));
        return result;
    }

    private static double nearestSimilarity(NormalizedCell origin, String skeleton,
            Map<Long, RegionCell> cellsById, int rowStep, int colStep) {
        RegionCell nearest = null;
        int distance = Integer.MAX_VALUE;
        for (RegionCell candidate : cellsById.values()) {
            if (candidate.formulaSkeleton() == null) continue;
            int rowDelta = candidate.cell().rowNum() - origin.rowNum();
            int colDelta = candidate.cell().colNum() - origin.colNum();
            boolean direction = rowStep != 0 ? colDelta == 0 && Integer.signum(rowDelta) == rowStep
                    : rowDelta == 0 && Integer.signum(colDelta) == colStep;
            int candidateDistance = Math.abs(rowDelta) + Math.abs(colDelta);
            if (direction && candidateDistance < distance) {
                nearest = candidate;
                distance = candidateDistance;
            }
        }
        return nearest == null ? 0.0 : skeletonSimilarity(skeleton, nearest.formulaSkeleton());
    }

    private static List<List<OccupiedCell>> components(List<OccupiedCell> cells) {
        Map<Long, OccupiedCell> cellsByCoordinate = new HashMap<>();
        Map<Long, OccupiedCell> cellsById = new HashMap<>();
        Set<Long> remaining = new HashSet<>();
        for (OccupiedCell cell : cells) {
            cellsByCoordinate.put(coordinate(cell.cell().rowNum(), cell.cell().colNum()), cell);
            cellsById.put(cell.id(), cell);
            remaining.add(cell.id());
        }
        List<List<OccupiedCell>> result = new ArrayList<>();
        while (!remaining.isEmpty()) {
            long startId = remaining.iterator().next();
            OccupiedCell start = cellsById.get(startId);
            remaining.remove(startId);
            List<OccupiedCell> component = new ArrayList<>();
            ArrayDeque<OccupiedCell> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                OccupiedCell current = queue.remove();
                component.add(current);
                for (int rowOffset = -2; rowOffset <= 2; rowOffset++) {
                    for (int colOffset = -2; colOffset <= 2; colOffset++) {
                        if (rowOffset == 0 && colOffset == 0) {
                            continue;
                        }
                        OccupiedCell candidate = cellsByCoordinate.get(coordinate(
                                current.cell().rowNum() + rowOffset, current.cell().colNum() + colOffset));
                        if (candidate != null && remaining.contains(candidate.id()) && connected(current, candidate)) {
                            remaining.remove(candidate.id());
                            queue.add(candidate);
                        }
                    }
                }
            }
            result.add(component);
        }
        return result;
    }

    /**
     * 8-connectivity, plus a one-cell skip: similar formulas, same-column labels, or an
     * axis-aligned stub-to-value spacer ({@code name | blank | number}).
     */
    private static boolean connected(OccupiedCell left, OccupiedCell right) {
        int rowGap = Math.abs(left.cell().rowNum() - right.cell().rowNum());
        int colGap = Math.abs(left.cell().colNum() - right.cell().colNum());
        if (rowGap <= 1 && colGap <= 1) {
            return true;
        }
        if (rowGap > 2 || colGap > 2) {
            return false;
        }
        return axisAlignedStubToValue(left.cell(), right.cell(), rowGap, colGap)
                || skeletonSimilarity(left.formulaSkeleton(), right.formulaSkeleton()) > 0
                || sameColumnTextLabels(left.cell(), right.cell());
    }

    private static boolean axisAlignedStubToValue(
            NormalizedCell left, NormalizedCell right, int rowGap, int colGap) {
        if (!((rowGap == 0 && colGap == 2) || (rowGap == 2 && colGap == 0))) {
            return false;
        }
        return (isTextStub(left) && isValueCell(right)) || (isTextStub(right) && isValueCell(left));
    }

    private static boolean isTextStub(NormalizedCell cell) {
        return cell.formulaText() == null
                && "text".equals(cell.valueType())
                && cell.textValue() != null
                && !cell.textValue().isBlank();
    }

    private static boolean isValueCell(NormalizedCell cell) {
        return "number".equals(cell.valueType()) || cell.formulaText() != null;
    }

    /**
     * §7.4: exact skeletons score 1; a one-token variation remains compatible at 0.5.
     * Larger differences deliberately do not make a geometric bridge.
     */
    private static double skeletonSimilarity(String left, String right) {
        if (left == null || right == null) {
            return 0.0;
        }
        int distance = tokenEditDistance(tokens(left), tokens(right));
        return distance == 0 ? 1.0 : distance == 1 ? 0.5 : 0.0;
    }

    private static List<String> tokens(String skeleton) {
        java.util.regex.Matcher matcher = SKELETON_TOKEN.matcher(skeleton);
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static int tokenEditDistance(List<String> left, List<String> right) {
        int[] previous = new int[right.size() + 1];
        for (int j = 0; j <= right.size(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.size(); i++) {
            int[] current = new int[right.size() + 1];
            current[0] = i;
            for (int j = 1; j <= right.size(); j++) {
                int substitution = previous[j - 1] + (left.get(i - 1).equals(right.get(j - 1)) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), substitution);
            }
            previous = current;
        }
        return previous[right.size()];
    }

    private static boolean sameColumnTextLabels(NormalizedCell left, NormalizedCell right) {
        return left.colNum() == right.colNum()
                && left.textValue() != null && !left.textValue().isBlank()
                && right.textValue() != null && !right.textValue().isBlank()
                && left.formulaText() == null && right.formulaText() == null;
    }

    private static List<List<OccupiedCell>> splitBanner(List<OccupiedCell> component) {
        for (Map.Entry<Integer, List<OccupiedCell>> row : byRow(component).entrySet()) {
            if (!isBanner(row.getValue())) {
                continue;
            }
            Set<Long> bannerIds = row.getValue().stream().map(OccupiedCell::id).collect(java.util.stream.Collectors.toSet());
            List<OccupiedCell> body = component.stream().filter(c -> !bannerIds.contains(c.id())).toList();
            List<List<OccupiedCell>> bodyComponents = components(body);
            if (bodyComponents.size() >= 2 && columnDisjoint(bodyComponents)) {
                List<List<OccupiedCell>> split = new ArrayList<>();
                split.add(row.getValue());
                split.addAll(bodyComponents);
                return split;
            }
        }
        return List.of(component);
    }

    private static Map<Integer, List<OccupiedCell>> byRow(List<OccupiedCell> component) {
        Map<Integer, List<OccupiedCell>> rows = new TreeMap<>();
        for (OccupiedCell cell : component) {
            rows.computeIfAbsent(cell.cell().rowNum(), ignored -> new ArrayList<>()).add(cell);
        }
        return rows;
    }

    private static boolean isBanner(List<OccupiedCell> cells) {
        if (cells.size() < 2) {
            return false;
        }
        String mergedRange = cells.getFirst().cell().mergedRange();
        return mergedRange != null && cells.stream()
                .allMatch(cell -> mergedRange.equals(cell.cell().mergedRange()));
    }

    private static boolean columnDisjoint(List<List<OccupiedCell>> components) {
        Set<Integer> columns = new HashSet<>();
        for (List<OccupiedCell> component : components) {
            Set<Integer> componentColumns = component.stream().map(c -> c.cell().colNum())
                    .collect(java.util.stream.Collectors.toSet());
            if (!java.util.Collections.disjoint(columns, componentColumns)) {
                return false;
            }
            columns.addAll(componentColumns);
        }
        return true;
    }

    private static OccupiedCell anchor(List<OccupiedCell> component) {
        return component.stream().min(Comparator.comparingInt((OccupiedCell c) -> c.cell().rowNum())
                .thenComparingInt(c -> c.cell().colNum())).orElseThrow();
    }

    private static long coordinate(int row, int col) {
        return ((long) row << 32) ^ (col & 0xffffffffL);
    }

    private static int minRow(List<OccupiedCell> component) { return component.stream().mapToInt(c -> c.cell().rowNum()).min().orElseThrow(); }
    private static int maxRow(List<OccupiedCell> component) { return component.stream().mapToInt(c -> c.cell().rowNum()).max().orElseThrow(); }
    private static int minCol(List<OccupiedCell> component) { return component.stream().mapToInt(c -> c.cell().colNum()).min().orElseThrow(); }
    private static int maxCol(List<OccupiedCell> component) { return component.stream().mapToInt(c -> c.cell().colNum()).max().orElseThrow(); }
}
