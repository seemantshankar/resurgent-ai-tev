package com.resurgent.tev.parser.enrichment;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Builds throwaway LLM input from a redacted workbook without changing the
 * source file.
 */
public final class TemporaryUnhiddenCopyBuilder {

    /**
     * Copies an {@code .xlsx} workbook to a temporary file and makes every row
     * and column on the named sheet visible. The caller owns the returned file
     * and is responsible for deleting it.
     */
    public Path build(Path redactedWorkbook, String sheetName) throws IOException {
        requireXlsx(redactedWorkbook);

        Path copy = Files.createTempFile("tev-enrichment-unhidden-", ".xlsx");
        boolean complete = false;
        try (InputStream input = Files.newInputStream(redactedWorkbook);
                XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                throw new IllegalArgumentException("sheet not found: " + sheetName);
            }

            unhideRows(sheet);
            unhideColumns(sheet);
            try (OutputStream output = Files.newOutputStream(copy)) {
                workbook.write(output);
            }
            complete = true;
            return copy;
        } finally {
            if (!complete) {
                Files.deleteIfExists(copy);
            }
        }
    }

    private static void requireXlsx(Path redactedWorkbook) throws IOException {
        if (!Files.isRegularFile(redactedWorkbook)) {
            throw new IOException("input file not found: " + redactedWorkbook);
        }
        String fileName = redactedWorkbook.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!fileName.endsWith(".xlsx")) {
            throw new IllegalArgumentException(
                    "temporary unhidden copy supports .xlsx only: " + redactedWorkbook);
        }
    }

    private static void unhideRows(Sheet sheet) {
        for (Row row : sheet) {
            if (row.getZeroHeight()) {
                row.setZeroHeight(false);
            }
        }
    }

    private static void unhideColumns(Sheet sheet) {
        int lastColumn = SpreadsheetVersion.EXCEL2007.getLastColumnIndex();
        for (int column = 0; column <= lastColumn; column++) {
            if (sheet.isColumnHidden(column)) {
                sheet.setColumnHidden(column, false);
            }
        }
    }
}
