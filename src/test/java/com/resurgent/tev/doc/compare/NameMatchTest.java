package com.resurgent.tev.doc.compare;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Spec (issue #135; GLOSSARY "Entity"): company names compare loosely —
 * case, punctuation, whitespace and legal-suffix equivalences (Pvt Ltd /
 * Private Limited, Ltd / Limited, LLP). Genuinely different names do not match.
 */
class NameMatchTest {

    /** left raw text, right raw text, expected verdict. */
    record Case(String left, String right, Verdict expected) {}

    static Stream<Case> nameTable() {
        return Stream.of(
                // legal-form equivalences
                new Case("ABC Solar Pvt Ltd", "ABC Solar Private Limited", Verdict.AGREE),
                new Case("ABC Solar Ltd", "ABC Solar Limited", Verdict.AGREE),
                new Case("ABC Developers LLP", "ABC Developers LLP", Verdict.AGREE),
                new Case("ABC Solar Pvt. Ltd.", "ABC Solar Private Limited", Verdict.AGREE),
                // punctuation, case and initials
                new Case("A.B.C. Traders Pvt. Ltd.", "ABC Traders Private Limited", Verdict.AGREE),
                new Case("abc solar pvt ltd", "ABC Solar Pvt Ltd", Verdict.AGREE),
                // whitespace, including newlines
                new Case("ABC   Solar\nPvt  Ltd", "ABC Solar Pvt Ltd", Verdict.AGREE),
                // genuinely different names do not match
                new Case("ABC Solar Pvt Ltd", "XYZ Solar Pvt Ltd", Verdict.DISAGREE),
                new Case("ABC Solar Pvt Ltd", "ABC Wind Pvt Ltd", Verdict.DISAGREE),
                new Case("ABC Solar Pvt Ltd", "ABC Solar Energy Pvt Ltd", Verdict.DISAGREE),
                // nothing to compare
                new Case("", "ABC Pvt Ltd", Verdict.NOT_COMPARABLE),
                new Case("   ", "ABC Pvt Ltd", Verdict.NOT_COMPARABLE),
                new Case("Pvt Ltd", "ABC Pvt Ltd", Verdict.NOT_COMPARABLE),
                new Case(null, "ABC Pvt Ltd", Verdict.NOT_COMPARABLE));
    }

    @ParameterizedTest
    @MethodSource("nameTable")
    void namesMatchLooselyAcrossCasePunctuationAndLegalForm(Case c) {
        assertThat(Comparisons.name(c.left(), c.right()).verdict()).isEqualTo(c.expected());
    }
}
