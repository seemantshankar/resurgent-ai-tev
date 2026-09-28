package com.resurgent.tev.parser.ingest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellAddress;

/** Column widths and cell notes read during ingest. */
final class SheetCapture {

    private SheetCapture() {}

    static NormalizedCell attachComment(Cell source, NormalizedCell cell) {
        if (cell == null || source == null) {
            return cell;
        }
        Comment comment = source.getCellComment();
        if (comment == null || comment.getString() == null) {
            return cell;
        }
        String body = comment.getString().getString();
        if (body == null || body.isBlank()) {
            return cell;
        }
        return cell.withComment(comment.getAuthor(), body);
    }

    /** Blank cells that exist only because they carry a note. */
    static void addCommentOnlyCells(Sheet sheet, List<NormalizedCell> cells, boolean sheetHidden) {
        Set<String> present = new HashSet<>();
        for (NormalizedCell cell : cells) {
            present.add(cell.coord());
        }
        for (CellAddress address : commentAddresses(sheet)) {
            String coord = CellGeometry.coord(address.getRow(), address.getColumn());
            if (present.contains(coord)) {
                continue;
            }
            Row row = sheet.getRow(address.getRow());
            Cell source = row == null ? null : row.getCell(address.getColumn());
            if (source == null) {
                continue;
            }
            boolean rowHidden = row.getZeroHeight();
            boolean colHidden = sheet.isColumnHidden(address.getColumn());
            NormalizedCell blank = NormalizedCellFactory.buildStyledBlank(
                    coord, address.getRow() + 1, address.getColumn() + 1,
                    rowHidden, colHidden, sheetHidden, null);
            NormalizedCell noted = attachComment(source, blank);
            if (noted.commentBody() != null) {
                cells.add(noted);
            }
        }
    }

    static List<ColumnWidth> columnWidths(Sheet sheet, List<NormalizedCell> cells) {
        int maxCol = 0;
        for (NormalizedCell cell : cells) {
            maxCol = Math.max(maxCol, cell.colNum());
        }
        List<ColumnWidth> widths = new ArrayList<>();
        for (int colNum = 1; colNum <= maxCol; colNum++) {
            widths.add(new ColumnWidth(colNum, sheet.getColumnWidth(colNum - 1)));
        }
        return widths;
    }

    private static List<CellAddress> commentAddresses(Sheet sheet) {
        if (sheet.getCellComments() == null) {
            return List.of();
        }
        return new ArrayList<>(sheet.getCellComments().keySet());
    }
}
