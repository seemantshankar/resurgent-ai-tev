package com.resurgent.tev.parser.nomenclature;

import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Loads the frozen bank spine and assembles the ontology slice a mandate's
 * Packet classification will send. No LLM calls.
 */
public final class NomenclatureCatalog {

    private final WorkspaceRepository repo;

    public NomenclatureCatalog(WorkspaceRepository repo) {
        this.repo = Objects.requireNonNull(repo, "repo");
        try {
            inTransaction(() -> {
                seedSpineIfEmpty();
                seedHotelPackIfEmpty();
            });
        } catch (SQLException e) {
            throw new NomenclatureException("failed to seed nomenclature catalog", e);
        }
    }

    public void confirmIndustry(long mandateId, String industryTag) {
        try {
            repo.upsertMandateIndustry(mandateId, industryTag, true, false);
        } catch (SQLException e) {
            throw new NomenclatureException(
                    "failed to confirm industry '" + industryTag + "' for mandate " + mandateId, e);
        }
    }

    public OntologySlice sliceForMandate(long mandateId) {
        return sliceForMandate(mandateId, null);
    }

    public List<NomenclatureNode> spine() {
        try {
            return repo.selectNomenclatureSpine();
        } catch (SQLException e) {
            throw new NomenclatureException("failed to load nomenclature spine", e);
        }
    }

    public OntologySlice sliceForMandate(long mandateId, String industryHint) {
        try {
            IndustryResolution industry = resolveIndustry(mandateId, industryHint);
            return assemble(mandateId, industry);
        } catch (SQLException e) {
            throw new NomenclatureException(
                    "failed to load ontology slice for mandate " + mandateId, e);
        }
    }

    public void putSoftLeaf(long mandateId, String parentPath, String leafName,
            List<String> aliases) {
        Objects.requireNonNull(parentPath, "parentPath");
        Objects.requireNonNull(leafName, "leafName");
        List<String> aliasTexts = aliases == null ? List.of() : aliases;
        OntologySlice current = sliceForMandate(mandateId);
        NomenclatureNode parent = current.node(parentPath).orElse(null);
        if (parent == null || parent.leaf()) {
            throw new NomenclatureException(
                    "cannot invent mid-level '" + parentPath
                            + "'; soft leaves attach under known mid-levels");
        }
        String path = parentPath + " > " + leafName;
        if (current.node(path).isPresent()) {
            throw new NomenclatureException(
                    "soft leaf path already exists in the ontology slice: '" + path + "'");
        }
        for (String aliasText : aliasTexts) {
            if (aliasText == null || aliasText.isBlank()) {
                continue;
            }
            Optional<String> bound = leafPathForNormalizedAlias(current, aliasText);
            if (bound.isPresent() && !bound.get().equals(path)) {
                throw new NomenclatureException(
                        "alias '" + aliasText + "' already maps to '" + bound.get() + "'");
            }
        }
        try {
            inTransaction(() -> {
                String now = Timestamps.now();
                repo.insertNomenclatureNode(new NomenclatureNode(
                        path,
                        leafName,
                        parentPath,
                        NomenclatureNode.LAYER_MANDATE_SOFT,
                        false,
                        true,
                        null,
                        mandateId), now);
                for (String aliasText : aliasTexts) {
                    if (aliasText == null || aliasText.isBlank()) {
                        continue;
                    }
                    repo.insertNomenclatureAlias(
                            new NomenclatureAlias(aliasText, path),
                            NomenclatureNode.LAYER_MANDATE_SOFT,
                            null,
                            mandateId,
                            now);
                }
            });
        } catch (SQLException e) {
            throw new NomenclatureException(
                    "failed to store soft leaf '" + path + "' for mandate " + mandateId, e);
        }
    }

    private IndustryResolution resolveIndustry(long mandateId, String industryHint)
            throws SQLException {
        IndustryResolution stored = repo.selectMandateIndustry(mandateId);
        if (stored != null) {
            return stored;
        }
        IndustryResolution inferred = (industryHint == null || industryHint.isBlank())
                ? IndustryResolution.unspecified()
                : IndustryResolution.inferred(industryHint);
        repo.upsertMandateIndustry(mandateId, inferred.industryTag(), false, true);
        return inferred;
    }

    private OntologySlice assemble(long mandateId, IndustryResolution industry)
            throws SQLException {
        List<NomenclatureNode> nodes = new ArrayList<>(repo.selectNomenclatureSpine());
        List<NomenclatureAlias> aliases = new ArrayList<>(
                repo.selectNomenclatureAliases(NomenclatureNode.LAYER_SPINE, null, null));
        if (!IndustryResolution.UNSPECIFIED.equals(industry.industryTag())) {
            nodes.addAll(repo.selectNomenclatureIndustry(industry.industryTag()));
            aliases.addAll(repo.selectNomenclatureAliases(
                    NomenclatureNode.LAYER_INDUSTRY, industry.industryTag(), null));
        }
        nodes.addAll(repo.selectNomenclatureOverlay(mandateId));
        aliases.addAll(repo.selectNomenclatureAliases(
                NomenclatureNode.LAYER_MANDATE_SOFT, null, mandateId));
        return new OntologySlice(industry, List.copyOf(nodes), List.copyOf(aliases));
    }

