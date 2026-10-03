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
