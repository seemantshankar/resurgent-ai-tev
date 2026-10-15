package com.resurgent.tev.doc.compare;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Spec (issue #135; GLOSSARY "Site"): site-name variants (Jaisingpura /
 * Jaisingh Pura / Aisinghpura) match with a loose score that is reported so
 * ambiguous matches can be escalated to the LLM by later tickets; an
 * unrelated village does not match.
 */
class SiteMatchTest {

    /** left raw text, right raw text, expected match. */
    record Case(String left, String right, boolean matched) {}

    static Stream<Case> siteTable() {
        return Stream.of(
                new Case("Jaisingpura", "Jaisingpura", true),
                new Case("Jaisingpura", "Jaisingh Pura", true),
                new Case("Jaisingpura", "Aisinghpura", true),
                new Case("Jaisingh Pura", "Aisinghpura", true),
                new Case("jaisingh  pura.", "JAISINGPURA", true),
                new Case("Jaisingpura", "Bhagyanagar", false),
                new Case("Jaisingpura", "Karawal Nagar", false),
                new Case("Jaisingpura", "Jaisalmer", false));
    }

    @ParameterizedTest
    @MethodSource("siteTable")
    void spellingVariantsMatchAndUnrelatedVillagesDoNot(Case c) {
        SiteMatch match = Comparisons.site(c.left(), c.right());
        assertThat(match.matched())
                .as("%s vs %s (score %.3f)", c.left(), c.right(), match.score())
                .isEqualTo(c.matched());
    }

    @Test
    void siteMatchReportsAScoreForEscalation() {
        // identical names score 1.0 and match
        SiteMatch exact = Comparisons.site("Jaisingpura", "Jaisingpura");
        assertThat(exact.matched()).isTrue();
        assertThat(exact.score()).isEqualTo(1.0);
        // a one-letter spelling variant scores high but is reported, not assumed
        SiteMatch variant = Comparisons.site("Jaisingpura", "Jaisingh Pura");
        assertThat(variant.matched()).isTrue();
        assertThat(variant.score()).isBetween(0.85, 1.0);
        // an unrelated village stays far below the match threshold
        SiteMatch unrelated = Comparisons.site("Jaisingpura", "Bhagyanagar");
        assertThat(unrelated.matched()).isFalse();
        assertThat(unrelated.score()).isLessThan(0.5);
    }

    @Test
    void blankSiteNamesNeverMatch() {
        assertThat(Comparisons.site(null, "Jaisingpura").matched()).isFalse();
        assertThat(Comparisons.site("   ", "Jaisingpura").matched()).isFalse();
        assertThat(Comparisons.site("Jaisingpura", null).score()).isEqualTo(0.0);
    }
}
