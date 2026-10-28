package com.resurgent.tev.doc.induce;

import com.resurgent.tev.doc.extract.ValueKind;
import com.resurgent.tev.doc.induce.PackMiningClient.MinedColumn;
import com.resurgent.tev.doc.induce.PackMiningClient.MinedField;
import com.resurgent.tev.doc.induce.PackMiningClient.MinedTable;
import com.resurgent.tev.doc.induce.PackMiningClient.MiningResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites Shape 1 nested array-of-row-object fields into {@link MinedTable}s
 * before consensus filters run. Rules are structural: a parent prefix with two
 * or more homogeneous labeled columns, not document-type English names.
 */
final class NestedScheduleNormalizer {

    private NestedScheduleNormalizer() {}

    /**
     * Returns a mining result whose nested row groups are tables, with the
     * matching list parent, dotted children, and duplicate column scalars
     * removed so they are not the publishable end state.
     */
    static MiningResult rewrite(
            MiningResult mined,
            List<Generalizer.DecisionNote> decisions,
            List<String> dropped) {
        if (mined == null || mined.fields().isEmpty()) {
            return mined;
        }
        Set<String> existingTables = new LinkedHashSet<>();
        for (MinedTable table : mined.tables()) {
            if (table.name() != null && !table.name().isBlank()) {
                existingTables.add(table.name());
            }
        }
        Map<String, List<MinedField>> groups = new LinkedHashMap<>();
        Map<String, MinedField> byKey = new LinkedHashMap<>();
        for (MinedField field : mined.fields()) {
            if (field.key() == null || field.key().isBlank()) {
                continue;
            }
            byKey.put(field.key(), field);
            Nested nested = Nested.split(field.key());
            if (nested == null || isListKind(field.valueKind())) {
                continue;
            }
            groups.computeIfAbsent(nested.parent(), key -> new ArrayList<>()).add(field);
        }

        List<MinedTable> extra = new ArrayList<>();
        Set<String> dropKeys = new LinkedHashSet<>();
        List<MinedTable> rewritten = new ArrayList<>(mined.tables());
        for (Map.Entry<String, List<MinedField>> entry : groups.entrySet()) {
            String parent = entry.getKey();
            boolean alreadyTable = existingTables.contains(parent);
            List<MinedColumn> columns = columnsOf(entry.getValue());
            if (!alreadyTable && columns.size() < 2) {
                continue;
            }
            dropKeys.add(parent);
            for (MinedField child : entry.getValue()) {
                dropKeys.add(child.key());
            }
            if (alreadyTable) {
                continue;
            }
            MinedField parentField = byKey.get(parent);
            MinedTable table = toTable(parent, parentField, entry.getValue(), columns);
            extra.add(table);
            rewritten.add(table);
            existingTables.add(parent);
            if (decisions != null) {
                decisions.add(Generalizer.note(parent, "rewritten", "widen_table",
                        "Nested row array rewritten to a pack table."));
            }
        }
        if (extra.isEmpty() && dropKeys.isEmpty()) {
            return mined;
        }
        for (MinedField field : mined.fields()) {
            if (dropKeys.contains(field.key())) {
                continue;
            }
            if (duplicatesColumn(field, rewritten)) {
                dropKeys.add(field.key());
            }
        }
        if (dropped != null) {
            for (String key : dropKeys) {
                dropped.add(key + ": nested row array rewritten to a pack table");
            }
        }
        List<MinedField> fields = new ArrayList<>();
        for (MinedField field : mined.fields()) {
            if (!dropKeys.contains(field.key())) {
                fields.add(field);
            }
        }
        List<MinedTable> tables = new ArrayList<>(mined.tables());
        tables.addAll(extra);
        return new MiningResult(
                mined.suggestedCode(),
                mined.family(),
                mined.identitySignals(),
                fields,
                tables,
                mined.clauses(),
                mined.passages(),
                mined.documentDateKind(),
                mined.webSearchQuery(),
                mined.domainRationale());
    }

