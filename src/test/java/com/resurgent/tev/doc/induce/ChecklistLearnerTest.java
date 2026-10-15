package com.resurgent.tev.doc.induce;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.checklist.ChecklistDefinition;
import com.resurgent.tev.doc.checklist.ChecklistItemDef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * M3-15 (#148) engine tests: the deterministic checklist learner adds document
 * types seen in most mandates of an industry, downgrades items never provided
 * across enough mandates to optional with the reason, and asks the project-type
 * judge exactly once for spine candidates observed across enough industries —
 * promoting only what the judge calls project-type independent.
 */
class ChecklistLearnerTest {

    @Test
    void addsATypeSeenInMostMandatesAndDowngradesTheMissing() {
        ChecklistDefinition draft = solarDraft();
        List<ChecklistLearner.MandateObservation> mandates = List.of(
                solar(1, "agreement.ppa", "report.pvsyst", "approval.evacuation"),
                solar(2, "agreement.ppa", "approval.evacuation"),
                solar(3, "agreement.ppa", "approval.evacuation"));

        ChecklistLearner.Outcome outcome = ChecklistLearner.learn(
                inputs(mandates, draft), new FixtureProjectTypeIndependenceJudge(),
                ChecklistLearner.Config.defaults());

        assertThat(outcome.updates()).singleElement().satisfies(update -> {
            assertThat(update.kind()).isEqualTo("industry");
            assertThat(update.code()).isEqualTo("in.solar_power");
            assertThat(update.previousVersion()).isEqualTo("1.0.0");
            assertThat(update.newVersion()).isEqualTo("1.1.0");
            assertThat(update.changes()).extracting(ChecklistLearner.ItemChange::kind)
                    .containsExactlyInAnyOrder("added", "made_optional");
            assertThat(update.changes())
                    .filteredOn(change -> change.kind().equals("added"))
                    .singleElement().satisfies(change -> {
                        assertThat(change.itemKey()).isEqualTo("approval_evacuation");
                        assertThat(change.documentType()).isEqualTo("approval.evacuation");
                        assertThat(change.sourceMandateIds()).containsExactly(1L, 2L, 3L);
                        assertThat(change.reason()).contains("3 of the 3", "1, 2, 3");
                    });
            assertThat(update.changes())
                    .filteredOn(change -> change.kind().equals("made_optional"))
                    .singleElement().satisfies(change -> {
                        assertThat(change.itemKey()).isEqualTo("pvsyst_report");
                        assertThat(change.sourceMandateIds()).containsExactly(2L, 3L);
                        assertThat(change.note()).startsWith("optional:").contains("2 of the 3");
                    });

            ChecklistDefinition learned = update.definition();
            assertThat(learned.version()).isEqualTo("1.1.0");
            assertThat(learned.items()).extracting(ChecklistItemDef::key)
                    .containsExactly("solar_ppa", "pvsyst_report", "approval_evacuation");
            assertThat(learned.items()).filteredOn(i -> i.key().equals("pvsyst_report"))
                    .singleElement().satisfies(item -> {
                        assertThat(item.mandatory()).isFalse();
                        assertThat(item.note()).startsWith("optional:");
                    });
            assertThat(learned.items()).filteredOn(i -> i.key().equals("solar_ppa"))
                    .singleElement().satisfies(item -> assertThat(item.mandatory()).isTrue());
            assertThat(learned.items()).filteredOn(i -> i.key().equals("approval_evacuation"))
                    .singleElement().satisfies(item -> {
                        assertThat(item.origin()).isEqualTo("observed");
                        assertThat(item.basisKind()).isEqualTo("observed");
                        assertThat(item.basisText()).contains("1, 2, 3");
                        assertThat(item.documentType()).isEqualTo("approval.evacuation");
                        assertThat(item.mandatory()).isTrue();
                    });
        });
    }

    @Test
    void doesNotAddATypeSeenInOnlyAMinority() {
        List<ChecklistLearner.MandateObservation> mandates = List.of(
                solar(1, "agreement.ppa", "report.pvsyst", "rare.type"),
                solar(2, "agreement.ppa", "report.pvsyst"),
                solar(3, "agreement.ppa", "report.pvsyst"));

        ChecklistLearner.Outcome outcome = ChecklistLearner.learn(
                inputs(mandates, solarDraft()), new FixtureProjectTypeIndependenceJudge(),
                ChecklistLearner.Config.defaults());

        // pvsyst_report is provided by all 3 -> mandatory stays; rare.type seen once -> not added.
        assertThat(outcome.updates()).isEmpty();
    }

    @Test
    void configControlTheDownAndAddThresholds() {
        List<ChecklistLearner.MandateObservation> mandates = List.of(
                solar(1, "agreement.ppa", "report.pvsyst", "rare.type"),
                solar(2, "report.pvsyst"),
                solar(3, "report.pvsyst"),
                solar(4, "report.pvsyst"));

        // minMissing 4: solar_ppa missing 3 mandates stays mandatory; rare.type is a
        // minority even with minObserved 1 -- still nothing to learn.
        ChecklistLearner.Outcome strict = ChecklistLearner.learn(
                inputs(mandates, solarDraft()), new FixtureProjectTypeIndependenceJudge(),
                new ChecklistLearner.Config(1, 4, 2));
        assertThat(strict.updates()).isEmpty();

        // minMissing 1: solar_ppa (not provided in 3 mandates) becomes optional.
        ChecklistLearner.Outcome relaxedMissing = ChecklistLearner.learn(
                inputs(mandates, solarDraft()), new FixtureProjectTypeIndependenceJudge(),
                new ChecklistLearner.Config(1, 1, 2));
        assertThat(relaxedMissing.updates()).singleElement().satisfies(update ->
                assertThat(update.changes()).extracting(ChecklistLearner.ItemChange::kind)
                        .containsExactly("made_optional"));
    }

    @Test
    void spinePromotionNeedsJudgedIndependenceAcrossTwoIndustries() {
        RecordingJudge judge = new RecordingJudge(Map.of(
                "approval.evacuation", true, "report.pvsyst", false));
        List<ChecklistLearner.MandateObservation> mandates = List.of(
                solar(1, "agreement.ppa", "report.pvsyst", "approval.evacuation"),
                solar(2, "agreement.ppa", "report.pvsyst", "approval.evacuation"),
                new ChecklistLearner.MandateObservation(3, "IN", List.of("hotel"),
                        List.of("licence.hotel", "approval.evacuation")));

        ChecklistLearner.Outcome outcome = ChecklistLearner.learn(
                inputs(mandates, solarDraft(), hotelDraft(), spine()),
                judge, ChecklistLearner.Config.defaults());

        // One judge call, one candidate: only the type seen in two industries.
        assertThat(judge.calls).isEqualTo(1);
        assertThat(judge.lastRequest.candidates())
                .extracting(ProjectTypeIndependenceJudge.Candidate::documentType)
                .containsExactly("approval.evacuation");

        ChecklistLearner.DefinitionUpdate spine = outcome.updates().stream()
                .filter(update -> update.kind().equals("spine"))
                .findFirst().orElseThrow();
        assertThat(spine.previousVersion()).isEqualTo("1.0.0");
        assertThat(spine.newVersion()).isEqualTo("1.1.0");
        assertThat(spine.changes()).singleElement().satisfies(change -> {
            assertThat(change.kind()).isEqualTo("promoted");
            assertThat(change.itemKey()).isEqualTo("approval_evacuation");
            assertThat(change.sourceMandateIds()).containsExactly(1L, 2L, 3L);
        });
        assertThat(spine.definition().items())
                .filteredOn(item -> item.key().equals("approval_evacuation"))
                .singleElement().satisfies(item -> {
                    assertThat(item.origin()).isEqualTo("promoted");
                    assertThat(item.basisKind()).isEqualTo("observed");
                    assertThat(item.basisText()).contains("solar_power", "hotel");
                    assertThat(item.note()).startsWith("project-type independent:");
                });
        assertThat(outcome.judgements()).hasSize(1);
    }

    @Test
    void offlineFixtureJudgeNeverPromotesToTheSpine() {
        List<ChecklistLearner.MandateObservation> mandates = List.of(
                solar(1, "agreement.ppa", "report.pvsyst", "approval.evacuation"),
                solar(2, "agreement.ppa", "report.pvsyst", "approval.evacuation"),
                new ChecklistLearner.MandateObservation(3, "IN", List.of("hotel"),
                        List.of("licence.hotel", "approval.evacuation")));

        ChecklistLearner.Outcome outcome = ChecklistLearner.learn(
                inputs(mandates, solarDraft(), hotelDraft(), spine()),
                new FixtureProjectTypeIndependenceJudge(),
                ChecklistLearner.Config.defaults());

        assertThat(outcome.updates()).noneMatch(update -> update.kind().equals("spine"));
        assertThat(outcome.judgements()).isNotEmpty()
                .allSatisfy(judgement -> {
                    assertThat(judgement.independent()).isFalse();
                    assertThat(judgement.reason())
                            .isEqualTo(FixtureProjectTypeIndependenceJudge.OFFLINE_REASON);
                });
    }

    @Test
    void configCanRaiseTheIndustryBarSoTheJudgeIsNotEvenAsked() {
        RecordingJudge judge = new RecordingJudge(Map.of("approval.evacuation", true));
        List<ChecklistLearner.MandateObservation> mandates = List.of(
                solar(1, "agreement.ppa", "report.pvsyst", "approval.evacuation"),
                solar(2, "agreement.ppa", "report.pvsyst", "approval.evacuation"),
                new ChecklistLearner.MandateObservation(3, "IN", List.of("hotel"),
                        List.of("licence.hotel", "approval.evacuation")));

        ChecklistLearner.Outcome outcome = ChecklistLearner.learn(
                inputs(mandates, solarDraft(), hotelDraft(), spine()),
                judge, new ChecklistLearner.Config(2, 2, 3));

        assertThat(judge.calls).isZero();
        assertThat(outcome.updates()).noneMatch(update -> update.kind().equals("spine"));
    }

    @Test
    void countryMismatchTeachesNothing() {
        List<ChecklistLearner.MandateObservation> mandates = List.of(
                new ChecklistLearner.MandateObservation(1, "KE", List.of("solar power"),
                        List.of("agreement.ppa", "approval.evacuation")),
                new ChecklistLearner.MandateObservation(2, "KE", List.of("solar power"),
                        List.of("agreement.ppa", "approval.evacuation")));

        ChecklistLearner.Outcome outcome = ChecklistLearner.learn(
                inputs(mandates, solarDraft(), spine()),
                new FixtureProjectTypeIndependenceJudge(), ChecklistLearner.Config.defaults());

        assertThat(outcome.updates()).isEmpty();
        assertThat(outcome.judgements()).isEmpty();
    }

    private static ChecklistLearner.Inputs inputs(
            List<ChecklistLearner.MandateObservation> mandates,
            ChecklistDefinition... catalog) {
        return new ChecklistLearner.Inputs(mandates, List.of(catalog));
    }

    private static ChecklistLearner.MandateObservation solar(long mandateId,
            String... documentTypes) {
        return new ChecklistLearner.MandateObservation(mandateId, "IN",
                List.of("solar power"), List.of(documentTypes));
    }

    private static ChecklistDefinition solarDraft() {
        return new ChecklistDefinition("in.solar_power", "1.0.0", "industry", "IN",
                List.of("solar power"), List.of(
                        document("solar_ppa", "agreement.ppa"),
                        document("pvsyst_report", "report.pvsyst")), null);
    }

    private static ChecklistDefinition hotelDraft() {
        return new ChecklistDefinition("in.hotel", "1.0.0", "industry", "IN",
                List.of("hotel"), List.of(document("hotel_licence", "licence.hotel")), null);
    }

    /** The seed spine, small on purpose: the learner only appends to it. */
    private static ChecklistDefinition spine() {
        return new ChecklistDefinition("in.spine", "1.0.0", "spine", "IN", List.of(),
                List.of(document("pan_card", "registration.pan")), null);
    }

    private static ChecklistItemDef document(String key, String documentType) {
        return new ChecklistItemDef(key, "Test " + key, "document", "mandate", null,
                documentType, null, null, true, "practice", "bank practice", "drafted",
                "Please share the {item}.", null);
    }

    /** A scripted judge that records its calls and answers from a fixed map. */
    private static final class RecordingJudge implements ProjectTypeIndependenceJudge {

        private final Map<String, Boolean> independent;
        private int calls;
        private IndependenceRequest lastRequest;

        private RecordingJudge(Map<String, Boolean> independent) {
            this.independent = independent;
        }

        @Override
        public String id() {
            return "test-project-type-independence";
        }

        @Override
        public IndependenceResult judge(IndependenceRequest request) {
            calls++;
            lastRequest = request;
            return new IndependenceResult(request.candidates().stream()
                    .map(candidate -> new Judgement(candidate.documentType(),
                            independent.getOrDefault(candidate.documentType(), false),
                            "test judgement for " + candidate.documentType()))
                    .toList());
        }
    }
}
