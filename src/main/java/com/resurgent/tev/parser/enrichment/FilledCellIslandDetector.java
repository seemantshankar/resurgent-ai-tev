package com.resurgent.tev.parser.enrichment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.util.CellReference;

/**
 * Finds filled-cell islands for LLM hints: 8-connected components, then merges
 * L-shaped tables (column headers above a body with an empty corner, left row
 * labels beside amounts, and one blank row of vertical spacing).
 */
final class FilledCellIslandDetector {

    /** Max blank rows between vertically glued islands (header/body spacing). */
    static final int MAX_VERTICAL_GAP = 1;

    /** Max blank columns between a left row-label island and its body. */
    static final int MAX_LEFT_LABEL_GAP = 2;

    /** Max width (columns) for an island treated as left row labels. */
    static final int MAX_LEFT_LABEL_WIDTH = 3;

    /** Max blank rows under a single-row header strip before the body starts. */
    static final int MAX_HEADER_BODY_GAP = 1;

    List<IslandHint> detect(Set<String> filledCells) {
        List<Set<String>> components = eightConnected(filledCells);
        components = mergeTableShapes(components);
        List<IslandHint> islands = new ArrayList<>(components.size());
        int index = 1;
        for (Set<String> component : components) {
            islands.add(new IslandHint(
                    "island-" + index++,
                    bounds(component),
                    component.size()));
        }
        return List.copyOf(islands);
    }

    private static List<Set<String>> eightConnected(Set<String> filledCells) {
        Map<String, Set<String>> neighborsByCell = new LinkedHashMap<>();
        for (String address : filledCells) {
            neighborsByCell.put(address, new LinkedHashSet<>());
        }
        for (String address : filledCells) {
            CellReference ref = new CellReference(address);
            int row = ref.getRow();
            int col = ref.getCol();
            for (int dr = -1; dr <= 1; dr++) {
                for (int dc = -1; dc <= 1; dc++) {
                    if (dr == 0 && dc == 0) {
                        continue;
                    }
                    String neighbor = address(row + dr, col + dc);
                    if (filledCells.contains(neighbor)) {
                        neighborsByCell.get(address).add(neighbor);
                    }
                }
            }
        }

        Set<String> visited = new LinkedHashSet<>();
        List<Set<String>> components = new ArrayList<>();
        List<String> sorted = filledCells.stream().sorted(Comparator.naturalOrder()).toList();
        for (String start : sorted) {
            if (visited.contains(start)) {
                continue;
            }
            Set<String> component = new LinkedHashSet<>();
            List<String> queue = new ArrayList<>();
            queue.add(start);
            visited.add(start);
            while (!queue.isEmpty()) {
                String current = queue.removeLast();
                component.add(current);
                for (String neighbor : neighborsByCell.get(current)) {
                    if (visited.add(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
            components.add(component);
        }
        return components;
    }

    private static List<Set<String>> mergeTableShapes(List<Set<String>> components) {
        List<Set<String>> current = new ArrayList<>(components);
        boolean changed = true;
        while (changed) {
            changed = false;
            outer:
            for (int i = 0; i < current.size(); i++) {
                for (int j = i + 1; j < current.size(); j++) {
                    if (shouldMerge(current.get(i), current.get(j))) {
                        Set<String> merged = new LinkedHashSet<>(current.get(i));
                        merged.addAll(current.get(j));
                        current.remove(j);
                        current.remove(i);
                        current.add(merged);
                        current.sort(Comparator.comparing(FilledCellIslandDetector::bounds));
                        changed = true;
                        break outer;
                    }
                }
            }
        }
        current.sort(Comparator.comparing(FilledCellIslandDetector::bounds));
        return current;
    }

    private static boolean shouldMerge(Set<String> a, Set<String> b) {
        BBox left = bbox(a);
        BBox right = bbox(b);
        return isHeaderAboveBody(left, right)
                || isHeaderAboveBody(right, left)
                || isLeftLabelsBesideBody(left, right)
                || isLeftLabelsBesideBody(right, left)
                || isVerticallyGlued(left, right);
    }

    /** Single-row header strip directly above a body with overlapping columns. */
    private static boolean isHeaderAboveBody(BBox header, BBox body) {
        if (header.minRow != header.maxRow) {
            return false;
        }
        int gap = body.minRow - header.maxRow - 1;
        if (gap < 0 || gap > MAX_HEADER_BODY_GAP) {
            return false;
        }
        return columnsOverlap(header, body);
    }

    /** Narrow row-label island immediately left of a body with overlapping rows. */
    private static boolean isLeftLabelsBesideBody(BBox labels, BBox body) {
        int labelWidth = labels.maxCol - labels.minCol + 1;
        int bodyWidth = body.maxCol - body.minCol + 1;
        if (labelWidth > MAX_LEFT_LABEL_WIDTH) {
            return false;
        }
        // Side scratch blocks are narrow; only absorb labels into a wider body.
        if (bodyWidth <= labelWidth || bodyWidth < 3) {
            return false;
        }
        if (labels.maxCol >= body.minCol) {
            return false;
        }
        int gap = body.minCol - labels.maxCol - 1;
        if (gap < 0 || gap > MAX_LEFT_LABEL_GAP) {
            return false;
        }
        return rowsOverlap(labels, body);
    }

    /**
     * Bodies separated by at most one blank row with overlapping columns, or
     * interlocking pieces that already share row and column bands (L-shape).
     */
    private static boolean isVerticallyGlued(BBox a, BBox b) {
        if (!columnsOverlap(a, b)) {
            return false;
        }
        if (rowsOverlap(a, b)) {
            return true;
        }
        BBox upper = a.minRow <= b.minRow ? a : b;
        BBox lower = a.minRow <= b.minRow ? b : a;
        int gap = lower.minRow - upper.maxRow - 1;
        return gap >= 0 && gap <= MAX_VERTICAL_GAP;
    }

    private static boolean columnsOverlap(BBox a, BBox b) {
        return a.minCol <= b.maxCol && b.minCol <= a.maxCol;
    }

    private static boolean rowsOverlap(BBox a, BBox b) {
        return a.minRow <= b.maxRow && b.minRow <= a.maxRow;
    }

    private static BBox bbox(Set<String> cells) {
        int minRow = Integer.MAX_VALUE;
        int minCol = Integer.MAX_VALUE;
        int maxRow = Integer.MIN_VALUE;
        int maxCol = Integer.MIN_VALUE;
        for (String address : cells) {
            CellReference ref = new CellReference(address);
            minRow = Math.min(minRow, ref.getRow());
            minCol = Math.min(minCol, ref.getCol());
            maxRow = Math.max(maxRow, ref.getRow());
            maxCol = Math.max(maxCol, ref.getCol());
        }
        return new BBox(minRow, minCol, maxRow, maxCol);
    }

    private static String bounds(Set<String> cells) {
        BBox box = bbox(cells);
        String first = address(box.minRow, box.minCol);
        String last = address(box.maxRow, box.maxCol);
        return first.equals(last) ? first : first + ":" + last;
    }

    private static String address(int row, int col) {
        return CellReference.convertNumToColString(col) + (row + 1);
    }

    private record BBox(int minRow, int minCol, int maxRow, int maxCol) {}
}
