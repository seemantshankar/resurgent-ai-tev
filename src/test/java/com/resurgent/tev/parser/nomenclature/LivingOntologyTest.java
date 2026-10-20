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

    @Test
    void hardAliasesRetainsSpineAndIndustryAndExcludesMandateSoft() {
        NomenclatureNode spineNode = new NomenclatureNode(
                "Project Cost > Plant & Machinery", "Plant & Machinery", "Project Cost",
                NomenclatureNode.LAYER_SPINE, true, true, null, null);
        NomenclatureNode industryNode = new NomenclatureNode(
                "Project Cost > Plant & Machinery > Boiler", "Boiler", "Project Cost > Plant & Machinery",
                NomenclatureNode.LAYER_INDUSTRY, false, true, "hotel", null);
        NomenclatureNode softNode = new NomenclatureNode(
                "Project Cost > Civil Works > Soft Category", "Soft Category", "Project Cost > Civil Works",
                NomenclatureNode.LAYER_MANDATE_SOFT, false, true, null, 7L);

        List<NomenclatureAlias> aliases = List.of(
                new NomenclatureAlias("Steam Generator", "Project Cost > Plant & Machinery > Boiler"),
                new NomenclatureAlias("Custom Soft Alias", "Project Cost > Civil Works > Soft Category"));

        OntologySlice slice = new OntologySlice(
                IndustryResolution.confirmed("hotel"),
                List.of(spineNode, industryNode, softNode),
                aliases);

        LivingOntology living = LivingOntology.from(slice);

        // Living aliases includes both hard and soft
        assertThat(living.aliases().get("STEAM GENERATOR"))
                .isEqualTo("Project Cost > Plant & Machinery > Boiler");
        assertThat(living.aliases().get("CUSTOM SOFT ALIAS"))
                .isEqualTo("Project Cost > Civil Works > Soft Category");
        assertThat(living.aliases().get("SOFT CATEGORY"))
                .isEqualTo("Project Cost > Civil Works > Soft Category");

        // Hard aliases includes hard nodes and aliases, but excludes mandate_soft destinations
        assertThat(living.hardAliases().get("STEAM GENERATOR"))
                .isEqualTo("Project Cost > Plant & Machinery > Boiler");
        assertThat(living.hardAliases().get("BOILER"))
                .isEqualTo("Project Cost > Plant & Machinery > Boiler");
        assertThat(living.hardAliases().get("PLANT & MACHINERY"))
                .isEqualTo("Project Cost > Plant & Machinery");
        assertThat(living.hardAliases().containsKey("CUSTOM SOFT ALIAS")).isFalse();
        assertThat(living.hardAliases().containsKey("SOFT CATEGORY")).isFalse();

        // Growing withLeaf adds to aliases but not hardAliases
        LivingOntology grown = living.withLeaf("Project Cost > New Soft", "New Soft");
        assertThat(grown.aliases().containsKey("NEW SOFT")).isTrue();
        assertThat(grown.hardAliases().containsKey("NEW SOFT")).isFalse();
    }
}
