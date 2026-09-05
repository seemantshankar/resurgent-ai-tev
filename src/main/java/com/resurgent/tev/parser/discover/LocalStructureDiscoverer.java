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
            String internalWhitespaceJson,
            String anchorsJson,
            String structuralSignaturesJson,
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
                List<List<Integer>> hardBlocks = hardContiguousBlocks(segmentRows, byRow);
                // #98: stacked isomorphic schedule blocks across a blank gap stay siblings
                // unless multi-signal continuity says they are one form-like cluster.
                if (shouldEmitStackedScheduleSiblings(hardBlocks, byRow)) {
                    for (List<Integer> blockRows : hardBlocks) {
                        List<CellEvidence> blockCells = cellsInRows(byRow, blockRows);
                        if (blockCells.size() < 2) {
                            continue;
                        }
                        result.add(toCandidate(
                                "child",
                                blockCells,
                                0.75,
                                "stacked schedule sibling separated by a soft blank gap",
                                "Sibling schedule child; resemblance is not physical identity"));
                    }
                } else {
                    result.add(toCandidate(
                            "child",
                            segmentCells,
                            0.8,
                            "soft-blank vertical cluster with compatible column occupancy",
                            "Local child spanning compatible rows with soft blank separators"));
                    // Nested section children only when blank gaps coincide with a signature
                    // change — blank bands alone must not split (Rules §5.5 / #91).
                    if (hardBlocks.size() >= 2) {
                        for (int i = 0; i < hardBlocks.size(); i++) {
                            List<Integer> blockRows = hardBlocks.get(i);
                            List<CellEvidence> blockCells = cellsInRows(byRow, blockRows);
                            if (blockCells.size() < 2) {
                                continue;
                            }
                            boolean distinctFromNeighbor = false;
                            if (i > 0) {
                                distinctFromNeighbor = !rowSignatureCompatible(
                                        cellsInRows(byRow, hardBlocks.get(i - 1)), blockCells);
                            }
                            if (i + 1 < hardBlocks.size()) {
                                distinctFromNeighbor = distinctFromNeighbor
                                        || !rowSignatureCompatible(
                                                blockCells,
                                                cellsInRows(byRow, hardBlocks.get(i + 1)));
                            }
                            if (!distinctFromNeighbor) {
                                continue;
                            }
                            result.add(toCandidate(
                                    "child",
                                    blockCells,
                                    0.7,
                                    "contiguous row block with a changed layout signature across a soft gap",
                                    "Nested section child under a wide local parent"));
                        }
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
            boolean compatible = columnBandsCompatible(currentCols, nextCols)
                    || isBlankLabelContinuation(byRow.get(occupiedRows.get(i - 1)), byRow.get(row));
            if (compatible) {
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
        // Require each band to look like a real side-by-side fragment (multiple cells).
        List<ColBand> substantial = bands.stream().filter(b -> b.cells().size() >= 2).toList();
        return substantial.size() >= 2 ? substantial : List.of(new ColBand(min, max, segmentCells));
    }

    private static boolean rowSignatureCompatible(
            List<CellEvidence> left, List<CellEvidence> right) {
        // Nested split / sibling checks use occupancy + value-type shape only.
        // Blank-label continuation is for soft vertical merge of adjacent detail rows (#100),
        // not for suppressing a multi-row table Candidate next to a sparse helper row.
        return columnBandsCompatible(occupiedCols(left), occupiedCols(right))
                && typePattern(left).equals(typePattern(right));
    }

    /**
     * #98 — two+ multi-row hard blocks that are near-isomorphic across blank gaps become
     * sibling Candidates. Single-row form fields with soft blanks stay soft-merged (#91).
     */
    private static boolean shouldEmitStackedScheduleSiblings(
            List<List<Integer>> hardBlocks, Map<Integer, List<CellEvidence>> byRow) {
        if (hardBlocks.size() < 2) {
            return false;
        }
        List<List<CellEvidence>> substantial = new ArrayList<>();
        for (List<Integer> blockRows : hardBlocks) {
            if (blockRows.size() < 2) {
                return false;
            }
            List<CellEvidence> cells = cellsInRows(byRow, blockRows);
            if (cells.size() < 4) {
                return false;
            }
            substantial.add(cells);
        }
        for (int i = 1; i < substantial.size(); i++) {
            if (!rowSignatureCompatible(substantial.get(i - 1), substantial.get(i))) {
                return false;
            }
            // Require an actual blank gap between blocks (not contiguous).
            int prevLast = hardBlocks.get(i - 1).get(hardBlocks.get(i - 1).size() - 1);
            int nextFirst = hardBlocks.get(i).get(0);
            if (nextFirst <= prevLast + 1) {
                return false;
            }
        }
        return true;
    }

    /**
     * #100 — immediately following row continues value columns while omitting the leftmost
     * label column. Only adjacent occupied rows (no blank-row gap).
     */
    private static boolean isBlankLabelContinuation(
            List<CellEvidence> prevRow, List<CellEvidence> nextRow) {
        if (prevRow == null || nextRow == null || prevRow.isEmpty() || nextRow.isEmpty()) {
            return false;
        }
        int prevNum = prevRow.get(0).rowNum();
        int nextNum = nextRow.get(0).rowNum();
        if (nextNum != prevNum + 1) {
            return false;
        }
        TreeSet<Integer> prevCols = new TreeSet<>(occupiedCols(prevRow));
        TreeSet<Integer> nextCols = new TreeSet<>(occupiedCols(nextRow));
        if (prevCols.size() < 2 || nextCols.isEmpty()) {
            return false;
        }
        int labelCol = prevCols.first();
        if (nextCols.contains(labelCol)) {
            return false;
        }
        TreeSet<Integer> prevValues = new TreeSet<>(prevCols);
        prevValues.remove(labelCol);
        if (prevValues.isEmpty()) {
            return false;
        }
        // Value columns of the continuation are a non-empty subset of the opener's value cols.
        return prevValues.containsAll(nextCols);
    }

    private static String typePattern(List<CellEvidence> cells) {
        // Per-column multiset of coarse types — layout shape, not labels.
        TreeMap<Integer, TreeMap<String, Integer>> byCol = new TreeMap<>();
        for (CellEvidence cell : cells) {
            byCol.computeIfAbsent(cell.colNum(), c -> new TreeMap<>())
                    .merge(coarseType(cell.valueType()), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, TreeMap<String, Integer>> e : byCol.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey()).append(':');
            sb.append(e.getValue());
        }
        return sb.toString();
    }

    private static String coarseType(String valueType) {
        if (valueType == null) {
            return "?";
        }
        return switch (valueType) {
            case "number", "bool", "date" -> "N";
            case "formula", "error" -> "F";
            case "text", "quantity_text" -> "T";
            default -> "O";
        };
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
        Set<Long> occupied = CandidateStructuralEvidence.occupiedPackedCoords(cells);
        return new NarrowCandidate(
                kind,
                ids,
                minRow,
                minCol,
                maxRow,
                maxCol,
                CandidateStructuralEvidence.internalWhitespaceJson(
                        minRow, minCol, maxRow, maxCol, occupied),
                CandidateStructuralEvidence.anchorsJson(cells),
                CandidateStructuralEvidence.structuralSignaturesJson(cells),
                confidence,
                rationale,
                explanation);
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
