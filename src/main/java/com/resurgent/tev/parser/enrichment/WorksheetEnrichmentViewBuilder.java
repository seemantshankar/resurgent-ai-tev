package com.resurgent.tev.parser.enrichment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/** Builds grid view, cell index, and island hints from an unhidden enrichment workbook tab. */
public final class WorksheetEnrichmentViewBuilder {

    static final int MAX_SPARSE_GRID_ROWS = 250;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public WorksheetEnrichmentView build(Path workbookPath, String sheetName) throws IOException {
        DataFormatter formatter = new DataFormatter();
        Map<String, CellRecord> cells = new LinkedHashMap<>();
        Map<String, String> mergeAnchors = new HashMap<>();

        try (InputStream input = Files.newInputStream(workbookPath);
                Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                throw new IllegalArgumentException("sheet not found: " + sheetName);
            }
            for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
                CellRangeAddress region = sheet.getMergedRegion(i);
                String anchor = address(region.getFirstRow(), region.getFirstColumn());
                String range = address(region.getFirstRow(), region.getFirstColumn())
                        + ":" + address(region.getLastRow(), region.getLastColumn());
                mergeAnchors.put(anchor, range);
            }
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (!isFilled(cell, formatter)) {
                        continue;
                    }
                    String coord = address(cell.getRowIndex(), cell.getColumnIndex());
                    String display = formatter.formatCellValue(cell);
                    String formula = cell.getCellType() == CellType.FORMULA
                            ? cell.getCellFormula()
                            : null;
                    String kind = cell.getCellType() == CellType.FORMULA
                            ? "formula"
                            : cell.getCellType() == CellType.STRING
                                    || cell.getCellType() == CellType.BOOLEAN
                                    ? "text"
                                    : "amount";
                    cells.put(coord, new CellRecord(
                            cell.getRowIndex() + 1,
                            cell.getColumnIndex(),
                            kind,
                            display,
                            formula,
                            mergeAnchors.get(coord)));
                }
            }
        }

        if (cells.isEmpty()) {
            return new WorksheetEnrichmentView(
                    0, 0, 0, 0, 0, "", "", "", List.of());
        }

        int minRow = cells.values().stream().mapToInt(CellRecord::row).min().orElse(1);
        int maxRow = cells.values().stream().mapToInt(CellRecord::row).max().orElse(1);
        int minCol = cells.values().stream().mapToInt(CellRecord::col).min().orElse(0);
        int maxCol = cells.values().stream().mapToInt(CellRecord::col).max().orElse(0);

        List<IslandHint> islands = new FilledCellIslandDetector().detect(cells.keySet());
        String cellIndexNdjson = toCellIndexNdjson(cells);
        String columnHeaderLine = columnHeader(minCol, maxCol);
        String sparseGrid = sparseGrid(cells, minRow, maxRow, minCol, maxCol);

        return new WorksheetEnrichmentView(
                cells.size(),
                minRow,
                maxRow,
                minCol,
                maxCol,
                columnHeaderLine,
                sparseGrid,
                cellIndexNdjson,
                islands);
    }

    private static String columnHeader(int minCol, int maxCol) {
        StringBuilder header = new StringBuilder("Columns:");
        for (int col = minCol; col <= maxCol; col++) {
            header.append(' ').append(CellReference.convertNumToColString(col));
        }
        return header.toString();
    }

    private static String sparseGrid(
            Map<String, CellRecord> cells,
            int minRow,
            int maxRow,
            int minCol,
            int maxCol) {
        StringBuilder grid = new StringBuilder();
        int rowsWritten = 0;
        boolean truncated = false;
        for (int row = minRow; row <= maxRow; row++) {
            List<String> parts = new ArrayList<>();
            boolean rowHasContent = false;
            for (int col = minCol; col <= maxCol; col++) {
                String coord = address(row - 1, col);
                CellRecord record = cells.get(coord);
                if (record != null) {
                    rowHasContent = true;
                    String merged = record.mergedRange() == null
                            ? ""
                            : " merged=" + record.mergedRange();
                    parts.add(coord + ":" + escapeGridValue(record.display()) + merged);
                } else {
                    parts.add("");
                }
            }
            if (!rowHasContent) {
                continue;
            }
            if (rowsWritten >= MAX_SPARSE_GRID_ROWS) {
                truncated = true;
                break;
            }
            grid.append("Row ").append(row).append(" | ");
            grid.append(String.join(" | ", parts));
            grid.append('\n');
            rowsWritten++;
        }
        if (truncated) {
            grid.append("(Grid truncated at ")
                    .append(MAX_SPARSE_GRID_ROWS)
                    .append(" content rows; use cell index and island hints for remaining cells.)")
                    .append('\n');
        }
        return grid.toString();
    }

    private static String toCellIndexNdjson(Map<String, CellRecord> cells) throws IOException {
        StringBuilder ndjson = new StringBuilder();
        for (Map.Entry<String, CellRecord> entry : cells.entrySet()) {
            CellRecord record = entry.getValue();
            CellIndexEntry indexEntry = new CellIndexEntry(
                    entry.getKey(),
                    record.row(),
                    record.col() + 1,
                    record.kind(),
                    record.display(),
                    record.formula(),
                    record.mergedRange());
            try {
                ndjson.append(MAPPER.writeValueAsString(indexEntry)).append('\n');
            } catch (JsonProcessingException e) {
                throw new IOException("failed serializing cell index entry " + entry.getKey(), e);
            }
        }
        return ndjson.toString();
    }

    private static boolean isFilled(Cell cell, DataFormatter formatter) {
        return cell.getCellType() == CellType.FORMULA
                || (cell.getCellType() != CellType.BLANK
                        && !formatter.formatCellValue(cell).isBlank());
    }

    private static String address(int row, int column) {
        return CellReference.convertNumToColString(column) + (row + 1);
    }

    private static String escapeGridValue(String value) {
        return value.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\t", "\\t")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private record CellRecord(
            int row,
            int col,
            String kind,
            String display,
            String formula,
            String mergedRange) {}
}
