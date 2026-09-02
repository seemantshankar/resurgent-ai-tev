package com.resurgent.tev.parser.enrichment;

import com.resurgent.tev.parser.enrichment.EnrichmentReport.Problem;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.ProblemCode;
import com.resurgent.tev.parser.enrichment.EnrichmentReport.Region;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;

/**
 * Portion of a sheet to send on a coverage-repair pass: leftover filled cells
 * plus existing regions that sit next to them. Frozen regions stay out.
 */
public record RepairWindow(
        List<String> leftovers,
        List<Region> nearbyRegions,
        Set<String> cropCells) {

    /** Max blank rows between leftover rows in one cluster, and to a nearby box. */
    static final int MAX_ROW_GAP = 5;

    /** Max blank columns between a leftover cluster and a side-adjacent box. */
    static final int MAX_COL_GAP = 2;

    public RepairWindow {
        leftovers = List.copyOf(leftovers);
        nearbyRegions = List.copyOf(nearbyRegions);
        cropCells = Set.copyOf(cropCells);
    }

    static RepairWindow from(EnrichmentReport report, Set<String> filledCells) {
        List<String> leftovers = report.problems().stream()
                .filter(problem -> problem.code() == ProblemCode.UNASSIGNED_CELL)
                .map(Problem::cells)
                .flatMap(List::stream)
                .distinct()
                .sorted()
                .toList();
        if (leftovers.isEmpty()) {
            return new RepairWindow(List.of(), List.of(), Set.of());
        }
        List<BBox> clusters = clusterLeftovers(leftovers);
        List<Region> nearby = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Region region : report.regions()) {
            BBox regionBox = bbox(region.bounds());
            boolean close = clusters.stream().anyMatch(cluster -> nearby(regionBox, cluster));
            if (close && seen.add(region.id())) {
                nearby.add(region);
            }
        }
        Set<String> crop = new LinkedHashSet<>(leftovers);
        for (String filled : filledCells) {
            if (crop.contains(filled)) {
                continue;
            }
            CellReference cell = new CellReference(filled);
            for (Region region : nearby) {
                if (CellRangeAddress.valueOf(region.bounds())
                        .isInRange(cell.getRow(), cell.getCol())) {
                    crop.add(filled);
                    break;
                }
            }
        }
        return new RepairWindow(leftovers, nearby, crop);
    }

    Set<String> nearbyIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Region region : nearbyRegions) {
            ids.add(region.id());
        }
        return Set.copyOf(ids);
    }

    static String cropNdjson(String ndjson, Set<String> cropCells) {
        StringBuilder cropped = new StringBuilder();
        for (String line : ndjson.split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            for (String coord : cropCells) {
                if (line.contains("\"coord\":\"" + coord + "\"")) {
                    cropped.append(line).append('\n');
                    break;
                }
            }
        }
        return cropped.toString();
    }

    static String cropSparseGrid(String sparseGrid, Set<String> cropCells) {
        StringBuilder cropped = new StringBuilder();
        for (String line : sparseGrid.split("\n", -1)) {
            if (!line.startsWith("Row ")) {
                continue;
            }
            String[] parts = line.split(" \\| ", -1);
            boolean keep = false;
            StringBuilder rebuilt = new StringBuilder(parts[0]);
            for (int i = 1; i < parts.length; i++) {
                rebuilt.append(" | ");
                String part = parts[i];
                int colon = part.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String coord = part.substring(0, colon);
                if (cropCells.contains(coord)) {
                    rebuilt.append(part);
                    keep = true;
                }
            }
            if (keep) {
                cropped.append(rebuilt).append('\n');
            }
        }
        return cropped.toString();
    }

    private static List<BBox> clusterLeftovers(List<String> leftovers) {
        TreeSet<Integer> rows = new TreeSet<>();
        for (String leftover : leftovers) {
            rows.add(new CellReference(leftover).getRow());
        }
        List<int[]> rowSpans = new ArrayList<>();
        int spanStart = -1;
        int previous = -1;
        for (int row : rows) {
            if (spanStart < 0) {
                spanStart = row;
            } else if (row - previous - 1 > MAX_ROW_GAP) {
                rowSpans.add(new int[] {spanStart, previous});
                spanStart = row;
            }
            previous = row;
        }
        rowSpans.add(new int[] {spanStart, previous});

        List<BBox> clusters = new ArrayList<>();
        for (int[] span : rowSpans) {
            int minRow = span[0];
            int maxRow = span[1];
            int minCol = Integer.MAX_VALUE;
            int maxCol = Integer.MIN_VALUE;
            for (String leftover : leftovers) {
                CellReference cell = new CellReference(leftover);
                if (cell.getRow() < minRow || cell.getRow() > maxRow) {
                    continue;
                }
                minCol = Math.min(minCol, cell.getCol());
                maxCol = Math.max(maxCol, cell.getCol());
            }
            clusters.add(new BBox(minRow, minCol, maxRow, maxCol));
        }
        clusters.sort(Comparator.comparingInt(BBox::minRow));
        return clusters;
    }

    private static boolean nearby(BBox region, BBox leftover) {
        if (overlap(region, leftover)) {
            return true;
        }
        if (columnsOverlap(region, leftover) && rowGap(region, leftover) <= MAX_ROW_GAP) {
            return true;
        }
        return rowsOverlap(region, leftover) && colGap(region, leftover) <= MAX_COL_GAP;
    }

    private static boolean overlap(BBox a, BBox b) {
        return columnsOverlap(a, b) && rowsOverlap(a, b);
    }

    private static boolean columnsOverlap(BBox a, BBox b) {
        return a.minCol <= b.maxCol && b.minCol <= a.maxCol;
    }

    private static boolean rowsOverlap(BBox a, BBox b) {
        return a.minRow <= b.maxRow && b.minRow <= a.maxRow;
    }

    private static int rowGap(BBox a, BBox b) {
        if (rowsOverlap(a, b)) {
            return 0;
        }
        return a.maxRow < b.minRow ? b.minRow - a.maxRow - 1 : a.minRow - b.maxRow - 1;
    }

    private static int colGap(BBox a, BBox b) {
        if (columnsOverlap(a, b)) {
            return 0;
        }
        return a.maxCol < b.minCol ? b.minCol - a.maxCol - 1 : a.minCol - b.maxCol - 1;
    }

    private static BBox bbox(String bounds) {
        CellRangeAddress range = CellRangeAddress.valueOf(bounds);
        return new BBox(
                range.getFirstRow(),
                range.getFirstColumn(),
                range.getLastRow(),
                range.getLastColumn());
    }

    private record BBox(int minRow, int minCol, int maxRow, int maxCol) {}
}