    private void seedSpineIfEmpty() throws SQLException {
        Set<String> existingPaths = pathsOf(repo.selectNomenclatureSpine());
        Set<String> existingAliases = aliasKeys(repo.selectNomenclatureAliases(
                NomenclatureNode.LAYER_SPINE, null, null));
        if (existingPaths.containsAll(NomenclatureSeed.SPINE_PATHS)
                && existingAliases.containsAll(aliasKeys(NomenclatureSeed.SPINE_ALIASES))) {
            return;
        }
        String now = Timestamps.now();
        for (String path : NomenclatureSeed.SPINE_PATHS) {
            if (existingPaths.contains(path)) {
                continue;
            }
            repo.insertNomenclatureNode(new NomenclatureNode(
                    path,
                    NomenclatureSeed.nameOf(path),
                    NomenclatureSeed.parentOf(path),
                    NomenclatureNode.LAYER_SPINE,
                    NomenclatureSeed.frozen(path),
                    false,
                    null,
                    null), now);
        }
        for (NomenclatureAlias alias : NomenclatureSeed.SPINE_ALIASES) {
            if (existingAliases.contains(aliasKey(alias))) {
                continue;
            }
            repo.insertNomenclatureAlias(
                    alias, NomenclatureNode.LAYER_SPINE, null, null, now);
        }
    }

    private void seedHotelPackIfEmpty() throws SQLException {
        Set<String> existingPaths = pathsOf(repo.selectNomenclatureIndustry(NomenclatureSeed.HOTEL));
        Set<String> existingAliases = aliasKeys(repo.selectNomenclatureAliases(
                NomenclatureNode.LAYER_INDUSTRY, NomenclatureSeed.HOTEL, null));
        if (existingPaths.containsAll(NomenclatureSeed.HOTEL_LEAVES)
                && existingAliases.containsAll(aliasKeys(NomenclatureSeed.HOTEL_ALIASES))) {
            return;
        }
        String now = Timestamps.now();
        for (String path : NomenclatureSeed.HOTEL_LEAVES) {
            if (existingPaths.contains(path)) {
                continue;
            }
            repo.insertNomenclatureNode(new NomenclatureNode(
                    path,
                    NomenclatureSeed.nameOf(path),
                    NomenclatureSeed.parentOf(path),
                    NomenclatureNode.LAYER_INDUSTRY,
                    false,
                    true,
                    NomenclatureSeed.HOTEL,
                    null), now);
        }
        for (NomenclatureAlias alias : NomenclatureSeed.HOTEL_ALIASES) {
            if (existingAliases.contains(aliasKey(alias))) {
                continue;
            }
            repo.insertNomenclatureAlias(
                    alias, NomenclatureNode.LAYER_INDUSTRY, NomenclatureSeed.HOTEL, null, now);
        }
    }

    private void inTransaction(SqlWork work) throws SQLException {
        Connection connection = repo.connection();
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            work.run();
            repo.commit();
        } catch (SQLException e) {
            repo.rollback();
            throw e;
        } catch (RuntimeException e) {
            try {
                repo.rollback();
            } catch (SQLException rollback) {
                e.addSuppressed(rollback);
            }
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static Optional<String> leafPathForNormalizedAlias(OntologySlice slice, String aliasText) {
        String needle = OntologySlice.normalize(aliasText);
        if (needle.isEmpty()) {
            return Optional.empty();
        }
        return slice.aliases().stream()
                .filter(alias -> OntologySlice.normalize(alias.aliasText()).equals(needle))
                .map(NomenclatureAlias::leafPath)
                .findFirst();
    }

    private static Set<String> pathsOf(List<NomenclatureNode> nodes) {
        Set<String> paths = new HashSet<>();
        for (NomenclatureNode node : nodes) {
            paths.add(node.path());
        }
        return paths;
    }

    private static Set<String> aliasKeys(List<NomenclatureAlias> aliases) {
        Set<String> keys = new HashSet<>();
        for (NomenclatureAlias alias : aliases) {
            keys.add(aliasKey(alias));
        }
        return keys;
    }

    private static String aliasKey(NomenclatureAlias alias) {
        return alias.aliasText() + '\0' + alias.leafPath();
    }

    @FunctionalInterface
    private interface SqlWork {
        void run() throws SQLException;
    }
}
