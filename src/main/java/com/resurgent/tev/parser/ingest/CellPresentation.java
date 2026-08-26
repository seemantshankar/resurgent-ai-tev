package com.resurgent.tev.parser.ingest;

import com.resurgent.tev.parser.db.Jsonb;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Presentation signals read from a source cell's style. {@code null} booleans in the
 * canonical columns mean unavailable; this record uses the same convention for optional
 * extras serialized into {@code cell.tags}.
 */
record CellPresentation(
        Boolean bold,
        Boolean italic,
        Boolean underline,
        Boolean hasFill,
        Boolean hasBorder,
        String numberFormat,
        Short fontColorIndex,
        String fontColorRgb,
        Short fillForegroundColorIndex,
        String fillForegroundRgb,
        String fillPattern) {

    private static final short DEFAULT_FONT_COLOR_INDEX = 8;
    private static final short AUTOMATIC_FONT_COLOR_INDEX = 32767;
    private static final short AUTOMATIC_FILL_COLOR_INDEX = 64;

    boolean hasPresentationSignals() {
        if (Boolean.TRUE.equals(bold) || Boolean.TRUE.equals(italic) || Boolean.TRUE.equals(underline)) {
            return true;
        }
        if (Boolean.TRUE.equals(hasFill) || Boolean.TRUE.equals(hasBorder)) {
            return true;
        }
        if (numberFormat != null && !numberFormat.isBlank()
                && !"General".equalsIgnoreCase(numberFormat.trim())) {
            return true;
        }
        if (fontColorRgb != null && !fontColorRgb.isBlank()) {
            return true;
        }
        if (fillForegroundRgb != null && !fillForegroundRgb.isBlank()) {
            return true;
        }
        if (fontColorIndex != null
                && fontColorIndex != DEFAULT_FONT_COLOR_INDEX
                && fontColorIndex != AUTOMATIC_FONT_COLOR_INDEX) {
            return true;
        }
        return fillForegroundColorIndex != null
                && Boolean.TRUE.equals(hasFill)
                && fillForegroundColorIndex != AUTOMATIC_FILL_COLOR_INDEX;
    }

    boolean differsFrom(CellPresentation other) {
        if (!java.util.Objects.equals(bold, other.bold)) {
            return true;
        }
        if (!java.util.Objects.equals(italic, other.italic)) {
            return true;
        }
        if (!java.util.Objects.equals(underline, other.underline)) {
            return true;
        }
        if (!java.util.Objects.equals(hasFill, other.hasFill)) {
            return true;
        }
        if (!java.util.Objects.equals(hasBorder, other.hasBorder)) {
            return true;
        }
        if (!sameNumberFormat(numberFormat, other.numberFormat)) {
            return true;
        }
        if (!java.util.Objects.equals(fontColorIndex, other.fontColorIndex)) {
            return true;
        }
        if (!java.util.Objects.equals(fontColorRgb, other.fontColorRgb)) {
            return true;
        }
        if (!java.util.Objects.equals(fillForegroundColorIndex, other.fillForegroundColorIndex)) {
            return true;
        }
        if (!java.util.Objects.equals(fillForegroundRgb, other.fillForegroundRgb)) {
            return true;
        }
        return !java.util.Objects.equals(fillPattern, other.fillPattern);
    }

    /**
     * Visual presentation worth keeping when normalizing whitespace-only strings to blank.
     * Inherited grid borders and column number formats are ignored as noise.
     */
    boolean hasMeaningfulBlankFormatting() {
        if (Boolean.TRUE.equals(bold) || Boolean.TRUE.equals(italic) || Boolean.TRUE.equals(underline)) {
            return true;
        }
        if (Boolean.TRUE.equals(hasFill)) {
            return true;
        }
        if (fontColorRgb != null && !fontColorRgb.isBlank()) {
            return true;
        }
        if (fillForegroundRgb != null && !fillForegroundRgb.isBlank()) {
            return true;
        }
        if (fontColorIndex != null
                && fontColorIndex != DEFAULT_FONT_COLOR_INDEX
                && fontColorIndex != AUTOMATIC_FONT_COLOR_INDEX) {
            return true;
        }
        return fillForegroundColorIndex != null
                && Boolean.TRUE.equals(hasFill)
                && fillForegroundColorIndex != AUTOMATIC_FILL_COLOR_INDEX;
    }

    private static boolean sameNumberFormat(String left, String right) {
        String normalizedLeft = left == null || left.isBlank() ? "General" : left.trim();
        String normalizedRight = right == null || right.isBlank() ? "General" : right.trim();
        return normalizedLeft.equalsIgnoreCase(normalizedRight);
    }

    NormalizedCell apply(NormalizedCell cell) {
        return cell.withPresentation(bold, hasFill, hasBorder, numberFormat, toTagsJson());
    }

    String toTagsJson() {
        Map<String, Object> tags = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(italic)) {
            tags.put("italic", true);
        }
        if (Boolean.TRUE.equals(underline)) {
            tags.put("underline", true);
        }
        if (fontColorIndex != null
                && fontColorIndex != DEFAULT_FONT_COLOR_INDEX
                && fontColorIndex != AUTOMATIC_FONT_COLOR_INDEX) {
            tags.put("fontColorIndex", fontColorIndex);
        }
        if (fontColorRgb != null && !fontColorRgb.isBlank()) {
            tags.put("fontColorRgb", fontColorRgb);
        }
        if (fillForegroundColorIndex != null
                && Boolean.TRUE.equals(hasFill)
                && fillForegroundColorIndex != AUTOMATIC_FILL_COLOR_INDEX) {
            tags.put("fillForegroundColorIndex", fillForegroundColorIndex);
        }
        if (fillForegroundRgb != null && !fillForegroundRgb.isBlank()) {
            tags.put("fillForegroundRgb", fillForegroundRgb);
        }
        if (fillPattern != null && !fillPattern.isBlank() && Boolean.TRUE.equals(hasFill)) {
            tags.put("fillPattern", fillPattern);
        }
        if (tags.isEmpty()) {
            return null;
        }
        try {
            return Jsonb.toJson(tags);
        } catch (IOException e) {
            throw new UncheckedIOException("failed serializing cell presentation tags", e);
        }
    }
}
