package com.resurgent.tev.parser.ingest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds a denormalized row identity from the row's leading text cells.
 *
 * <p>A section stub such as {@code (A)} is kept and joined to the description
 * that follows it. A lone description is used as-is, so a header row of
 * {@code Metric | Year1} does not become one label. Numeric serials and later
 * note columns are omitted.
 */
final class RowLabelComposer {

    private static final Pattern SECTION_STUB = Pattern.compile("\\([A-Za-z0-9]{1,4}\\)");

    private RowLabelComposer() {}

    static String compose(List<NormalizedCell> rowCells) {
        List<String> parts = new ArrayList<>();
        boolean started = false;
        boolean joinRun = false;
        List<NormalizedCell> ordered = rowCells.stream()
                .sorted(Comparator.comparingInt(NormalizedCell::colNum))
                .toList();
        for (NormalizedCell cell : ordered) {
            if (cell.isMergedParticipant()) {
                continue;
            }
            String label = textTyped(cell);
            if (label != null) {
                if (!started) {
                    parts.add(label);
                    started = true;
                    joinRun = isSectionStub(label);
                    if (!joinRun) {
                        break;
                    }
                } else {
                    parts.add(label);
                }
            } else if (started) {
                break;
            }
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    private static boolean isSectionStub(String value) {
        return SECTION_STUB.matcher(value).matches();
    }

    private static String textTyped(NormalizedCell cell) {
        if (!"text".equals(cell.valueType()) || cell.displayValue() == null) {
            return null;
        }
        String value = cell.displayValue().trim().replaceAll("\\s+", " ");
        return value.isEmpty() ? null : value;
    }
}
