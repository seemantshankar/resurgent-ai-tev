package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TranscribedLeafTest {

    @Test
    void flagsSupplierRateQuantitySpecAndBrokenRef() {
        assertThat(TranscribedLeaf.isTranscribed(
                "Elevator (Supplier - Kone Elevator India Pvt. Ltd.)")).isTrue();
        assertThat(TranscribedLeaf.isTranscribed("CGST@14%")).isTrue();
        assertThat(TranscribedLeaf.isTranscribed("97650 Sqft@ 600 Rs/ Sqft")).isTrue();
        assertThat(TranscribedLeaf.isTranscribed("#REF!")).isTrue();
        assertThat(TranscribedLeaf.isTranscribed(
                "Less : AC as per Quotation included Below")).isTrue();
    }

    @Test
    void allowsShortCategoryNames() {
        assertThat(TranscribedLeaf.isTranscribed("Closing Stock")).isFalse();
        assertThat(TranscribedLeaf.isTranscribed("CCTV System")).isFalse();
        assertThat(TranscribedLeaf.isTranscribed("Structure")).isFalse();
    }
}
