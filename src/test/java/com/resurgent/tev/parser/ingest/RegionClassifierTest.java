package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RegionClassifierTest {
    private final RegionClassifier classifier = new RegionClassifier();

    @Test
    void exactCostHeadAliasWinsAndCarriesOnlyTheLockedCode() {
        RegionClassification result = classifier.classify(bounds(),
                List.of(new RegionClassifier.RegionCell(4, 1, "Plant & Machinery", false, false)),
                new RegionClassifier.HeaderContext(List.of(4), List.of("Particulars", "Amount")));

        assertThat(result.type()).isEqualTo(RegionType.COST_HEAD);
        assertThat(result.costHeadCode()).isEqualTo("PLANT_MACHINERY");
        assertThat(result.reasons()).extracting(DetectionReason::code)
                .containsExactly(DetectionReason.Code.COST_HEAD_ALIAS);
        assertThat(result.reasons().getFirst().params()).containsOnly(Map.entry("match_count", 1L));
    }

    @Test
    void costHeadDoesNotUseSubstringOrFuzzyMatching() {
        RegionClassification result = classifier.classify(bounds(),
                List.of(new RegionClassifier.RegionCell(4, 1, "civil engineering works", false, false)),
                new RegionClassifier.HeaderContext(List.of(), List.of()));

        assertThat(result.type()).isEqualTo(RegionType.UNKNOWN);
        assertThat(result.costHeadCode()).isNull();
        assertThat(result.reasons()).extracting(DetectionReason::code)
                .containsExactly(DetectionReason.Code.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void confidenceIsTheMarginBetweenPnlAndBalanceSheetScores() {
        RegionClassification result = classifier.classify(bounds(), List.of(),
                new RegionClassifier.HeaderContext(List.of(1),
                        List.of("Assets", "Liabilities", "Revenue", "Expense")));

        assertThat(result.type()).isEqualTo(RegionType.PNL);
        assertThat(result.confidence()).isZero();
        assertThat(result.reasons()).extracting(DetectionReason::code)
                .containsExactly(DetectionReason.Code.STATEMENT_SHAPE);
    }

    @Test
    void structuredReasonsRejectTextualParameters() {
        assertThatThrownBy(() -> new DetectionReason(DetectionReason.Code.HEADER_TOKEN, 2,
                (Map) Map.of("token", "amount")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void serialPatternClassifiesVendorBlockWithTextFreeReason() {
        RegionClassification result = classifier.classify(bounds(), List.of(
                new RegionClassifier.RegionCell(2, 1, "1", false, true),
                new RegionClassifier.RegionCell(3, 1, "2", false, true),
                new RegionClassifier.RegionCell(4, 1, "3", false, true)),
                new RegionClassifier.HeaderContext(List.of(), List.of()));

        assertThat(result.type()).isEqualTo(RegionType.VENDOR_BLOCK);
        assertThat(result.reasons()).extracting(DetectionReason::code)
                .containsExactly(DetectionReason.Code.SERIAL_PATTERN);
        assertThat(result.reasons().getFirst().params())
                .containsOnly(Map.entry("serial_count", 3L), Map.entry("serial_pattern", 1L));
    }

    @Test
    void doesNotClassifyAmbiguousCostHeadAliasWithoutContext() {
        RegionClassification result = classifier.classify(bounds(),
                List.of(new RegionClassifier.RegionCell(4, 1, "equipment", false, false)),
                new RegionClassifier.HeaderContext(List.of(), List.of()));

        assertThat(result.type()).isEqualTo(RegionType.UNKNOWN);
    }

    @Test
    void yearHeaderAloneStaysUnknownWithHeaderTokenScoreBelowTheEvidenceFloor() {
        RegionClassification result = classifier.classify(bounds(), List.of(),
                new RegionClassifier.HeaderContext(List.of(1), List.of("Year 1")));

        assertThat(result.type()).isEqualTo(RegionType.UNKNOWN);
        assertThat(result.reasons()).extracting(DetectionReason::code)
                .containsExactly(DetectionReason.Code.INSUFFICIENT_EVIDENCE);
        assertThat(result.reasons().getFirst().params()).containsEntry("top_score", 2L);
    }

    @Test
    void requiresARealSerialSequenceRatherThanTwoSerialLookingValues() {
        RegionClassification result = classifier.classify(bounds(), List.of(
                new RegionClassifier.RegionCell(2, 1, "1", false, true),
                new RegionClassifier.RegionCell(3, 1, "2", false, true)),
                new RegionClassifier.HeaderContext(List.of(), List.of()));

        assertThat(result.type()).isEqualTo(RegionType.UNKNOWN);
    }

    @Test
    void recognizesAlphabeticAndMixedSerialSequences() {
        RegionClassification alpha = classifier.classify(bounds(), List.of(
                new RegionClassifier.RegionCell(1, 1, "A.", false, false),
                new RegionClassifier.RegionCell(2, 1, "B.", false, false),
                new RegionClassifier.RegionCell(3, 1, "C.", false, false)),
                new RegionClassifier.HeaderContext(List.of(), List.of()));
        RegionClassification mixed = classifier.classify(bounds(), List.of(
                new RegionClassifier.RegionCell(1, 1, "1", false, true),
                new RegionClassifier.RegionCell(2, 1, "2", false, true),
                new RegionClassifier.RegionCell(3, 1, "A.", false, false),
                new RegionClassifier.RegionCell(4, 1, "B.", false, false)),
                new RegionClassifier.HeaderContext(List.of(), List.of()));

        assertThat(alpha.reasons().getFirst().params()).containsEntry("serial_pattern", 2L);
        assertThat(mixed.reasons().getFirst().params()).containsEntry("serial_pattern", 4L);
    }

    @Test
    void selectsCostHeadAndReasonsDeterministicallyRegardlessOfInputOrder() {
        RegionClassification result = classifier.classify(bounds(), List.of(
                new RegionClassifier.RegionCell(4, 1, "Plant & Machinery", false, false),
                new RegionClassifier.RegionCell(2, 1, "Civil Works", false, false)),
                new RegionClassifier.HeaderContext(List.of(), List.of()));

        assertThat(result.costHeadCode()).isEqualTo("CIVIL");
        assertThat(result.reasons()).isSortedAccordingTo(
                java.util.Comparator.comparing(reason -> reason.code().name()));
    }

    private static RegionClassifier.RegionBounds bounds() {
        return new RegionClassifier.RegionBounds(1, 5, 1, 3);
    }
}
