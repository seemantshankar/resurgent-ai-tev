package com.resurgent.tev.doc.assess;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure helpers of the entity scan (issue #136): role normalisation, loose name
 * equality through the #135 comparison module, and token containment for
 * anchoring a fact's quoted text to an entity.
 */
class EntityNamesTest {

    @Test
    void roleNormalisationAcceptsTheThreeMandateRoles() {
        assertThat(EntityNames.normaliseRole("Borrower")).isEqualTo("borrower");
        assertThat(EntityNames.normaliseRole("borrowers")).isEqualTo("borrower");
        assertThat(EntityNames.normaliseRole(" Promoter ")).isEqualTo("promoter");
        assertThat(EntityNames.normaliseRole("PROMOTERS")).isEqualTo("promoter");
        assertThat(EntityNames.normaliseRole("group company")).isEqualTo("group_company");
        assertThat(EntityNames.normaliseRole("Group Companies")).isEqualTo("group_company");
        assertThat(EntityNames.normaliseRole("group_company")).isEqualTo("group_company");
    }

    @Test
    void roleNormalisationRefusesAnythingElse() {
        assertThat(EntityNames.normaliseRole("director")).isNull();
        assertThat(EntityNames.normaliseRole("lender")).isNull();
        assertThat(EntityNames.normaliseRole("")).isNull();
        assertThat(EntityNames.normaliseRole(null)).isNull();
    }

    @Test
    void legalSpellingVariantsAreOneName() {
        assertThat(EntityNames.sameName("ABC Pvt Ltd", "ABC Private Limited")).isTrue();
        assertThat(EntityNames.sameName("A.B.C. Traders", "ABC Traders")).isTrue();
        assertThat(EntityNames.sameName("Ramesh Kumar Sharma", "RAMESH KUMAR SHARMA")).isTrue();
        assertThat(EntityNames.sameName("ABC Pvt Ltd", "XYZ Pvt Ltd")).isFalse();
        // a name that is nothing but a legal form is not usable as an entity name
        assertThat(EntityNames.sameName("Pvt Ltd", "Pvt Ltd")).isFalse();
    }

    @Test
    void containmentFindsTheNameInsideQuotedText() {
        assertThat(EntityNames.containsName(
                "Net Worth of Ramesh Kumar Sharma as on 31st March 2026",
                "Ramesh Kumar Sharma")).isTrue();
        assertThat(EntityNames.containsName(
                "This deed is executed between ABC Pvt Ltd (the Borrower) and the Bank",
                "ABC Pvt. Ltd.")).isTrue();
        assertThat(EntityNames.containsName(
                "executed between ABC Pvt Ltd (the Borrower) and the Bank",
                "ABC Private Limited")).isFalse();
        assertThat(EntityNames.containsName(
                "M/s ABC Pvt Ltd has requested a facility", "ABC Pvt Ltd")).isTrue();
        assertThat(EntityNames.containsName("Ramesh Kumar Sharma", "Rakesh Kumar")).isFalse();
        assertThat(EntityNames.containsName("anything", "")).isFalse();
        assertThat(EntityNames.containsName(null, "ABC")).isFalse();
        assertThat(EntityNames.containsName("anything", null)).isFalse();
    }
}
