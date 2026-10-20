package com.resurgent.tev.parser.nomenclature;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * ADR 0022 §2/§3 (#154): a soft leaf must name a category, never copy the row it came from.
 * The shapes below are the ADR's own list — a supplier line, a tax rate, a quantity, a
 * specification, a Less:/Add: qualifier — while a short category still mints.
 */
class OntologyVocabularyTest {

    @Test
    void rowCopiesAreTranscriptionsNotCategories() {
        assertThat(OntologyVocabulary.transcribedLeaf("Supplier - Kone Brothers")).isTrue();
        assertThat(OntologyVocabulary.transcribedLeaf("Elevator (Supplier - Kone Brothers)"))
                .isTrue();
        assertThat(OntologyVocabulary.transcribedLeaf("Vendor: Blue Dart")).isTrue();
        assertThat(OntologyVocabulary.transcribedLeaf("M/s Kone Elevators")).isTrue();
        assertThat(OntologyVocabulary.transcribedLeaf("Freight charges - Blue Dart")).isTrue();
        assertThat(OntologyVocabulary.transcribedLeaf("GST @ 18%")).isTrue();
        assertThat(OntologyVocabulary.transcribedLeaf("No. of Modules")).isTrue();
        assertThat(OntologyVocabulary.transcribedLeaf("Quantity - 10 MW")).isTrue();
        assertThat(OntologyVocabulary.transcribedLeaf("10 MW solar modules")).isTrue();
        assertThat(OntologyVocabulary.transcribedLeaf("DC cable 240 sq mm")).isTrue();
        assertThat(OntologyVocabulary.transcribedLeaf("Less: Input tax credit")).isTrue();
        assertThat(OntologyVocabulary.transcribedLeaf("Add: Customs duty")).isTrue();
        assertThat(OntologyVocabulary.transcribedLeaf("#REF! in row 12")).isTrue();
        assertThat(OntologyVocabulary.transcribedLeaf("#REF!")).isTrue();
        assertThat(OntologyVocabulary.transcribedLeaf("#VALUE!")).isTrue();
        assertThat(OntologyVocabulary.transcribedLeaf("#NAME?")).isTrue();
        assertThat(OntologyVocabulary.transcribedLeaf("#N/A")).isTrue();
        assertThat(OntologyVocabulary.transcribedLeaf("#DIV/0!")).isTrue();
        assertThat(OntologyVocabulary.transcribedLeaf("#NULL!")).isTrue();
    }

    @Test
    void shortCategoryNamesStillMint() {
        assertThat(OntologyVocabulary.transcribedLeaf("Closing Stock")).isFalse();
        assertThat(OntologyVocabulary.transcribedLeaf("Manpower")).isFalse();
        assertThat(OntologyVocabulary.transcribedLeaf("Net Worth")).isFalse();
        assertThat(OntologyVocabulary.transcribedLeaf("Insurance Premium")).isFalse();
        assertThat(OntologyVocabulary.transcribedLeaf("Elevator / Lift")).isFalse();
        assertThat(OntologyVocabulary.transcribedLeaf("Land / Site Development")).isFalse();
        assertThat(OntologyVocabulary.transcribedLeaf("Pre-Operative Expenses")).isFalse();
    }

    /**
     * The hole ADR 0022 describes: path shape alone lets a supplier line through as a
     * category, so the refusal has to read the leaf's content. Pinned here so a later
     * loosening of {@code allowed} cannot quietly re-open it.
     */
    @Test
    void pathShapeAloneAcceptsATranscribedLeaf() {
        assertThat(OntologyVocabulary.allowed("economic",
                "Project Cost > Civil Works > Supplier - Kone Brothers")).isTrue();
        assertThat(OntologyVocabulary.transcribedLeaf("Supplier - Kone Brothers")).isTrue();
        assertThat(OntologyVocabulary.allowed("economic",
                "Profit & Loss > Closing Stock")).isTrue();
        assertThat(OntologyVocabulary.transcribedLeaf("Closing Stock")).isFalse();
    }

    /**
     * The rule both binders consult (ADR 0022 §2): refuse the row copy, follow the
     * catalogue where it uniquely names a leaf.
     */
    @Test
    void bindablePathRefusesARowCopyAndFollowsACatalogueHit() {
        assertThat(OntologyVocabulary.bindablePath(
                "Project Cost > Civil Works > Supplier - Kone Brothers",
                OntologyVocabulary.aliasIndex())).isNull();
        assertThat(OntologyVocabulary.bindablePath(
                "Project Cost > Civil Works > Less AC as per Quotation included Below",
                OntologyVocabulary.aliasIndex()))
                .isEqualTo("Project Cost > Plant & Machinery > Air Conditioning");
    }

    /**
     * The refusal may only ever bite a leaf the ontology has to grow: every seeded path
     * must still bind to itself, or this rule would silently drop the spine it gates on.
     */
    @Test
    void noSeededPathIsRefusedAsATranscription() {
        for (String path : OntologyVocabulary.allowedPaths()) {
            assertThat(OntologyVocabulary.bindablePath(path, OntologyVocabulary.aliasIndex()))
                    .as(path)
                    .isEqualTo(path);
        }
    }
}
