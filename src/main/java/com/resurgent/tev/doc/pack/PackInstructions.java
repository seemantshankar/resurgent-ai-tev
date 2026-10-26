package com.resurgent.tev.doc.pack;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resolves pack instruction text for a field or table column (#157). */
public final class PackInstructions {

    private PackInstructions() {}

    public static String forFieldKey(DocTypePack pack, String fieldKey) {
        if (pack == null || fieldKey == null) {
            return null;
        }
        for (PackField field : pack.fields()) {
            if (fieldKey.equals(field.key())) {
                return field.promptHint();
            }
        }
        int dot = fieldKey.indexOf('.');
        if (dot > 0) {
            String table = fieldKey.substring(0, dot);
            String column = fieldKey.substring(dot + 1);
            return forTableColumn(pack, table, column);
        }
        for (Map<String, Object> table : pack.tables()) {
            String tableName = String.valueOf(table.getOrDefault("name", ""));
            String fromTable = forTableColumn(pack, tableName, fieldKey);
            if (fromTable != null) {
                return fromTable;
            }
        }
        return null;
    }

    private static String forTableColumn(DocTypePack pack, String tableName, String columnKey) {
        for (Map<String, Object> table : pack.tables()) {
            if (!tableName.equals(String.valueOf(table.getOrDefault("name", "")))) {
                continue;
            }
            Object fieldsObj = table.get("fields");
            if (!(fieldsObj instanceof List<?> columns)) {
                continue;
            }
            for (Object colObj : columns) {
                if (!(colObj instanceof Map<?, ?> col)) {
                    continue;
                }
                if (columnKey.equals(String.valueOf(col.get("key")))) {
                    Object hint = col.get("prompt_hint");
                    return hint == null ? null : String.valueOf(hint);
                }
            }
        }
        return null;
    }

    /** Stable comparison key for facts: field_key plus optional line_key. */
    public static String factSlotKey(String fieldKey, String lineKey) {
        String fk = fieldKey == null ? "" : fieldKey.trim();
        if (lineKey == null || lineKey.isBlank()) {
            return fk.toLowerCase(Locale.ROOT);
        }
        return fk.toLowerCase(Locale.ROOT) + "|" + lineKey.trim().toLowerCase(Locale.ROOT);
    }
}
