package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code --pages} parsing and the canonical {@code target_pages} rendering. */
class PageSelectionTest {

    @Test
    void parsesSinglesAndRangesSortedAndDeduped() {
        PageSelection selection = PageSelection.parse("3,1-2,5");
        assertThat(selection.indices()).containsExactly(1, 2, 3, 5);
        assertThat(selection.wire()).isEqualTo("1-3,5");
    }

    @Test
    void compressedRunRoundTrips() {
        assertThat(PageSelection.parse("1-5,8").wire()).isEqualTo("1-5,8");
        assertThat(PageSelection.parse("1,2,3").wire()).isEqualTo("1-3");
    }

    @Test
    void containsIsBinarySearch() {
        PageSelection selection = PageSelection.parse("1-5,8");
        assertThat(selection.contains(3)).isTrue();
        assertThat(selection.contains(8)).isTrue();
        assertThat(selection.contains(6)).isFalse();
    }

    @Test
    void optionalBlankIsNullMeaningAllPages() {
        assertThat(PageSelection.parseOptional(null)).isNull();
        assertThat(PageSelection.parseOptional("   ")).isNull();
    }

    @Test
    void rejectsMalformedSpecs() {
        assertThatThrownBy(() -> PageSelection.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageSelection.parse("a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a number");
        assertThatThrownBy(() -> PageSelection.parse("0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-based");
        assertThatThrownBy(() -> PageSelection.parse("5-3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reversed");
        assertThatThrownBy(() -> PageSelection.parse("1,,3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty token");
    }

    @Test
    void wireOfEmptyOrNullIsNull() {
        assertThat(PageSelection.wire(null)).isNull();
        assertThat(PageSelection.wire(List.of())).isNull();
        assertThat(PageSelection.wire(List.of(2, 1, 3))).isEqualTo("1-3");
    }
}
