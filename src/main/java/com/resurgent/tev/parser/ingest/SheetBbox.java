package com.resurgent.tev.parser.ingest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;

/**
 * Computes the real worksheet bounding box from occupied cells, merged regions,
 * comment addresses, and same-sheet formula precedents. Shared by the XLSX and
 * XLS adapters.
 */
final class SheetBbox {

    private SheetBbox() {
    }

    static Bbox computeBbox(Sheet sheet, List<NormalizedCell> baseCells,
            List<CellRangeAddress> mergedRegions, List<CellAddress> commentAddresses) {
        Integer minRow = null;
        Integer minCol = null;
        Integer maxRow = null;
        Integer maxCol = null;
        for (NormalizedCell cell : baseCells) {
            if (cell.isPresentationOnlyEmpty()) {
                continue;
            }
            minRow = min(minRow, cell.rowNum());
            minCol = min(minCol, cell.colNum());
            maxRow = max(maxRow, cell.rowNum());
            maxCol = max(maxCol, cell.colNum());
        }
        for (CellRangeAddress region : mergedRegions) {
            minRow = min(minRow, region.getFirstRow() + 1);
            minCol = min(minCol, region.getFirstColumn() + 1);
            maxRow = max(maxRow, region.getLastRow() + 1);
            maxCol = max(maxCol, region.getLastColumn() + 1);
        }
        for (CellAddress address : commentAddresses) {
            minRow = min(minRow, address.getRow() + 1);
            minCol = min(minCol, address.getColumn() + 1);
            maxRow = max(maxRow, address.getRow() + 1);
            maxCol = max(maxCol, address.getColumn() + 1);
        }
        for (CellReference ref : sameSheetPrecedents(sheet.getSheetName(), baseCells)) {
            minRow = min(minRow, ref.getRow() + 1);
            minCol = min(minCol, ref.getCol() + 1);
            maxRow = max(maxRow, ref.getRow() + 1);
            maxCol = max(maxCol, ref.getCol() + 1);
        }
        return new Bbox(minRow, minCol, maxRow, maxCol);
    }

    static int countContentRows(List<NormalizedCell> cells) {
        Set<Integer> rows = new HashSet<>();
        for (NormalizedCell cell : cells) {
            if ("cell".equals(cell.valueSource()) && !cell.isPresentationOnlyEmpty()) {
                rows.add(cell.rowNum());
            }
        }
        return rows.size();
    }

    private static List<CellReference> sameSheetPrecedents(String sheetName,
            List<NormalizedCell> baseCells) {
        List<CellReference> refs = new ArrayList<>();
        for (NormalizedCell cell : baseCells) {
            if (cell.formulaText() == null || cell.formulaText().isBlank()) {
                continue;
            }
            refs.addAll(FormulaReferenceExtractor.extractLocalRefs(
                    cell.formulaText(), sheetName));
        }
        return refs;
    }

    private static Integer min(Integer current, int candidate) {
        return current == null ? candidate : Math.min(current, candidate);
    }

    private static Integer max(Integer current, int candidate) {
        return current == null ? candidate : Math.max(current, candidate);
    }

    record Bbox(Integer minRow, Integer minCol, Integer maxRow, Integer maxCol) {
    }
}
