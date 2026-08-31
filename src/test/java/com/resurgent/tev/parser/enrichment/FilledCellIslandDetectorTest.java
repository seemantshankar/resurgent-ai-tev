package com.resurgent.tev.parser.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FilledCellIslandDetectorTest {

    @Test
    void detectsEightConnectedComponents() {
        Set<String> filled = Set.of("A1", "B1", "A2", "B2", "D4");
        var islands = new FilledCellIslandDetector().detect(filled);

        assertThat(islands).hasSize(2);
        assertThat(islands.get(0).bounds()).isEqualTo("A1:B2");
        assertThat(islands.get(0).cellCount()).isEqualTo(4);
        assertThat(islands.get(1).bounds()).isEqualTo("D4");
        assertThat(islands.get(1).cellCount()).isEqualTo(1);
    }

    @Test
    void diagonalTouchCountsAsConnected() {
        Set<String> filled = Set.of("A1", "B2");
        var islands = new FilledCellIslandDetector().detect(filled);

        assertThat(islands).hasSize(1);
        assertThat(islands.getFirst().bounds()).isEqualTo("A1:B2");
    }

    @Test
    void mergesLShapedFixedAssetsStyleHeaderAndBody() {
        // Year headers D1:F1, blank row 2, row labels in A + amounts in D:F on rows 3/5
        Set<String> filled = new LinkedHashSet<>();
        filled.addAll(Set.of("D1", "E1", "F1"));
        filled.addAll(Set.of("A3", "D3", "E3", "F3"));
        filled.addAll(Set.of("A5", "D5", "E5", "F5"));

        var islands = new FilledCellIslandDetector().detect(filled);

        assertThat(islands).hasSize(1);
        assertThat(islands.getFirst().bounds()).isEqualTo("A1:F5");
        assertThat(islands.getFirst().cellCount()).isEqualTo(11);
    }

    @Test
    void mergesRateTableHeaderRowWithBodyBelow() {
        Set<String> filled = new LinkedHashSet<>();
        filled.addAll(Set.of("D1", "E1", "F1", "G1"));
        filled.addAll(Set.of("A2", "D2", "E2", "F2", "G2", "H2"));
        filled.addAll(Set.of("A3", "D3", "E3", "F3", "G3", "H3"));

        var islands = new FilledCellIslandDetector().detect(filled);

        assertThat(islands).hasSize(1);
        assertThat(islands.getFirst().bounds()).isEqualTo("A1:H3");
    }

    @Test
    void doesNotMergeSideScratchSeparatedByBlankColumn() {
        // Wide main block A1:M2 and narrow side check O1:P2 (blank N) stay separate
        Set<String> filled = new LinkedHashSet<>();
        for (int row = 1; row <= 2; row++) {
            for (char col = 'A'; col <= 'M'; col++) {
                if (col == 'B' || col == 'C' || col == 'N') {
                    continue;
                }
                filled.add("" + col + row);
            }
            filled.add("O" + row);
            filled.add("P" + row);
        }

        var islands = new FilledCellIslandDetector().detect(filled);

        assertThat(islands.stream().map(IslandHint::bounds).toList())
                .contains("A1:M2", "O1:P2");
    }
}
