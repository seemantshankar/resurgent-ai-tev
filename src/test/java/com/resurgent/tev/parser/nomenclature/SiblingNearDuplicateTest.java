package com.resurgent.tev.parser.nomenclature;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #158: a soft leaf that only restates a sibling — {@code &} versus {@code and},
 * a plural, punctuation, or the same words in another order — binds to that sibling.
 */
class SiblingNearDuplicateTest {

    private static final String PARENT = "Project Cost > Plant & Machinery";
    private static final String FREIGHT = PARENT + " > Freight & Forwarding";

    @Test
    void ampersandAndPluralPunctuationAndWordOrderReuseTheSibling() {
        assertThat(SiblingNearDuplicate.prefer(
                PARENT + " > Freight and Forwarding", List.of(FREIGHT)))
                .isEqualTo(FREIGHT);
        assertThat(SiblingNearDuplicate.prefer(
                PARENT + " > Freight & Forwarding.", List.of(FREIGHT)))
                .isEqualTo(FREIGHT);
        assertThat(SiblingNearDuplicate.prefer(
                PARENT + " > Forwarding & Freight", List.of(FREIGHT)))
                .isEqualTo(FREIGHT);
        assertThat(SiblingNearDuplicate.prefer(
                PARENT + " > Module Mounting Structure",
                List.of(PARENT + " > Module Mounting Structures")))
                .isEqualTo(PARENT + " > Module Mounting Structures");
    }

    @Test
    void aDifferentCategoryOrADifferentParentStaysNew() {
        assertThat(SiblingNearDuplicate.prefer(
                PARENT + " > Transformer", List.of(FREIGHT)))
                .isNull();
        assertThat(SiblingNearDuplicate.prefer(
                PARENT + " > Solar PV Modules",
                List.of(PARENT + " > Modules")))
                .isNull();
        assertThat(SiblingNearDuplicate.prefer(
                "Project Cost > Tax",
                List.of("Profit & Loss > Tax")))
                .isNull();
        assertThat(SiblingNearDuplicate.prefer(FREIGHT, List.of(FREIGHT)))
                .isNull();
    }

    @Test
    void otherJoinWordsAndAShorterPluralStayDistinctWhileTaxMatches() {
        String expenses = "Profit & Loss > Operating Expenses";
        assertThat(SiblingNearDuplicate.prefer(
                expenses + " > Interest for Term Loan",
                List.of(expenses + " > Interest on Term Loan")))
                .isNull();
        assertThat(SiblingNearDuplicate.prefer(PARENT + " > Taxes", List.of(PARENT + " > Tax")))
                .isEqualTo(PARENT + " > Tax");
        assertThat(SiblingNearDuplicate.shaped(PARENT + " > Freight & Forwarding."))
                .isEqualTo(FREIGHT);
        assertThat(SiblingNearDuplicate.shaped(PARENT + " > Freight, Forwarding"))
                .isEqualTo(PARENT + " > Freight Forwarding");
    }

    @Test
    void anAliasUnderTheSameParentWinsWhenTheWordsDiffer() {
        String inverters = PARENT + " > Inverters / PCU";
        Map<String, String> aliases = Map.of(OntologyVocabulary.norm("Inverter"), inverters);

        assertThat(SiblingNearDuplicate.aliasedSibling(PARENT + " > Inverter", aliases))
                .isEqualTo(inverters);
        assertThat(SiblingNearDuplicate.aliasedSibling("Project Cost > Inverter", aliases))
                .isNull();
    }
}
