package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LayerATriageNormalizerTest {

    private static String[] n(String triage, String relevance) {
        return LayerATriageNormalizer.normalize(triage, relevance);
    }

    /** A helper is a region that supports the model: it is kept, not thrown away as scratch. */
    @Test
    void aHelperIsAMainRegionThatSupportsTheModel() {
        assertThat(n("HELPER", "SECONDARY")).containsExactly("main", "supporting");
        assertThat(n("helper", "primary")).containsExactly("main", "supporting");
        assertThat(n("helper", null)).containsExactly("main", "supporting");
    }

    @Test
    void mainKeepsItsRelevanceAndTheOldSpellingsStillWork() {
        assertThat(n("MAIN", "PRIMARY")).containsExactly("main", "primary");
        assertThat(n("main", "tertiary")).containsExactly("main", "supporting");
        assertThat(n("main", "supporting")).containsExactly("main", "supporting");
    }

    @Test
    void scratchAndOrphanAreNoiseWhateverRelevanceWasSaid() {
        assertThat(n("scratch", "primary")).containsExactly("scratch", "noise");
        assertThat(n("SCRATCH", null)).containsExactly("scratch", "noise");
        assertThat(n("orphan", "supporting")).containsExactly("orphan", "noise");
    }

    @Test
    void aMainRegionIsNeverNoise() {
        assertThat(n("main", "noise")).containsExactly("main", "supporting");
    }

    /** When the model does not say, keep the content: a wrongly kept region costs a model call, a wrongly dropped one is lost. */
    @Test
    void anUnknownOrMissingTriageKeepsTheRegion() {
        assertThat(n(null, null)).containsExactly("main", "supporting");
        assertThat(n("", "primary")).containsExactly("main", "primary");
        assertThat(n("banana", "noise")).containsExactly("main", "supporting");
        assertThat(n(null, "noise")).containsExactly("main", "supporting");
    }
}
