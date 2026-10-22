package com.resurgent.tev.parser.nomenclature;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #154 (ADR 0022 §2 read backwards, §4 in place of its missing harness): a workspace that
 * grew leaves copying their row is pruned when it is next opened, and after that no
 * mandate leaf the binders would now refuse survives anywhere in the ontology.
 */
class NomenclaturePruningTest {

    @TempDir
    Path tempDir;

    @Test
    void openingAWorkspacePrunesLeavesThatCopyTheirRowAndKeepsRealCategories() throws Exception {
        Path dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            NomenclatureCatalog catalog = new NomenclatureCatalog(
                    new WorkspaceRepository(db.connection()));
            catalog.confirmIndustry(7L, "hotel");
            catalog.putSoftLeaf(7L, "Project Cost > Civil Works", "Supplier - Kone Brothers",
                    List.of("Supplier - Kone Brothers"));
            catalog.putSoftLeaf(7L, "Project Cost > Civil Works", "GST @ 18%",
                    List.of("GST @ 18%"));
            catalog.putSoftLeaf(7L, "Profit & Loss", "Closing Stock", List.of("Closing Stock"));
            // stand where this workspace sat before the rule existed: V68 — un-applied
            try (Statement s = db.connection().createStatement()) {
                s.execute("DELETE FROM schema_migration WHERE version = "
                        + WorkspaceDatabase.versionOf(WorkspaceDatabase.V68_PRUNE_TRANSCRIBED_LEAVES));
            }
        }

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            OntologySlice slice = new NomenclatureCatalog(new WorkspaceRepository(db.connection()))
                    .sliceForMandate(7L);
            assertThat(slice.node("Project Cost > Civil Works > Supplier - Kone Brothers"))
                    .isEmpty();
            assertThat(slice.node("Project Cost > Civil Works > GST @ 18%")).isEmpty();
            assertThat(slice.node("Profit & Loss > Closing Stock")).isPresent();
            assertThat(slice.aliases())
                    .noneSatisfy(alias -> assertThat(alias.leafPath())
                            .endsWith("Supplier - Kone Brothers"));
            assertThat(slice.leafPathForAlias("Closing Stock"))
                    .contains("Profit & Loss > Closing Stock");
            for (NomenclatureNode node : slice.nodes()) {
                if (!NomenclatureNode.LAYER_MANDATE_SOFT.equals(node.layer())) {
                    continue;
                }
                assertThat(OntologyVocabulary.transcribedLeaf(node.name()))
                        .as(node.path())
                        .isFalse();
            }
        }
    }

    @Test
    void pruneReconcilesBindingsInterpretationsAndFacts() throws Exception {
        Path dbPath = tempDir.resolve("reconcile.db");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            java.sql.Connection c = db.connection();
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA foreign_keys = OFF");
                s.execute("INSERT INTO nomenclature_node (path, name, layer, parent_path, frozen, leaf, created_at) "
                        + "VALUES ('Project Cost > Civil Works > Supplier - Kone Brothers', 'Supplier - Kone Brothers', 'mandate_soft', 'Project Cost > Civil Works', 0, 1, '2026-01-01')");
                s.execute("INSERT INTO nomenclature_alias (alias_text, leaf_path, layer, created_at) "
                        + "VALUES ('Supplier - Kone Brothers', 'Project Cost > Civil Works > Supplier - Kone Brothers', 'mandate_soft', '2026-01-01')");

                s.execute("INSERT INTO nomenclature_binding (parse_run_id, candidate_id, cell_id, path_root, path, created_at) "
                        + "VALUES (1, 1, 1, 'economic', 'Project Cost > Civil Works > Supplier - Kone Brothers', '2026-01-01')");
                s.execute("INSERT INTO cell_interpretation (parse_run_id, cell_id, value_origin, result_source, nomenclature_path, nomenclature_status, created_at) "
                        + "VALUES (1, 1, 'literal', 'literal', 'Project Cost > Civil Works > Supplier - Kone Brothers', 'bound', '2026-01-01')");
                s.execute("INSERT INTO fact (fact_id, parse_run_id, document_version_id, ontology_path, path_root, value_kind, extraction_method, nomenclature_status, created_at) "
                        + "VALUES (1, 1, 1, 'Project Cost > Civil Works > Supplier - Kone Brothers', 'economic', 'money', 'llm_field', 'bound', '2026-01-01')");
            }

            int pruned = NomenclaturePruner.pruneTranscribedLeaves(c);
            assertThat(pruned).isEqualTo(1);

            try (Statement s = c.createStatement()) {
                try (java.sql.ResultSet rs = s.executeQuery(
                        "SELECT count(*) FROM nomenclature_binding WHERE path = 'Project Cost > Civil Works > Supplier - Kone Brothers'")) {
                    assertThat(rs.next() && rs.getInt(1) == 0).isTrue();
                }
                try (java.sql.ResultSet rs = s.executeQuery(
                        "SELECT nomenclature_status, unbound_reason FROM cell_interpretation WHERE cell_id = 1")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("nomenclature_status")).isEqualTo("unbound");
                    assertThat(rs.getString("unbound_reason")).isEqualTo("transcribed_label");
                }
                try (java.sql.ResultSet rs = s.executeQuery(
                        "SELECT nomenclature_status, unbound_reason, ontology_path FROM fact WHERE fact_id = 1")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("nomenclature_status")).isEqualTo("unbound");
                    assertThat(rs.getString("unbound_reason")).isEqualTo("transcribed_label");
                    assertThat(rs.getString("ontology_path")).isEqualTo("Project Cost > Civil Works > Supplier - Kone Brothers");
                }
            }
        }
    }
}
