package com.resurgent.tev.doc.bind;

import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.CostUnit;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.nomenclature.LivingOntology;
import com.resurgent.tev.parser.nomenclature.NomenclatureCatalog;
import com.resurgent.tev.parser.nomenclature.NomenclatureException;
import com.resurgent.tev.parser.nomenclature.OntologySlice;
import com.resurgent.tev.parser.nomenclature.OntologyVocabulary;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Binds extracted facts to the shared nomenclature (issue #153). The pack gives each
 * fact a candidate path; that path is resolved against the living ontology — an exact
 * living path, then the alias index the Excel model binds with — and the fact stores
 * the canonical {@code root > path} it resolved to.
 *
 * <p>Anything the deterministic pass cannot resolve is put to the document LLM, one
 * batched call for the run, with only the living ontology's paths on offer; a genuinely
 * new leaf is absorbed into the mandate's own overlay the way classify grows it.
 * Offline, declined or rejected assignments stay {@code unbound} with their reason:
 * retrievable, never compared silently.
 *
 * <p>Ontology node rows are not required before bind; when a {@code doc_ontology_node}
 * table exists, spine paths are ensured (layer=spine, frozen=1).
 */
public final class PackPathBinder {

    /** The roots a pack path may open with; the shared ontology names its own roots. */
    private static final Set<String> PACK_ROOTS = Set.of(
            "identity", "economic", "technical", "commercial", "legal", "regulatory", "frame");

    /** Bind counts for one parse_run. */
    public record BindOutcome(
            long parseRunId,
            int bound,
            int unbound,
            int notApplicable,
            int spineNodesEnsured) {}

    /**
     * Binds every fact of the parse_run and writes {@code doc-bind} run_stat.
     *
     * @param dbPath workspace database
     * @param parseRunId extract parse_run whose facts to bind
     * @param mandateId mandate whose living ontology binds to
     * @param pack pack used at extract
     * @param client document LLM seam for unmatched leaves, or null when offline
     * @return bind counts
     * @throws Exception on workspace or persistence failure
     */
    public BindOutcome bind(Path dbPath, long parseRunId, long mandateId, DocTypePack pack,
            DocLlmClient client) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            return bind(db.connection(), parseRunId, mandateId, pack, client);
        }
    }

    /**
     * Binds using a caller-owned connection (caller manages transactions).
     *
     * @param connection workspace connection
     * @param parseRunId extract parse_run
     * @param mandateId mandate whose living ontology binds to
     * @param pack pack used at extract
     * @param client document LLM seam for unmatched leaves, or null when offline
     * @return bind counts
     * @throws Exception on persistence failure
     */
    public BindOutcome bind(Connection connection, long parseRunId, long mandateId,
            DocTypePack pack, DocLlmClient client) throws Exception {
        FactRepository facts = new FactRepository(connection);
        WorkspaceRepository repo = new WorkspaceRepository(connection);
        NomenclatureCatalog catalog = new NomenclatureCatalog(repo);
        LivingOntology living = LivingOntology.from(catalog.sliceForMandate(mandateId));

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
        List<Unresolved> unresolved = new ArrayList<>();
        for (FactRepository.FactBindRow row : rows) {
            String candidate = null;
            String candidateRoot = row.pathRoot();
            PackField field = row.fieldKey() == null ? null : fieldByKey.get(row.fieldKey());
            if (field != null) {
                // Derived facts bind to the primary leaf path (same key, e.g. net_worth).
                candidate = field.path();
                candidateRoot = field.pathRootWire();
            }
            String[] tablePath = row.fieldKey() == null ? null
                    : tablePathByName.get(row.fieldKey());
            if (candidate == null && tablePath != null && tablePath[0] != null
                    && !"null".equals(tablePath[0])) {
                candidate = tablePath[0];
                candidateRoot = tablePath[1].toLowerCase(Locale.ROOT);
            }
            // Table column keys (description/qty/...) also bind to the line-item path.
            if (candidate == null) {
                String viaColumn = tableColumnPath(pack, row.fieldKey());
                if (viaColumn != null) {
                    String[] owner = ownerTablePath(pack, row.fieldKey());
                    candidate = viaColumn;
                    candidateRoot = owner == null ? "economic"
                            : owner[1].toLowerCase(Locale.ROOT);
                }
            }
            if (candidate == null) {
                unbind(facts, row.factId(), row.ontologyPath(), row.pathRoot(), "no_pack_field");
                unbound++;
                continue;
            }
            Resolved resolved = resolveShared(candidate, living);
            if (resolved != null) {
                facts.updateFactBinding(row.factId(), resolved.canonicalPath(),
                        resolved.pathRoot(), "bound", null, resolved.reason());
                bound++;
                continue;
            }
            unresolved.add(new Unresolved(row, candidate, candidateRoot));
        }

        // The leaves no alias names go to the model as one batch, and the ontology
        // grows under it the way classify grows it.
        int llmCalls = 0;
        int refused = 0;
        int softLeaves = 0;
        int collisions = 0;
        int transcribed = 0;
        Map<String, LlmLeafAssignment.Assignment> placements = Map.of();
        if (!unresolved.isEmpty() && client != null) {
            llmCalls = 1;
            List<String> prompt = new ArrayList<>();
            Set<String> prompted = new HashSet<>();
            for (Unresolved open : unresolved) {
                if (prompted.add(open.row().fieldKey())) {
                    prompt.add(LlmLeafAssignment.describe(open.row().fieldKey(),
                            stripPackRoot(open.candidate()), open.row().valueText(),
                            open.row().pageIndex(), open.row().verbatim()));
                }
            }
            LlmLeafAssignment.Result result = LlmLeafAssignment.assign(client, prompt,
                    living.paths());
            placements = result.assignments();
            recordUsage(repo, parseRunId, result.usage());
        }

        for (Unresolved open : unresolved) {
            FactRepository.FactBindRow row = open.row();
            LlmLeafAssignment.Assignment placement = placements.get(row.fieldKey());
            if (placement == null) {
                unbind(facts, row.factId(), open.candidate(), open.candidateRoot(),
                        client == null ? "no_model" : "model_declined");
                unbound++;
                continue;
            }
            String root = OntologyVocabulary.rootFor(placement.path());
            if (!OntologyVocabulary.allowed(root, placement.path())) {
                unbind(facts, row.factId(), open.candidate(), open.candidateRoot(),
                        "model_path_refused");
                unbound++;
                refused++;
                continue;
            }
            // The page is the pipeline's own provenance, not the model's prose.
            String reason = "model" + onPage(row.pageIndex()) + ": " + placement.reasoning();
            if (!living.contains(placement.path())) {
                // A genuinely new leaf hangs under a head the ontology already knows.
                // An alias clash refuses the growth but not the placement: the fact
                // keeps this path for the run, exactly as Layer B leaves it.
                int sep = placement.path().lastIndexOf(OntologySlice.SEPARATOR);
                String leaf = placement.path().substring(sep + OntologySlice.SEPARATOR.length());
                String bindable = OntologyVocabulary.bindablePath(placement.path(), living.hardAliases());
                if (bindable == null) {
                    // ADR 0022 §2: the leaf copies its row, so it could never match again.
                    unbind(facts, row.factId(), open.candidate(), open.candidateRoot(),
                            "transcribed_label");
                    unbound++;
                    transcribed++;
                    continue;
                }
                if (!bindable.equals(placement.path())) {
                    // §2's escape hatch: the catalogue names one leaf for this transcription.
                    Resolved rescued = canonical(bindable, "alias: " + leaf);
                    facts.updateFactBinding(row.factId(), rescued.canonicalPath(),
                            rescued.pathRoot(), "bound", null, rescued.reason());
                    bound++;
                    continue;
                }
                try {
                    catalog.putSoftLeaf(mandateId,
                            placement.path().substring(0, sep), leaf, List.of(leaf));
                    living = living.withLeaf(placement.path(), leaf);
                    softLeaves++;
                    reason = "model leaf (mandate_soft)" + onPage(row.pageIndex()) + ": "
                            + placement.reasoning();
                } catch (NomenclatureException e) {
                    collisions++;
                    reason = "model leaf (alias collision refused)" + onPage(row.pageIndex())
                            + ": " + placement.reasoning();
                }
            }
            Resolved resolved = canonical(placement.path(), reason);
            facts.updateFactBinding(row.factId(), resolved.canonicalPath(),
                    resolved.pathRoot(), "bound", null, resolved.reason());
            bound++;
        }

        int spine = ensureSpineNodes(connection, pack);

        facts.recordRunStat(parseRunId, "doc-bind", "facts_bound", (double) bound, null);
        facts.recordRunStat(parseRunId, "doc-bind", "facts_unbound", (double) unbound, null);
        facts.recordRunStat(parseRunId, "doc-bind", "facts_not_applicable",
                (double) notApplicable, null);
        facts.recordRunStat(parseRunId, "doc-bind", "llm_calls", (double) llmCalls, null);
        facts.recordRunStat(parseRunId, "doc-bind", "model_paths_refused", (double) refused,
                null);
        facts.recordRunStat(parseRunId, "doc-bind", "soft_leaves_added", (double) softLeaves,
                null);
        facts.recordRunStat(parseRunId, "doc-bind", "alias_collisions", (double) collisions,
                null);
        facts.recordRunStat(parseRunId, "doc-bind", "transcribed_leaves_refused",
                (double) transcribed, null);
        if (spine > 0) {
            facts.recordRunStat(parseRunId, "doc-bind", "spine_nodes", (double) spine, null);
        }

        return new BindOutcome(parseRunId, bound, unbound, notApplicable, spine);
    }

    private static void recordUsage(WorkspaceRepository repo, long parseRunId,
            DocLlmClient.Usage usage) throws Exception {
        repo.recordLlmUsage(parseRunId, "doc-bind", usage.modelId(),
                usage.calls(), usage.failedCalls(), 0,
                usage.promptTokens(), usage.completionTokens(),
                usage.costUsd(), CostUnit.USD, usage.costUsd() == null ? 1 : 0,
                usage.latencyMsTotal());
    }

    /** One fact whose pack label names no leaf of the shared nomenclature. */
    private record Unresolved(FactRepository.FactBindRow row, String candidate,
            String candidateRoot) {}

    /** An unbound fact keeps the path it came in with: retrievable, never compared. */
    private static void unbind(FactRepository facts, long factId, String path, String root,
            String reason) throws SQLException {
        facts.updateFactBinding(factId, path, root, "unbound", reason, null);
    }

    /** The page the label was read on, as the bind reason cites it. */
    private static String onPage(Long pageIndex) {
        return pageIndex == null ? "" : " (page " + pageIndex + ")";
    }

    /** One deterministic resolution: the canonical stored path, its root and why. */
    private record Resolved(String canonicalPath, String pathRoot, String reason) {}

    /**
     * The deterministic pass: a pack path names a living path, or a label the shared
     * alias index knows — the whole qualified leaf first, then its last segment, so
     * {@code Audited Financials > Net Worth} lands on {@code Balance Sheet > Net Worth}.
     * An alias the index marks ambiguous (empty) resolves nothing.
     *
     * <p>The last segment is tried only when the pack's own parent is a head the shared
     * ontology does not carry. Under a head the ontology does carry, a bare label would
     * move the leaf to another head — a quotation's {@code Project Cost > Tax} is GST in
     * the cost, not the P&amp;L's tax expense — so the leaf goes to the model instead.
     */
    private static Resolved resolveShared(String packPath, LivingOntology living) {
        String leaf = stripPackRoot(packPath);
        if (leaf.isBlank()) {
            return null;
        }
        if (living.paths().contains(leaf)) {
            return canonical(leaf, "living path: " + leaf);
        }
        Map<String, String> aliases = living.aliases();
        String whole = aliases.get(OntologyVocabulary.norm(leaf));
        if (whole != null && !whole.isBlank()) {
            return canonical(whole, "alias: " + leaf);
        }
        if (parentKnown(leaf, living)) {
            return null;
        }
        String name = leaf.contains(OntologySlice.SEPARATOR)
                ? leaf.substring(leaf.lastIndexOf(OntologySlice.SEPARATOR) + 3) : leaf;
        String byName = aliases.get(OntologyVocabulary.norm(name));
        if (byName != null && !byName.isBlank()) {
            return canonical(byName, "alias: " + name);
        }
        return null;
    }

    /** Whether the leaf's own parent is a path the shared ontology already carries. */
    private static boolean parentKnown(String leaf, LivingOntology living) {
        int sep = leaf.lastIndexOf(OntologySlice.SEPARATOR);
        return sep > 0 && living.contains(leaf.substring(0, sep).trim());
    }

    private static Resolved canonical(String sharedPath, String reason) {
        String root = OntologyVocabulary.rootFor(sharedPath);
        return new Resolved(root + " > " + sharedPath, root, reason);
    }

    /** The pack path without the root the pack chose for it; the ontology owns the root. */
    private static String stripPackRoot(String packPath) {
        String text = packPath == null ? "" : packPath.trim();
        int sep = text.indexOf(OntologySlice.SEPARATOR);
        if (sep > 0 && PACK_ROOTS.contains(text.substring(0, sep).toLowerCase(Locale.ROOT))) {
            return text.substring(sep + 3).trim();
        }
        return text;
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
