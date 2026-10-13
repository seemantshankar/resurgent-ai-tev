package com.resurgent.tev.doc.bind;

import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic pack-path bind (S2.6). Facts whose {@code field_key} matches a pack
 * field (or pack table) get {@code ontology_path}, {@code path_root}, and
 * {@code nomenclature_status='bound'}. Derived facts bind to their primary leaf path.
 * Everything else stays {@code unbound} with {@code unbound_reason='no_pack_field'}.
 *
 * <p>No LLM. Ontology node rows are not required before bind; when a
 * {@code doc_ontology_node} table exists, spine paths are ensured (layer=spine, frozen=1).
 */
public final class PackPathBinder {

    /** Bind counts for one parse_run. */
    public record BindOutcome(
            long parseRunId,
            int bound,
            int unbound,
            int notApplicable,
            int spineNodesEnsured) {}

    /**
     * Binds every fact of the parse_run to pack paths and writes {@code doc-bind} run_stat.
     *
     * @param dbPath workspace database
     * @param parseRunId extract parse_run whose facts to bind
     * @param pack pack used at extract
     * @return bind counts
     * @throws Exception on workspace or persistence failure
     */
    public BindOutcome bind(Path dbPath, long parseRunId, DocTypePack pack) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            return bind(db.connection(), parseRunId, pack);
        }
    }

    /**
     * Binds using a caller-owned connection (caller manages transactions).
     *
     * @param connection workspace connection
     * @param parseRunId extract parse_run
     * @param pack pack used at extract
     * @return bind counts
     * @throws Exception on persistence failure
     */
    public BindOutcome bind(Connection connection, long parseRunId, DocTypePack pack)
            throws Exception {
        FactRepository facts = new FactRepository(connection);

        Map<String, PackField> fieldByKey = new HashMap<>();
        for (PackField f : pack.fields()) {
            fieldByKey.put(f.key(), f);
        }
        Map<String, String[]> tablePathByName = new HashMap<>();
        for (Map<String, Object> table : pack.tables()) {
            Object name = table.get("name");
            if (name == null) {
                continue;
            }
            Object path = table.get("path");
            Object root = table.get("path_root");
            tablePathByName.put(String.valueOf(name), new String[] {
                    path == null ? null : String.valueOf(path),
                    root == null ? "economic" : String.valueOf(root)});
        }

        int bound = 0;
        int unbound = 0;
        int notApplicable = 0;
        List<FactRepository.FactBindRow> rows = facts.selectFactsForParseRun(parseRunId);
        for (FactRepository.FactBindRow row : rows) {
            PackField field = row.fieldKey() == null ? null : fieldByKey.get(row.fieldKey());
            if (field != null) {
                // Derived facts bind to the primary leaf path (same key, e.g. net_worth).
                facts.updateFactBinding(row.factId(), field.path(), field.pathRootWire(),
                        "bound", null);
                bound++;
                continue;
            }
            String[] tablePath = row.fieldKey() == null ? null
                    : tablePathByName.get(row.fieldKey());
            if (tablePath != null && tablePath[0] != null && !"null".equals(tablePath[0])) {
                facts.updateFactBinding(row.factId(), tablePath[0],
                        tablePath[1].toLowerCase(Locale.ROOT), "bound", null);
                bound++;
                continue;
            }
            // Table column keys (description/qty/...) also bind to the line-item path.
            String viaColumn = tableColumnPath(pack, row.fieldKey());
            if (viaColumn != null) {
                String[] owner = ownerTablePath(pack, row.fieldKey());
                facts.updateFactBinding(row.factId(), viaColumn,
                        owner == null ? "economic" : owner[1].toLowerCase(Locale.ROOT),
                        "bound", null);
                bound++;
                continue;
            }
            facts.updateFactBinding(row.factId(), row.ontologyPath(), row.pathRoot(),
                    "unbound", "no_pack_field");
            unbound++;
        }

        int spine = ensureSpineNodes(connection, pack);

        facts.recordRunStat(parseRunId, "doc-bind", "facts_bound", (double) bound, null);
        facts.recordRunStat(parseRunId, "doc-bind", "facts_unbound", (double) unbound, null);
        facts.recordRunStat(parseRunId, "doc-bind", "facts_not_applicable",
                (double) notApplicable, null);
        if (spine > 0) {
            facts.recordRunStat(parseRunId, "doc-bind", "spine_nodes", (double) spine, null);
        }

        return new BindOutcome(parseRunId, bound, unbound, notApplicable, spine);
    }

    /**
     * Optional spine ensure: inserts pack paths into {@code doc_ontology_node}
     * (layer=spine, frozen=1) when that table exists. Silent no-op otherwise
     * (S2.6: ontology node rows are not required to exist before bind).
     *
     * @return number of rows ensured
     */
    static int ensureSpineNodes(Connection connection, DocTypePack pack) throws Exception {
        if (!tableExists(connection, "doc_ontology_node")) {
            return 0;
        }
        Set<String> paths = new HashSet<>();
        for (PackField f : pack.fields()) {
            paths.add(f.path());
        }
        for (Map<String, Object> table : pack.tables()) {
            Object path = table.get("path");
            if (path != null) {
                paths.add(String.valueOf(path));
            }
        }
        int inserted = 0;
        String now = Timestamps.now();
        for (String path : paths) {
            String name = path.contains(" > ")
                    ? path.substring(path.lastIndexOf(" > ") + 3) : path;
            String parent = path.contains(" > ")
                    ? path.substring(0, path.lastIndexOf(" > ")) : null;
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO doc_ontology_node (path, name, parent_path, layer, frozen)"
                            + " VALUES (?, ?, ?, 'spine', 1)"
                            + " ON CONFLICT (path) DO NOTHING")) {
                ps.setString(1, path);
                ps.setString(2, name);
                if (parent == null) {
                    ps.setNull(3, java.sql.Types.VARCHAR);
                } else {
                    ps.setString(3, parent);
                }
                inserted += ps.executeUpdate();
            } catch (Exception e) {
                // Table shape may differ; spine ensure is optional.
                return inserted;
            }
        }
        return inserted;
    }

    private static String tableColumnPath(DocTypePack pack, String fieldKey) {
        if (fieldKey == null) {
            return null;
        }
        for (Map<String, Object> table : pack.tables()) {
            Object path = table.get("path");
            Object fieldsObj = table.get("fields");
            if (path == null || !(fieldsObj instanceof List<?> list)) {
                continue;
            }
            for (Object raw : list) {
                if (raw instanceof Map<?, ?> f && fieldKey.equals(String.valueOf(f.get("key")))) {
                    return String.valueOf(path);
                }
            }
        }
        return null;
    }

    private static String[] ownerTablePath(DocTypePack pack, String fieldKey) {
        if (fieldKey == null) {
            return null;
        }
        for (Map<String, Object> table : pack.tables()) {
            Object fieldsObj = table.get("fields");
            Object root = table.get("path_root");
            if (!(fieldsObj instanceof List<?> list)) {
                continue;
            }
            for (Object raw : list) {
                if (raw instanceof Map<?, ?> f && fieldKey.equals(String.valueOf(f.get("key")))) {
                    return new String[] {
                            String.valueOf(table.get("path")),
                            root == null ? "economic" : String.valueOf(root)};
                }
            }
        }
        return null;
    }

    private static boolean tableExists(Connection connection, String tableName)
            throws Exception {
        DatabaseMetaData md = connection.getMetaData();
        try (ResultSet rs = md.getTables(null, null, tableName, new String[] {"TABLE"})) {
            if (rs.next()) {
                return true;
            }
        }
        try (ResultSet rs = md.getTables(null, null, tableName.toUpperCase(Locale.ROOT),
                new String[] {"TABLE"})) {
            return rs.next();
        }
    }
}
