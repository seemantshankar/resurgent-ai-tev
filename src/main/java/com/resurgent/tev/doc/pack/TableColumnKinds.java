package com.resurgent.tev.doc.pack;

import com.resurgent.tev.doc.extract.ValueKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Closed-set {@code value_kind} checks for pack table columns. Messages name
 * {@code table.column} so a missing kind is actionable.
 */
public final class TableColumnKinds {

    private TableColumnKinds() {}

    /**
     * Problems on every table's columns, including key columns.
     */
    public static List<String> problems(List<Map<String, Object>> tables) {
        List<String> problems = new ArrayList<>();
        if (tables == null) {
            return problems;
        }
        for (Map<String, Object> table : tables) {
            problems.addAll(problems(table));
        }
        return problems;
    }

    /**
     * Problems on one table's columns.
     */
    public static List<String> problems(Map<String, Object> table) {
        List<String> problems = new ArrayList<>();
        if (table == null) {
            return problems;
        }
        Object nameObj = table.get("name");
        String tableName = nameObj == null || String.valueOf(nameObj).isBlank()
                ? "table" : String.valueOf(nameObj);
        Object fieldsObj = table.get("fields");
        if (!(fieldsObj instanceof List<?> columns)) {
            return problems;
        }
        for (Object raw : columns) {
            if (!(raw instanceof Map<?, ?> column)) {
                continue;
            }
            Object keyObj = column.get("key");
            String colKey = keyObj == null || String.valueOf(keyObj).isBlank()
                    ? "(unnamed)" : String.valueOf(keyObj);
            String slot = tableName + "." + colKey;
            Object kindObj = column.get("value_kind");
            if (kindObj == null || String.valueOf(kindObj).isBlank()) {
                problems.add(slot + ": missing value_kind");
                continue;
            }
            try {
                ValueKind.fromWire(String.valueOf(kindObj).trim());
            } catch (IllegalArgumentException e) {
                problems.add(slot + ": value_kind '" + kindObj + "' is not in the closed set");
            }
        }
        return problems;
    }
}
