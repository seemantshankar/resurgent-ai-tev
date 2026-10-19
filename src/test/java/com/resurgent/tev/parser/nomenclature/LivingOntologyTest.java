package com.resurgent.tev.parser.nomenclature;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit: {@link LivingOntology#withLeaf} collision handling (PR #155 review). A grown
 * leaf must follow the shared alias rule — a label naming two paths becomes ambiguous
 * rather than silently replacing the earlier mapping the next bind reads.
 */
class LivingOntologyTest {

    private static NomenclatureNode mid(String path, String name, String parent) {
        return new NomenclatureNode(path, name, parent, NomenclatureNode.LAYER_SPINE,
                true, false, null, null);
    }

    private static LivingOntology ontologyWith(List<NomenclatureAlias> aliases) {
        return LivingOntology.from(new OntologySlice(
                IndustryResolution.confirmed("hotel"),
                List.of(mid("Balance Sheet", "Balance Sheet", null)),
                aliases));
    }

    @Test
    void aCollisioningAliasBecomesAmbiguousInsteadOfReplacingTheKnownPath() {
        LivingOntology living = ontologyWith(List.of(
                new NomenclatureAlias("Depreciation", "Balance Sheet > Accumulated Depreciation")));

        LivingOntology grown = living.withLeaf(
                "Balance Sheet > Cost of Depreciation", "Depreciation");

        assertThat(grown.aliases().get("DEPRECIATION")).isEmpty();
        assertThat(grown.contains("Balance Sheet > Cost of Depreciation")).isTrue();
    }

    @Test
    void aFreeLabelAndPathAreRegisteredForTheGrownLeaf() {
        LivingOntology living = ontologyWith(List.of());

        LivingOntology grown = living.withLeaf("Balance Sheet > Total Assets", "Total Assets");

        assertThat(grown.aliases().get("TOTAL ASSETS"))
                .isEqualTo("Balance Sheet > Total Assets");
        assertThat(grown.aliases().get("BALANCE SHEET TOTAL ASSETS"))
                .isEqualTo("Balance Sheet > Total Assets");
    }

    @Test
    void anAlreadyAmbiguousLabelStaysAmbiguous() {
        LivingOntology living = ontologyWith(List.of(
                new NomenclatureAlias("Depreciation", "Balance Sheet > Accumulated Depreciation"),
                new NomenclatureAlias("Depreciation", "Profit & Loss > Depreciation")));
        assertThat(living.aliases().get("DEPRECIATION")).isEmpty();

        LivingOntology grown = living.withLeaf(
                "Balance Sheet > Less Depreciation", "Depreciation");

        assertThat(grown.aliases().get("DEPRECIATION")).isEmpty();
    }
}
