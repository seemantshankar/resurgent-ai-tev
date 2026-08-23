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
import java.util.regex.Pattern;

/**
 * Finds the coarse, geometry-only regions of one worksheet. Classification happens in a
 * later ticket; this detector deliberately emits every component as {@code unknown}.
 */
final class RegionDetector {

    private static final Pattern SKELETON_TOKEN = Pattern.compile("\\$ABS\\$|[A-Za-z_]+|\\d+(?:\\.\\d+)?|\\S");

    record RegionCell(NormalizedCell cell, String formulaSkeleton) {}

    record DetectedRegion(String key, int startRow, int endRow, int startCol, int endCol,
            List<Long> cellIds) {}

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
            split.addAll(splitBanner(component));
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

    private static boolean isOccupied(NormalizedCell cell) {
        return cell.isMergedParticipant() || cell.isError()
                || cell.formulaText() != null
                || (cell.rawValue() != null && !cell.rawValue().isBlank());
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

    /** 8-connectivity, with a single-cell dilation restricted to coherent formulas or labels. */
    private static boolean connected(OccupiedCell left, OccupiedCell right) {
        int rowGap = Math.abs(left.cell().rowNum() - right.cell().rowNum());
        int colGap = Math.abs(left.cell().colNum() - right.cell().colNum());
        if (rowGap <= 1 && colGap <= 1) {
            return true;
        }
        if (rowGap > 2 || colGap > 2) {
            return false;
        }
        return skeletonSimilarity(left.formulaSkeleton(), right.formulaSkeleton()) > 0
                || sameColumnTextLabels(left.cell(), right.cell());
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
        Map<Integer, List<OccupiedCell>> rows = new LinkedHashMap<>();
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