    private static List<MinedColumn> columnsOf(List<MinedField> children) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<MinedColumn> columns = new ArrayList<>();
        for (MinedField child : children) {
            Nested nested = Nested.split(child.key());
            if (nested == null || !seen.add(nested.column())) {
                continue;
            }
            columns.add(new MinedColumn(nested.column(), child.valueKind()));
        }
        return columns;
    }

    private static MinedTable toTable(
            String name,
            MinedField parent,
            List<MinedField> children,
            List<MinedColumn> columns) {
        MinedField first = children.get(0);
        String path = parent != null && parent.path() != null && !parent.path().isBlank()
                ? parent.path()
                : parentPath(first.path());
        String pathRoot = parent != null && parent.pathRoot() != null && !parent.pathRoot().isBlank()
                ? parent.pathRoot()
                : first.pathRoot();
        LinkedHashSet<String> docIds = new LinkedHashSet<>();
        boolean domainStandard = parent != null && parent.domainStandard();
        if (parent != null) {
            docIds.addAll(parent.docIds());
        }
        for (MinedField child : children) {
            docIds.addAll(child.docIds());
            domainStandard = domainStandard || child.domainStandard();
        }
        return new MinedTable(
                name,
                path,
                pathRoot,
                inferKeyColumn(columns),
                null,
                null,
                inferPeriodKey(columns),
                columns,
                List.copyOf(docIds),
                domainStandard);
    }

    private static String inferKeyColumn(List<MinedColumn> columns) {
        String textColumn = null;
        for (MinedColumn column : columns) {
            if (!isTextLike(column.valueKind())) {
                continue;
            }
            if (textColumn != null) {
                return null;
            }
            textColumn = column.key();
        }
        return textColumn;
    }

    private static String inferPeriodKey(List<MinedColumn> columns) {
        String dateColumn = null;
        for (MinedColumn column : columns) {
            if (Generalizer.parseKind(column.valueKind()) != ValueKind.DATE) {
                continue;
            }
            if (dateColumn != null) {
                return null;
            }
            dateColumn = column.key();
        }
        return dateColumn;
    }

    private static boolean duplicatesColumn(MinedField field, List<MinedTable> tables) {
        String key = field.key();
        String path = field.path() == null ? "" : field.path().trim();
        for (MinedTable table : tables) {
            boolean columnMatch = false;
            for (MinedColumn column : table.columns()) {
                if (key.equals(column.key())) {
                    columnMatch = true;
                    break;
                }
            }
            if (!columnMatch) {
                continue;
            }
            String tablePath = table.path() == null ? "" : table.path().trim();
            if (tablePath.isEmpty()
                    || path.equals(tablePath)
                    || path.startsWith(tablePath + " >")
                    || path.startsWith(tablePath + ">")) {
                return true;
            }
        }
        return false;
    }

    private static String parentPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int sep = path.lastIndexOf('>');
        if (sep <= 0) {
            return path.trim();
        }
        return path.substring(0, sep).trim();
    }

    private static boolean isListKind(String wire) {
        return Generalizer.parseKind(wire) == ValueKind.LIST;
    }

    private static boolean isTextLike(String wire) {
        if (wire == null || wire.isBlank()) {
            return false;
        }
        ValueKind kind = Generalizer.parseKind(wire);
        return kind == ValueKind.TEXT || kind == ValueKind.ENUM;
    }

    private record Nested(String parent, String column) {

        static Nested split(String key) {
            if (key == null || key.isBlank()) {
                return null;
            }
            String normalized = key.replace("[]", "").trim();
            int dot = normalized.lastIndexOf('.');
            if (dot <= 0 || dot == normalized.length() - 1) {
                return null;
            }
            String parent = normalized.substring(0, dot);
            String column = normalized.substring(dot + 1);
            if (parent.isBlank() || column.isBlank()) {
                return null;
            }
            return new Nested(parent, column);
        }
    }
}
