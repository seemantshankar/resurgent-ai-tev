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
            // stand where this workspace sat before the rule existed: V68 — the newest
            // migration, recorded by its position in WorkspaceDatabase.MIGRATIONS — un-applied
            try (Statement s = db.connection().createStatement()) {
                s.execute("DELETE FROM schema_migration WHERE version >= "
                        + WorkspaceDatabase.latestVersion());
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
}
