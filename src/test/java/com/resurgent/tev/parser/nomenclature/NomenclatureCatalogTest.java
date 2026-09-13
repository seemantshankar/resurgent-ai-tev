package com.resurgent.tev.parser.nomenclature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seam: {@link NomenclatureCatalog} — load the frozen global spine, merge an
 * industry pack, hold a mandate overlay, and assemble the ontology slice.
 */
class NomenclatureCatalogTest {

    @TempDir
    Path tempDir;

    @Test
    void globalSpineIsLoadableWithFrozenCapExMoFAndThinStatements() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(tempDir.resolve("spine.db"))) {
            NomenclatureCatalog catalog = new NomenclatureCatalog(
                    new WorkspaceRepository(db.connection()));
            List<NomenclatureNode> spine = catalog.spine();
            assertThat(spine).extracting(NomenclatureNode::path).contains(
                    "Project Cost > Civil Works",
                    "Project Cost > Plant & Machinery",
                    "Project Cost > Land & Site Development",
                    "Project Cost > Margin Money / Working Capital Margin",
                    "Means of Finance > Term Loan",
                    "Means of Finance > Promoter / Partners' Capital",
                    "Profit & Loss",
                    "Balance Sheet",
                    "Cash Flow");
            OntologySlice slice = catalog.sliceForMandate(1L);

            assertThat(slice.node("Project Cost > Civil Works").orElseThrow().frozen()).isTrue();
            assertThat(slice.node("Project Cost > Plant & Machinery").orElseThrow().frozen()).isTrue();
            assertThat(slice.node("Project Cost > Land & Site Development").orElseThrow().frozen())
                    .isTrue();
            assertThat(slice.node("Project Cost > Margin Money / Working Capital Margin")
                    .orElseThrow().frozen()).isTrue();
            assertThat(slice.node("Means of Finance > Term Loan").orElseThrow().frozen()).isTrue();
            assertThat(slice.node("Means of Finance > Promoter / Partners' Capital")
                    .orElseThrow().frozen()).isTrue();
            assertThat(slice.node("Profit & Loss").orElseThrow().frozen()).isFalse();
            assertThat(slice.node("Balance Sheet").orElseThrow().frozen()).isFalse();
            assertThat(slice.node("Cash Flow").orElseThrow().frozen()).isFalse();
            assertThat(slice.leafPathForAlias("Building Cost").orElseThrow())
                    .isEqualTo("Project Cost > Civil Works");
            assertThat(slice.leafPathForAlias("Civil - Building").orElseThrow())
                    .isEqualTo("Project Cost > Civil Works");
            assertThat(slice.leafPathForAlias("Term Loan From Financial Institutions")
                    .orElseThrow())
                    .isEqualTo("Means of Finance > Term Loan");
        }
    }

    @Test
    void industryPackMergesIntoSliceForMandate() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(tempDir.resolve("hotel.db"))) {
            NomenclatureCatalog catalog = new NomenclatureCatalog(
                    new WorkspaceRepository(db.connection()));
            catalog.confirmIndustry(42L, "hotel");

            OntologySlice slice = catalog.sliceForMandate(42L);
            NomenclatureNode ac = slice.node(
                    "Project Cost > Plant & Machinery > Air Conditioning").orElseThrow();
            assertThat(ac.layer()).isEqualTo(NomenclatureNode.LAYER_INDUSTRY);
            assertThat(ac.leaf()).isTrue();
            assertThat(ac.frozen()).isFalse();
            assertThat(ac.industryTag()).isEqualTo("hotel");
            assertThat(slice.node("Project Cost > Plant & Machinery > Elevator / Lift"))
                    .isPresent();
            assertThat(slice.node("Project Cost > Plant & Machinery > Kitchen Equipments"))
                    .isPresent();
            assertThat(slice.node("Project Cost > Plant & Machinery > Wastewater / ETP"))
                    .isPresent();
            assertThat(slice.leafPathForAlias("Lift").orElseThrow())
                    .isEqualTo("Project Cost > Plant & Machinery > Elevator / Lift");

            OntologySlice bare = catalog.sliceForMandate(7L);
            assertThat(bare.node("Project Cost > Plant & Machinery > Air Conditioning"))
                    .isEmpty();
            assertThat(bare.node("Project Cost > Civil Works")).isPresent();
        }
    }

    @Test
    void mandateOverlayHoldsSoftLeavesUnderKnownParentsOnly() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(tempDir.resolve("overlay.db"))) {
            NomenclatureCatalog catalog = new NomenclatureCatalog(
                    new WorkspaceRepository(db.connection()));
            catalog.confirmIndustry(9L, "hotel");
            catalog.putSoftLeaf(9L, "Project Cost > Plant & Machinery", "Kone Elevator",
                    List.of("Kone Elevator India"));

            OntologySlice slice = catalog.sliceForMandate(9L);
            NomenclatureNode kone = slice.node(
                    "Project Cost > Plant & Machinery > Kone Elevator").orElseThrow();
            assertThat(kone.layer()).isEqualTo(NomenclatureNode.LAYER_MANDATE_SOFT);
            assertThat(kone.leaf()).isTrue();
            assertThat(kone.frozen()).isFalse();
            assertThat(kone.mandateId()).isEqualTo(9L);
            assertThat(slice.leafPathForAlias("Kone Elevator India").orElseThrow())
                    .isEqualTo("Project Cost > Plant & Machinery > Kone Elevator");

            catalog.confirmIndustry(10L, "hotel");
            assertThat(catalog.sliceForMandate(10L)
                    .node("Project Cost > Plant & Machinery > Kone Elevator")).isEmpty();

            assertThatThrownBy(() -> catalog.putSoftLeaf(
                    9L, "Project Cost > Spaceships", "Warp Core", List.of()))
                    .isInstanceOf(NomenclatureException.class)
                    .hasMessageContaining("mid-level");
            assertThatThrownBy(() -> catalog.putSoftLeaf(
                    9L, "Project Cost > Plant & Machinery > Air Conditioning",
                    "Chillers", List.of()))
                    .isInstanceOf(NomenclatureException.class)
                    .hasMessageContaining("mid-level");
        }
    }

    @Test
    void missingIndustryUsesNonBlockingInferConfirmStub() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(tempDir.resolve("infer.db"))) {
            NomenclatureCatalog catalog = new NomenclatureCatalog(
                    new WorkspaceRepository(db.connection()));

            OntologySlice inferred = catalog.sliceForMandate(3L, "hotel");
            assertThat(inferred.industry().needsConfirm()).isTrue();
            assertThat(inferred.industry().inferred()).isTrue();
            assertThat(inferred.industry().confirmed()).isFalse();
            assertThat(inferred.industry().industryTag()).isEqualTo("hotel");
            assertThat(inferred.node("Project Cost > Plant & Machinery > Air Conditioning"))
                    .isPresent();
            assertThat(inferred.node("Project Cost > Civil Works")).isPresent();

            catalog.confirmIndustry(3L, "hotel");
            OntologySlice confirmed = catalog.sliceForMandate(3L);
            assertThat(confirmed.industry().needsConfirm()).isFalse();
            assertThat(confirmed.industry().confirmed()).isTrue();
            assertThat(confirmed.node("Project Cost > Plant & Machinery > Air Conditioning"))
                    .isPresent();

            OntologySlice unspecified = catalog.sliceForMandate(4L);
            assertThat(unspecified.industry().needsConfirm()).isTrue();
            assertThat(unspecified.industry().inferred()).isTrue();
            assertThat(unspecified.industry().industryTag()).isEqualTo("unspecified");
            assertThat(unspecified.node("Project Cost")).isPresent();
            assertThat(unspecified.node("Project Cost > Plant & Machinery > Air Conditioning"))
                    .isEmpty();
        }
    }

    @Test
    void sliceRoundTripsAfterReopenWithoutChangingCandidateGeometry() throws Exception {
        Path dbPath = tempDir.resolve("roundtrip.db");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            NomenclatureCatalog catalog = new NomenclatureCatalog(
                    new WorkspaceRepository(db.connection()));
            catalog.confirmIndustry(5L, "hotel");
            catalog.putSoftLeaf(5L, "Project Cost > Civil Works", "Interior Fit-out",
                    List.of("Interior"));
        }
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            List<String> tables = new java.util.ArrayList<>();
            try (var rs = db.connection().getMetaData()
                    .getTables(null, null, "%", new String[] {"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
            assertThat(tables).contains(
                    "nomenclature_node", "nomenclature_alias", "mandate_industry",
                    "candidate", "candidate_member", "candidate_related");
            assertThat(tables).doesNotContain("region", "cost_head");

            NomenclatureCatalog catalog = new NomenclatureCatalog(
                    new WorkspaceRepository(db.connection()));
            OntologySlice slice = catalog.sliceForMandate(5L);
            assertThat(slice.industry().confirmed()).isTrue();
            assertThat(slice.industry().industryTag()).isEqualTo("hotel");
            assertThat(slice.node("Project Cost > Plant & Machinery > Air Conditioning"))
                    .isPresent();
            assertThat(slice.node("Project Cost > Civil Works > Interior Fit-out")).isPresent();
            assertThat(slice.leafPathForAlias("Interior").orElseThrow())
                    .isEqualTo("Project Cost > Civil Works > Interior Fit-out");
        }
    }
}
