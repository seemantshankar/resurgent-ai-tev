package com.resurgent.tev.parser.nomenclature;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Migration 68's data step (#154): deletes the {@code mandate_soft} leaves that copy their
 * row, the ones both binders now refuse at ADR 0022 §2. The decision is the binder's own
 * predicate, so a workspace and a fresh run can never disagree about what a leaf is.
 */
public final class NomenclaturePruner {

    private NomenclaturePruner() {}

    /**
     * Prunes one workspace in place, inside the caller's transaction.
     *
     * @param connection workspace connection
     * @return how many leaves were deleted
     * @throws SQLException if the vocabulary cannot be read or pruned
     */
    public static int pruneTranscribedLeaves(Connection connection) throws SQLException {
        List<String> doomed = new ArrayList<>();
        try (Statement s = connection.createStatement();
                ResultSet rs = s.executeQuery(
                        "SELECT path, name FROM nomenclature_node"
                                + " WHERE layer = '" + NomenclatureNode.LAYER_MANDATE_SOFT + "'")) {
            while (rs.next()) {
                if (OntologyVocabulary.transcribedLeaf(rs.getString("name"))) {
                    doomed.add(rs.getString("path"));
                }
            }
        }
        if (doomed.isEmpty()) {
            return 0;
        }
        try (PreparedStatement node = connection.prepareStatement(
                "DELETE FROM nomenclature_node WHERE layer = ? AND path = ?");
                PreparedStatement alias = connection.prepareStatement(
                        "DELETE FROM nomenclature_alias WHERE leaf_path = ?")) {
            for (String path : doomed) {
                node.setString(1, NomenclatureNode.LAYER_MANDATE_SOFT);
                node.setString(2, path);
                node.executeUpdate();
                // The label that named this leaf would otherwise still resolve to a path
                // the ontology no longer carries.
                alias.setString(1, path);
                alias.executeUpdate();
            }
        }
        return doomed.size();
    }
}
