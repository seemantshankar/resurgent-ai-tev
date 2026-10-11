package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.CellPacketView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the numbers no region claimed. The model's layout can skip part of a sheet (a data block
 * whose labels it boxed on their own, a calculation pad below the main schedule); those cells
 * would then have no region description and no header geometry. Every sizeable leftover block
 * becomes a region of its own, so it goes through the same Layer A and geometry steps as the rest.
 */
final class ResidualRegions {

    /** Blocks with fewer numbers than this are scattered constants, not a table. */
    static final int MIN_NUMBERS = 3;
    /** Cells within this many rows or columns of each other (one blank between) belong together. */
    private static final int REACH = 2;

    /** A leftover block: its bounding box and the unclaimed cells in it. */
    record Block(int minRow, int minCol, int maxRow, int maxCol, List<Long> memberIds) {}

    /** A region's bounding box (1-based, inclusive) and the ids of the cells it owns. */
    record Extent(int minRow, int minCol, int maxRow, int maxCol, Set<Long> memberIds) {}

    /** Edges grow at most this many times: a unit column, then a second one beside it. */
    private static final int MAX_FRINGE_ROUNDS = 4;

    private ResidualRegions() {}

    /** The leftover blocks of one sheet, given the ids of the cells some region already owns. */
    static List<Block> find(List<CellPacketView> sheetCells, Set<Long> owned) {
        Map<Long, CellPacketView> free = new HashMap<>();
        Map<Long, CellPacketView> byPosition = new HashMap<>();
        for (CellPacketView cell : sheetCells) {
            if ("empty".equals(cell.valueType()) || owned.contains(cell.cellId())) {
                continue;
            }
            free.put(cell.cellId(), cell);
            byPosition.put(position(cell.rowNum(), cell.colNum()), cell);
        }
        List<Block> blocks = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (CellPacketView start : sheetCells) {
            if (!free.containsKey(start.cellId()) || !seen.add(start.cellId())) {
                continue;
            }
            List<CellPacketView> component = new ArrayList<>();
            List<CellPacketView> stack = new ArrayList<>(List.of(start));
            while (!stack.isEmpty()) {
                CellPacketView cell = stack.remove(stack.size() - 1);
                component.add(cell);
                for (int dr = -REACH; dr <= REACH; dr++) {
                    for (int dc = -REACH; dc <= REACH; dc++) {
                        CellPacketView next = byPosition.get(position(cell.rowNum() + dr, cell.colNum() + dc));
                        if (next != null && seen.add(next.cellId())) {
                            stack.add(next);
                        }
                    }
                }
            }
            blockOf(component).ifPresent(blocks::add);
        }
        return blocks;
    }

    /**
     * Units, remarks and status text that hug a region's edge belong to it. The model draws boxes
     * around the numbers and sometimes stops one column short of the units beside them, and a
     * cell with no unit next to it reads as a bare number. A run of unowned, non-numeric cells
     * on the line just outside a region's edge joins that region when the whole run lies within
     * the region's extent on that edge: a column of units no taller than the table, a note row no
     * wider than it. A run that keeps going past the region, or is made of numbers, is a table of
     * its own and is left alone. Edges are tried again after each gain, so a second column joins.
     * Returns the regions in the order given, expanded, with the absorbed cells among their members.
     */
    static List<Extent> absorbFringe(List<CellPacketView> sheetCells, List<Extent> regions) {
        Set<Long> owned = new HashSet<>();
        for (Extent region : regions) {
            owned.addAll(region.memberIds());
        }
        Map<Long, CellPacketView> free = new HashMap<>();
        for (CellPacketView cell : sheetCells) {
            if (!"empty".equals(cell.valueType()) && !owned.contains(cell.cellId())) {
                free.put(cell.cellId(), cell);
            }
        }
        List<Extent> grown = new ArrayList<>();
        for (Extent region : regions) {
            int minRow = region.minRow();
            int minCol = region.minCol();
            int maxRow = region.maxRow();
            int maxCol = region.maxCol();
            Set<Long> members = new HashSet<>(region.memberIds());
            for (int round = 0; round < MAX_FRINGE_ROUNDS; round++) {
                boolean gained = false;
                for (int side = 0; side < 4; side++) {
                    boolean horizontal = side < 2; // left / right edges are columns, top / bottom are rows
                    int line = switch (side) {
                        case 0 -> minCol - 1;
                        case 1 -> maxCol + 1;
                        case 2 -> minRow - 1;
                        default -> maxRow + 1;
                    };
                    int spanMin = horizontal ? minRow : minCol;
                    int spanMax = horizontal ? maxRow : maxCol;
                    List<CellPacketView> added = fringeRun(free, line, horizontal, spanMin, spanMax);
                    if (added.isEmpty()) {
                        continue;
                    }
                    for (CellPacketView cell : added) {
                        members.add(cell.cellId());
                        free.remove(cell.cellId());
                    }
                    switch (side) {
                        case 0 -> minCol = line;
                        case 1 -> maxCol = line;
                        case 2 -> minRow = line;
                        default -> maxRow = line;
                    }
                    gained = true;
                }
                if (!gained) {
                    break;
                }
            }
            grown.add(new Extent(minRow, minCol, maxRow, maxCol, members));
        }
        return grown;
    }

