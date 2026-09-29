package com.resurgent.tev.parser.ingest;

/** One worksheet column's drawn width, in Excel's 1/256-character units. */
public record ColumnWidth(int colNum, int width) {
}
