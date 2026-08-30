package com.resurgent.tev.parser.enrichment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Reads filled cells and same-sheet formula dependencies from an .xlsx tab. */
public final class WorksheetSnapshotReader {

    private static final Pattern CELL_OR_RANGE = Pattern.compile(
            "(?<![A-Za-z0-9_.])(?:(?:'((?:[^']|'')+)'|([A-Za-z_][A-Za-z0-9_.]*))!)?"
                    + "\\$?([A-Z]{1,3})\\$?([1-9][0-9]*)"
                    + "(?::\\$?([A-Z]{1,3})\\$?([1-9][0-9]*))?");

    public WorksheetSnapshot read(Path workbookPath, String sheetName) throws IOException {
        Set<String> filledCells = new LinkedHashSet<>();
        Map<String, Set<String>> references = new LinkedHashMap<>();
        DataFormatter formatter = new DataFormatter();

        try (InputStream input = Files.newInputStream(workbookPath);
                XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                throw new IllegalArgumentException("sheet not found: " + sheetName);
            }
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (!isFilled(cell, formatter)) {
                        continue;
                    }
                    String address = address(cell.getRowIndex(), cell.getColumnIndex());
                    filledCells.add(address);
                    if (cell.getCellType() == CellType.FORMULA) {
                        references.put(address, references(cell.getCellFormula(), sheetName));
                    }
                }
            }
        }
        return new WorksheetSnapshot(filledCells, references);
    }

    private static boolean isFilled(Cell cell, DataFormatter formatter) {
        return cell.getCellType() == CellType.FORMULA
                || (cell.getCellType() != CellType.BLANK
                        && !formatter.formatCellValue(cell).isBlank());
    }

    private static Set<String> references(String formula, String currentSheet) {
        Set<String> references = new LinkedHashSet<>();
        Matcher matcher = CELL_OR_RANGE.matcher(formula);
        while (matcher.find()) {
            String quotedSheet = matcher.group(1);
            String bareSheet = matcher.group(2);
            String referencedSheet = quotedSheet != null
                    ? quotedSheet.replace("''", "'")
                    : bareSheet;
            if (referencedSheet != null && !referencedSheet.equals(currentSheet)) {
                continue;
            }
            CellReference first = new CellReference(matcher.group(3) + matcher.group(4));
            CellReference last = matcher.group(5) == null
                    ? first
                    : new CellReference(matcher.group(5) + matcher.group(6));
            for (int row = first.getRow(); row <= last.getRow(); row++) {
                for (int column = first.getCol(); column <= last.getCol(); column++) {
                    references.add(address(row, column));
                }
            }
        }
        return Set.copyOf(references);
    }

    private static String address(int row, int column) {
        return CellReference.convertNumToColString(column) + (row + 1);
    }
}