    /** Unowned cells on one line that form a text-only run lying wholly inside [spanMin, spanMax]. */
    private static List<CellPacketView> fringeRun(
            Map<Long, CellPacketView> free, int line, boolean column, int spanMin, int spanMax) {
        List<CellPacketView> onLine = new ArrayList<>();
        for (CellPacketView cell : free.values()) {
            if ((column ? cell.colNum() : cell.rowNum()) == line) {
                onLine.add(cell);
            }
        }
        onLine.sort(java.util.Comparator.comparingInt(c -> column ? c.rowNum() : c.colNum()));
        List<CellPacketView> accepted = new ArrayList<>();
        List<CellPacketView> run = new ArrayList<>();
        for (int i = 0; i <= onLine.size(); i++) {
            CellPacketView cell = i < onLine.size() ? onLine.get(i) : null;
            boolean continues = cell != null && !run.isEmpty()
                    && position(cell, column) - position(run.get(run.size() - 1), column) <= REACH - 1;
            if (cell != null && (run.isEmpty() || continues)) {
                run.add(cell);
                continue;
            }
            if (qualifies(run, column, spanMin, spanMax)) {
                accepted.addAll(run);
            }
            run = new ArrayList<>();
            if (cell != null) {
                run.add(cell);
            }
        }
        return accepted;
    }

    private static boolean qualifies(List<CellPacketView> run, boolean column, int spanMin, int spanMax) {
        if (run.isEmpty()) {
            return false;
        }
        int numbers = 0;
        for (CellPacketView cell : run) {
            int at = position(cell, column);
            if (at < spanMin || at > spanMax) {
                return false; // runs on past the region: a table of its own
            }
            if ("number".equals(cell.valueType())) {
                numbers++;
            }
        }
        return numbers < MIN_NUMBERS;
    }

    private static int position(CellPacketView cell, boolean column) {
        return column ? cell.rowNum() : cell.colNum();
    }

    private static java.util.Optional<Block> blockOf(List<CellPacketView> component) {
        int numbers = 0;
        int minRow = Integer.MAX_VALUE;
        int minCol = Integer.MAX_VALUE;
        int maxRow = 0;
        int maxCol = 0;
        List<Long> ids = new ArrayList<>();
        for (CellPacketView cell : component) {
            if ("number".equals(cell.valueType())) {
                numbers++;
            }
            minRow = Math.min(minRow, cell.rowNum());
            minCol = Math.min(minCol, cell.colNum());
            maxRow = Math.max(maxRow, cell.rowNum());
            maxCol = Math.max(maxCol, cell.colNum());
            ids.add(cell.cellId());
        }
        return numbers < MIN_NUMBERS
                ? java.util.Optional.empty()
                : java.util.Optional.of(new Block(minRow, minCol, maxRow, maxCol, ids));
    }

    private static long position(int row, int col) {
        return ((long) row << 32) | (col & 0xffffffffL);
    }
}
