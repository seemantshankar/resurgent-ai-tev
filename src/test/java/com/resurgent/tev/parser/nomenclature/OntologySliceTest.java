package com.resurgent.tev.parser.nomenclature;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit: {@link OntologySlice} label resolution. Order must not decide an answer,
 * a qualified label must not be stolen by a prefix, and a name living under more
 * than one parent must report ambiguous rather than the first row in list order.
 */
class OntologySliceTest {

    private static NomenclatureNode mid(String path, String name, String parent) {
        return new NomenclatureNode(path, name, parent, NomenclatureNode.LAYER_SPINE,
                true, false, null, null);
    }

    private static NomenclatureNode leaf(String path, String name, String parent) {
        return new NomenclatureNode(path, name, parent, NomenclatureNode.LAYER_INDUSTRY,
                false, true, "hotel", null);
    }

    private static OntologySlice slice(List<NomenclatureNode> nodes, List<NomenclatureAlias> aliases) {
        return new OntologySlice(IndustryResolution.confirmed("hotel"), nodes, aliases);
    }

    private static final String DEPRECIATION = "Less: Depreciation @ 10 %";

    private OntologySlice multiParentSlice() {
        return slice(List.of(
                mid("Project Cost", "Project Cost", null),
                mid("Project Cost > Civil Works", "Civil Works", "Project Cost"),
                leaf("Project Cost > Civil Works > Building", "Building",
                        "Project Cost > Civil Works"),
                leaf("Project Cost > Civil Works > " + DEPRECIATION, DEPRECIATION,
                        "Project Cost > Civil Works"),
                leaf("Project Cost > Plant & Machinery > " + DEPRECIATION, DEPRECIATION,
                        "Project Cost > Plant & Machinery"),
                mid("Project Cost > Plant & Machinery", "Plant & Machinery", "Project Cost")),
                List.of());
    }

    @Test
    void nodeLookupNormalisesWhitespaceAndCase() {
        OntologySlice slice = multiParentSlice();

        assertThat(slice.node("Project Cost > Civil Works > Building")).isPresent();
        assertThat(slice.node("project cost  >  civil works > building")).isPresent();
        assertThat(slice.node("Project Cost > Civil Works > Basement")).isEmpty();
    }

    @Test
    void aNameUnderMoreThanOneParentIsAmbiguousNotFirstInListOrder() {
        OntologySlice slice = multiParentSlice();

        OntologySlice.Resolution resolution = slice.resolveLabel(DEPRECIATION);

        assertThat(resolution.kind()).isEqualTo(OntologySlice.Resolution.Kind.AMBIGUOUS);
        assertThat(resolution.paths()).containsExactlyInAnyOrder(
                "Project Cost > Civil Works > " + DEPRECIATION,
                "Project Cost > Plant & Machinery > " + DEPRECIATION);
        assertThat(slice.resolve(DEPRECIATION)).isEmpty();
    }

    @Test
    void aQualifiedLabelPicksTheLeafUnderThatQualifierNotThePrefixMatch() {
        OntologySlice slice = multiParentSlice();

        assertThat(slice.resolve("BUILDING > " + DEPRECIATION))
                .contains("Project Cost > Civil Works > " + DEPRECIATION);
        assertThat(slice.resolve("PLANT & MACHINERY > " + DEPRECIATION))
                .contains("Project Cost > Plant & Machinery > " + DEPRECIATION);
    }

    @Test
    void aQualifiedLabelNeverPrefixMatchesItsFirstSegment() {
        OntologySlice slice = multiParentSlice();

        assertThat(slice.resolve("Building > Some Unknown Sub Line")).isEmpty();
    }

    @Test
    void exactNameStillResolvesAndPrefixMatchStillWorksForUnqualifiedLabels() {
        OntologySlice slice = multiParentSlice();

        assertThat(slice.resolve("Building")).contains("Project Cost > Civil Works > Building");
        assertThat(slice.resolve("Building (Block A)"))
                .contains("Project Cost > Civil Works > Building");
    }

    @Test
    void anAliasUnderTwoLeavesIsAmbiguous() {
        OntologySlice slice = slice(
                List.of(
                        mid("Project Cost", "Project Cost", null),
                        leaf("Project Cost > A", "A", "Project Cost"),
                        leaf("Project Cost > B", "B", "Project Cost")),
                List.of(
                        new NomenclatureAlias("Shared", "Project Cost > A"),
                        new NomenclatureAlias("Shared", "Project Cost > B")));

        assertThat(slice.resolveLabel("Shared").kind())
                .isEqualTo(OntologySlice.Resolution.Kind.AMBIGUOUS);
        assertThat(slice.resolve("Shared")).isEmpty();
    }

    @Test
    void anUnknownLabelResolvesToNone() {
        assertThat(multiParentSlice().resolveLabel("Nothing Like This").kind())
                .isEqualTo(OntologySlice.Resolution.Kind.NONE);
    }
}
