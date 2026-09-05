package com.resurgent.tev.parser.discover;

import com.resurgent.tev.parser.db.CellEvidence;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Worksheet-local child / parallel / overlap Candidates from layout signatures.
 * Package-private; exercised only through {@link DiscoverService}.
 */
final class LocalStructureDiscoverer {

    record NarrowCandidate(
            String kind,
            List<Long> memberCellIds,
            int bboxMinRow,
            int bboxMinCol,
            int bboxMaxRow,
            int bboxMaxCol,
            double structuralConfidence,
            String structuralConfidenceRationale,
            String explanation) {
    }

    List<NarrowCandidate> discover(List<CellEvidence> cells) {
        if (cells == null || cells.isEmpty()) {
            return List.of();
        }
        Map<Integer, List<CellEvidence>> byRow = groupByRow(cells);
        List<List<Integer>> softSegments = softVerticalSegments(byRow);
        List<NarrowCandidate> result = new ArrayList<>();
        for (List<Integer> segmentRows : softSegments) {
            List<CellEvidence> segmentCells = cellsInRows(byRow, segmentRows);
            if (segmentCells.size() < 2) {
                continue;
            }
            List<ColBand> bands = parallelColumnBands(segmentCells);
            if (bands.size() >= 2) {
                for (ColBand band : bands) {
                    result.add(toCandidate(
                            "parallel",
                            band.cells(),
                            0.85,
                            "distinct column band separated by an empty column gap",
                            "Parallel column band with occupied columns "
                                    + band.minCol() + "-" + band.maxCol()));
                }
                result.add(toCandidate(
                        "overlap",
                        segmentCells,
                        0.55,
                        "wide grouping remains plausible alongside parallel bands",
                        "Overlapping wide grouping spanning parallel column bands"));
            } else {
                result.add(toCandidate(
                        "child",
                        segmentCells,
                        0.8,
                        "soft-blank vertical cluster with compatible column occupancy",
                        "Local child spanning compatible rows with soft blank separators"));
                List<List<Integer>> hardBlocks = hardContiguousBlocks(segmentRows, byRow);
                if (hardBlocks.size() >= 2) {
                    for (List<Integer> blockRows : hardBlocks) {
                        List<CellEvidence> blockCells = cellsInRows(byRow, blockRows);
                        if (blockCells.size() < 2) {
                            continue;
                        }
                        result.add(toCandidate(
                                "child",
                                blockCells,
                                0.7,
                                "contiguous row block inside a table-wide soft parent",
                                "Nested section child under table-wide local parent"));
                    }
                }
            }
        }
        return dedupeExactMembership(result);
    }

    private static Map<Integer, List<CellEvidence>> groupByRow(List<CellEvidence> cells) {
        Map<Integer, List<CellEvidence>> byRow = new TreeMap<>();
        for (CellEvidence cell : cells) {
            byRow.computeIfAbsent(cell.rowNum(), r -> new ArrayList<>()).add(cell);
        }
        return byRow;
    }

    /**
     * Soft separators: blank bands alone do not split when column occupancy stays compatible.
     */
    private static List<List<Integer>> softVerticalSegments(Map<Integer, List<CellEvidence>> byRow) {
        List<Integer> occupiedRows = new ArrayList<>(byRow.keySet());
        if (occupiedRows.isEmpty()) {
            return List.of();
        }
        List<List<Integer>> segments = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        current.add(occupiedRows.get(0));
        Set<Integer> currentCols = occupiedCols(byRow.get(occupiedRows.get(0)));

        for (int i = 1; i < occupiedRows.size(); i++) {
            int row = occupiedRows.get(i);
            Set<Integer> nextCols = occupiedCols(byRow.get(row));
            if (columnBandsCompatible(currentCols, nextCols)) {
                current.add(row);
                currentCols = union(currentCols, nextCols);
            } else {
                segments.add(current);
                current = new ArrayList<>();
                current.add(row);
                currentCols = new HashSet<>(nextCols);
            }
        }
        segments.add(current);
        return segments;
    }

    private static List<List<Integer>> hardContiguousBlocks(
            List<Integer> segmentRows, Map<Integer, List<CellEvidence>> byRow) {
        if (segmentRows.isEmpty()) {
            return List.of();
        }
        List<List<Integer>> blocks = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        current.add(segmentRows.get(0));
        for (int i = 1; i < segmentRows.size(); i++) {
            int prev = segmentRows.get(i - 1);
            int row = segmentRows.get(i);
            if (row == prev + 1) {
                current.add(row);
            } else {
                blocks.add(current);
                current = new ArrayList<>();
                current.add(row);
            }
        }
        blocks.add(current);
        return blocks;
    }

