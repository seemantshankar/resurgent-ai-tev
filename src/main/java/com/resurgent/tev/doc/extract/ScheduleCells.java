package com.resurgent.tev.doc.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.resurgent.tev.doc.pack.DocTypePack;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns one row of a pack table into fact candidates. A table that names a
 * {@code key_column} is a record schedule or a period matrix: each value cell
 * becomes one candidate with {@code field_key = table.column} and the row key
 * as {@code line_key}. A period-only table (audited headline figures) keeps the
 * column key and the period, with no line key.
 */
public final class ScheduleCells {

    private ScheduleCells() {}

    /** The pack table a {@code table.column} field key belongs to. */
    public record TableRef(String tableName, String column, String path, String pathRoot) {}

    /** Whether the compiled row schema names a line-key column. */
    public static boolean isSchedule(JsonNode itemProps) {
        return columnWith(itemProps, "line_key") != null;
    }

    /**
     * The table that owns {@code fieldKey} when it is {@code table.column} and that
     * table declares a {@code key_column}. Null for scalar fields and for period-only
     * columns such as audited {@code revenue}.
     */
    public static TableRef owner(DocTypePack pack, String fieldKey) {
        if (pack == null || fieldKey == null) {
            return null;
        }
        int dot = fieldKey.indexOf('.');
        if (dot <= 0 || dot == fieldKey.length() - 1) {
            return null;
        }
        String tableName = fieldKey.substring(0, dot);
        String column = fieldKey.substring(dot + 1);
        for (Map<String, Object> table : pack.tables()) {
            if (!tableName.equals(String.valueOf(table.get("name")))) {
                continue;
            }
            if (table.get("key_column") == null) {
                return null;
            }
            Object path = table.get("path");
            Object root = table.get("path_root");
            return new TableRef(tableName, column,
                    path == null ? null : String.valueOf(path),
                    root == null ? "economic" : String.valueOf(root).toLowerCase(Locale.ROOT));
        }
        return null;
    }

    /**
     * Emits one candidate per value cell of {@code row}. Key, entity, site, and
     * period columns are attributes of those cells, not cells of their own.
     */
    public static void expand(String tableName, JsonNode itemProps, JsonNode row,
            List<FactCandidate> facts, List<Citation> citations) {
        if (row == null || row.isNull() || !row.isObject() || itemProps == null) {
            return;
        }
        String keyColumn = columnWith(itemProps, "line_key");
        String entityColumn = columnWith(itemProps, "entity");
        String siteColumn = columnWith(itemProps, "site");
        String periodColumn = columnWith(itemProps, "period");
        boolean schedule = keyColumn != null;
        String lineKey = textOf(row, keyColumn);
        String entity = textOf(row, entityColumn);
        String site = textOf(row, siteColumn);
        String period = textOf(row, periodColumn);

        Iterator<Map.Entry<String, JsonNode>> it = row.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> col = it.next();
            String column = col.getKey();
            if (column.equals(keyColumn) || column.equals(entityColumn)
                    || column.equals(siteColumn) || column.equals(periodColumn)) {
                continue;
            }
            JsonNode cell = col.getValue();
            if (cell == null || cell.isNull()) {
                continue;
            }
            String value = valueOf(cell);
            if (value == null) {
                continue;
            }
            String fieldKey = schedule ? tableName + "." + column : column;
            FactCandidate candidate = new FactCandidate(fieldKey, kindOf(itemProps, column))
                    .valueText(value)
                    .lineKey(schedule ? lineKey : null)
                    .period(period)
                    .subjectEntity(entity)
                    .siteName(site)
                    .confidence(0.95)
                    .extractionMethod("deterministic")
                    .statedOrDerived("stated");
            String quote = quoteOf(cell, value);
            int page = pageOf(cell);
            if (quote != null) {
                Citation citation = Citation.of(page, quote);
                candidate.addCitation(citation);
                citations.add(citation);
            }
            facts.add(candidate);
        }
    }

    private static String columnWith(JsonNode itemProps, String marker) {
        if (itemProps == null || !itemProps.isObject()) {
            return null;
        }
        Iterator<Map.Entry<String, JsonNode>> it = itemProps.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (entry.getValue().path(marker).asBoolean(false)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static ValueKind kindOf(JsonNode props, String key) {
        return SchemaLeafKinds.of(props, key);
    }

    private static String textOf(JsonNode row, String column) {
        if (column == null || row == null || !row.hasNonNull(column)) {
            return null;
        }
        return valueOf(row.get(column));
    }

    private static String valueOf(JsonNode cell) {
        if (cell == null || cell.isNull()) {
            return null;
        }
        if (cell.isObject()) {
            if (!cell.hasNonNull("value")) {
                return null;
            }
            String text = cell.get("value").asText(null);
            return text == null || text.isBlank() ? null : text;
        }
        String text = cell.asText(null);
        return text == null || text.isBlank() ? null : text;
    }

    private static String quoteOf(JsonNode cell, String value) {
        if (cell != null && cell.isObject() && cell.hasNonNull("quote")) {
            String quote = cell.get("quote").asText(null);
            if (quote != null && !quote.isBlank()) {
                return quote;
            }
        }
        return value;
    }

    private static int pageOf(JsonNode cell) {
        if (cell != null && cell.isObject() && cell.hasNonNull("page")) {
            return cell.get("page").asInt(1);
        }
        return 1;
    }
}
