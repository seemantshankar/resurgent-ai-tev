package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CandidateComposerTest {

    @Test
    void identicalCoverage_recordsReasonAndDoesNotDoubleCount() {
        ExplicitAnchorDetector.Contribution left = contribution(1, "assets:civil", 150, 10);
        ExplicitAnchorDetector.Contribution right = contribution(2, "details:civil", 150, 10);
        ExplicitAnchorDetector.Candidate composed = CandidateComposer.compose(
                "file",
                List.of(candidate(left, right)),
                List.of(),
                List.of(),
                Map.of()).getFirst();

        assertThat(composed.amount()).isEqualByComparingTo("150");
        assertThat(composed.contributions()).hasSize(2);
        assertThat(composed.reasons()).contains("IDENTICAL_COVERAGE");
        assertThat(composed.review()).isFalse();
    }

    @Test
    void allContributionsSuperseded_keepsSourcesAndDoesNotRestoreAmount() {
        ExplicitAnchorDetector.Contribution a = contribution(1, "a:civil", 10, 10);
        ExplicitAnchorDetector.Contribution b = contribution(2, "b:civil", 20, 20);
        ExplicitAnchorDetector.Contribution c = contribution(3, "c:civil", 30, 30);
        List<DuplicateDetector.Proposal> proposals = List.of(
                proposal(1, 2, "a:civil", "b:civil"),
                proposal(1, 3, "a:civil", "c:civil"),
                proposal(2, 3, "b:civil", "c:civil"));
        List<DuplicateDetector.Decision> decisions = List.of(
                new DuplicateDetector.Decision("a:civil", "b:civil", "Duplicate", "a:civil"),
                new DuplicateDetector.Decision("a:civil", "c:civil", "Duplicate", "b:civil"),
                new DuplicateDetector.Decision("b:civil", "c:civil", "Duplicate", "c:civil"));

        ExplicitAnchorDetector.Candidate composed = CandidateComposer.compose(
                "file",
                List.of(candidate(a, b, c)),
                proposals,
                decisions,
                Map.of()).getFirst();

        assertThat(composed.amount()).isEqualByComparingTo("0");
        assertThat(composed.contributions()).hasSize(3);
        assertThat(composed.review()).isTrue();
        assertThat(composed.reasons()).contains("ALL_CONTRIBUTIONS_SUPERSEDED");
    }

    @Test
    void unknownSupersedeKey_doesNotClaimSupersession() {
        ExplicitAnchorDetector.Contribution left = contribution(1, "assets:civil", 150, 10);
        ExplicitAnchorDetector.Contribution right = contribution(2, "details:civil", 150, 20);
        DuplicateDetector.Proposal proposal = proposal(1, 2, "assets:civil", "details:civil");
        DuplicateDetector.Decision decision = new DuplicateDetector.Decision(
                "assets:civil", "details:civil", "Duplicate", "other:civil");

        ExplicitAnchorDetector.Candidate composed = CandidateComposer.compose(
                "file",
                List.of(candidate(left, right)),
                List.of(proposal),
                List.of(decision),
                Map.of()).getFirst();

        assertThat(composed.reasons()).doesNotContain("DUPLICATE_SUPERSEDED");
        assertThat(composed.contributions()).hasSize(2);
        assertThat(composed.amount()).isEqualByComparingTo("150");
    }

    private static ExplicitAnchorDetector.Candidate candidate(
            ExplicitAnchorDetector.Contribution... contributions) {
        return new ExplicitAnchorDetector.Candidate(
                1L,
                "CIVIL",
                "fp",
                BigDecimal.valueOf(60),
                "INR",
                "Rs",
                0.9,
                List.of(),
                false,
                List.of(contributions));
    }

    private static ExplicitAnchorDetector.Contribution contribution(
            long regionId, String key, double amount, long cellId) {
        return new ExplicitAnchorDetector.Contribution(
                regionId,
                key,
                1L,
                cellId,
                "explicit_total_anchor",
                BigDecimal.valueOf(amount),
                "Rs",
                "INR",
                BigDecimal.valueOf(amount),
                "Rs",
                "INR",
                0.9,
                List.of(),
                List.of(new ExplicitAnchorDetector.CellParticipation(
                        cellId, "B2", "included", "")));
    }

    private static DuplicateDetector.Proposal proposal(
            long leftId, long rightId, String leftKey, String rightKey) {
        return new DuplicateDetector.Proposal(
                leftId, rightId, leftKey, rightKey, DuplicateDetector.EXACT, 1.0, List.of());
    }
}