    private static List<ColBand> parallelColumnBands(List<CellEvidence> segmentCells) {
        TreeSet<Integer> occupied = new TreeSet<>();
        for (CellEvidence cell : segmentCells) {
            occupied.add(cell.colNum());
        }
        if (occupied.size() < 2) {
            return List.of(new ColBand(occupied.first(), occupied.last(), segmentCells));
        }
        int min = occupied.first();
        int max = occupied.last();
        List<int[]> gapRanges = new ArrayList<>();
        Integer gapStart = null;
        for (int col = min; col <= max; col++) {
            if (!occupied.contains(col)) {
                if (gapStart == null) {
                    gapStart = col;
                }
            } else if (gapStart != null) {
                gapRanges.add(new int[] {gapStart, col - 1});
                gapStart = null;
            }
        }
        if (gapRanges.isEmpty()) {
            return List.of(new ColBand(min, max, segmentCells));
        }

        // Split on every empty-column gap that separates two non-empty bands.
        List<ColBand> bands = new ArrayList<>();
        int bandStart = min;
        for (int[] gap : gapRanges) {
            int bandEnd = gap[0] - 1;
            List<CellEvidence> bandCells = cellsInColRange(segmentCells, bandStart, bandEnd);
            if (!bandCells.isEmpty()) {
                bands.add(new ColBand(bandStart, bandEnd, bandCells));
            }
            bandStart = gap[1] + 1;
        }
        List<CellEvidence> last = cellsInColRange(segmentCells, bandStart, max);
        if (!last.isEmpty()) {
            bands.add(new ColBand(bandStart, max, last));
        }
        // Require each band to look like a real table fragment (multiple cells).
        List<ColBand> substantial = bands.stream().filter(b -> b.cells().size() >= 2).toList();
        return substantial.size() >= 2 ? substantial : List.of(new ColBand(min, max, segmentCells));
    }

    private static List<CellEvidence> cellsInRows(
            Map<Integer, List<CellEvidence>> byRow, List<Integer> rows) {
        List<CellEvidence> out = new ArrayList<>();
        for (Integer row : rows) {
            List<CellEvidence> rowCells = byRow.get(row);
            if (rowCells != null) {
                out.addAll(rowCells);
            }
        }
        return out;
    }

    private static List<CellEvidence> cellsInColRange(
            List<CellEvidence> cells, int minCol, int maxCol) {
        List<CellEvidence> out = new ArrayList<>();
        for (CellEvidence cell : cells) {
            if (cell.colNum() >= minCol && cell.colNum() <= maxCol) {
                out.add(cell);
            }
        }
        return out;
    }

    private static Set<Integer> occupiedCols(List<CellEvidence> rowCells) {
        Set<Integer> cols = new HashSet<>();
        for (CellEvidence cell : rowCells) {
            cols.add(cell.colNum());
        }
        return cols;
    }

    private static Set<Integer> union(Set<Integer> a, Set<Integer> b) {
        Set<Integer> out = new HashSet<>(a);
        out.addAll(b);
        return out;
    }

    private static boolean columnBandsCompatible(Set<Integer> a, Set<Integer> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return true;
        }
        Set<Integer> inter = new HashSet<>(a);
        inter.retainAll(b);
        if (!inter.isEmpty()) {
            return true;
        }
        int aMin = a.stream().mapToInt(Integer::intValue).min().orElse(0);
        int aMax = a.stream().mapToInt(Integer::intValue).max().orElse(0);
        int bMin = b.stream().mapToInt(Integer::intValue).min().orElse(0);
        int bMax = b.stream().mapToInt(Integer::intValue).max().orElse(0);
        // Ranges overlap or touch within one column — still compatible for soft merge.
        return aMin <= bMax + 1 && bMin <= aMax + 1;
    }

    private static NarrowCandidate toCandidate(
            String kind,
            List<CellEvidence> cells,
            double confidence,
            String rationale,
            String explanation) {
        List<Long> ids = new ArrayList<>(cells.size());
        int minRow = Integer.MAX_VALUE;
        int minCol = Integer.MAX_VALUE;
        int maxRow = Integer.MIN_VALUE;
        int maxCol = Integer.MIN_VALUE;
        for (CellEvidence cell : cells) {
            ids.add(cell.cellId());
            minRow = Math.min(minRow, cell.rowNum());
            minCol = Math.min(minCol, cell.colNum());
            maxRow = Math.max(maxRow, cell.rowNum());
            maxCol = Math.max(maxCol, cell.colNum());
        }
        ids.sort(Long::compareTo);
        return new NarrowCandidate(
                kind, ids, minRow, minCol, maxRow, maxCol, confidence, rationale, explanation);
    }

    private static List<NarrowCandidate> dedupeExactMembership(List<NarrowCandidate> candidates) {
        Map<String, NarrowCandidate> byMembers = new LinkedHashMap<>();
        for (NarrowCandidate candidate : candidates) {
            String key = candidate.kind() + ":" + candidate.memberCellIds();
            byMembers.putIfAbsent(key, candidate);
        }
        List<NarrowCandidate> out = new ArrayList<>(byMembers.values());
        out.sort(Comparator
                .comparing(NarrowCandidate::kind)
                .thenComparingInt(NarrowCandidate::bboxMinRow)
                .thenComparingInt(NarrowCandidate::bboxMinCol));
        return out;
    }

    private record ColBand(int minCol, int maxCol, List<CellEvidence> cells) {
    }
}
