package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.WorksheetRef;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Resolves sheet-qualified or local A1 coords against a parse run's worksheets. */
final class PeerCoordResolver {

    private PeerCoordResolver() {}

    static Optional<Long> resolveCellId(
            List<WorksheetRef> worksheets,
            Map<Long, Map<String, Long>> cellIdsByWorksheetAndCoord,
            long defaultWorksheetId,
            String qualifiedCoord) {
        if (qualifiedCoord == null || qualifiedCoord.isBlank()) {
            return Optional.empty();
        }
        String trimmed = qualifiedCoord.trim();
        int bang = trimmed.indexOf('!');
        long worksheetId;
        String coord;
        if (bang >= 0) {
            String sheetName = trimmed.substring(0, bang).trim();
            coord = trimmed.substring(bang + 1).trim().toUpperCase(Locale.ROOT);
            worksheetId = matchWorksheet(worksheets, sheetName).orElse(-1L);
            if (worksheetId < 0) {
                return Optional.empty();
            }
        } else {
            worksheetId = defaultWorksheetId;
            coord = trimmed.toUpperCase(Locale.ROOT);
        }
        Map<String, Long> byCoord = cellIdsByWorksheetAndCoord.get(worksheetId);
        if (byCoord == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byCoord.get(coord));
    }

    private static Optional<Long> matchWorksheet(List<WorksheetRef> worksheets, String sheetName) {
        String needle = sheetName.toLowerCase(Locale.ROOT);
        for (WorksheetRef worksheet : worksheets) {
            if (worksheet.sheetName().toLowerCase(Locale.ROOT).equals(needle)) {
                return Optional.of(worksheet.worksheetId());
            }
        }
        return Optional.empty();
    }

    static Map<Long, Map<String, Long>> indexCells(
            List<WorksheetRef> worksheets,
            PeerCellIndexLoader loader) throws PeerCoordException {
        try {
            Map<Long, Map<String, Long>> index = new java.util.HashMap<>();
            for (WorksheetRef worksheet : worksheets) {
                index.put(
                        worksheet.worksheetId(),
                        loader.loadForWorksheet(worksheet.worksheetId()).stream()
                                .collect(Collectors.toMap(
                                        ref -> ref.coord().toUpperCase(Locale.ROOT),
                                        PeerCellRef::cellId,
                                        (a, b) -> a)));
            }
            return index;
        } catch (Exception e) {
            throw new PeerCoordException("failed to index cells for peer resolution", e);
        }
    }

    record PeerCellRef(long cellId, String coord) {}

    @FunctionalInterface
    interface PeerCellIndexLoader {
        List<PeerCellRef> loadForWorksheet(long worksheetId) throws Exception;
    }

    static final class PeerCoordException extends Exception {
        PeerCoordException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
