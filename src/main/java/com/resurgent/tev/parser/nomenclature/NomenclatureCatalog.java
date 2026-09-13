package com.resurgent.tev.parser.nomenclature;

import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Loads the frozen bank spine and assembles the ontology slice a mandate's
 * Packet classification will send. No LLM calls.
 */
public final class NomenclatureCatalog {

    private final WorkspaceRepository repo;

    public NomenclatureCatalog(WorkspaceRepository repo) {
        this.repo = Objects.requireNonNull(repo, "repo");
        try {
            seedSpineIfEmpty();
            seedHotelPackIfEmpty();
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
        try {
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
                repo.insertNomenclatureAlias(
                        new NomenclatureAlias(aliasText, path),
                        NomenclatureNode.LAYER_MANDATE_SOFT,
                        null,
                        mandateId,
                        now);
            }
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
        if (repo.countNomenclatureSpine() > 0) {
            return;
        }
        String now = Timestamps.now();
        for (String path : NomenclatureSeed.SPINE_PATHS) {
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
            repo.insertNomenclatureAlias(
                    alias, NomenclatureNode.LAYER_SPINE, null, null, now);
        }
    }

    private void seedHotelPackIfEmpty() throws SQLException {
        if (repo.countNomenclatureIndustry(NomenclatureSeed.HOTEL) > 0) {
            return;
        }
        String now = Timestamps.now();
        for (String path : NomenclatureSeed.HOTEL_LEAVES) {
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
            repo.insertNomenclatureAlias(
                    alias, NomenclatureNode.LAYER_INDUSTRY, NomenclatureSeed.HOTEL, null, now);
        }
    }
}
