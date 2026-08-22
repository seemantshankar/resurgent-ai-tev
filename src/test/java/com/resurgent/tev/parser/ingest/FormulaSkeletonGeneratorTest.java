package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link FormulaSkeletonGenerator}: canonical region-free formula skeleton emission.
 */
class FormulaSkeletonGeneratorTest {

    @Test
    void replacesLocalCellReferencesWithAbs() {
        FormulaToken t1 = new FormulaToken(0, "D18", "local_cell", null, "D18", false, false, 0, 0, false, false);
        FormulaToken t2 = new FormulaToken(1, "D19", "local_cell", null, "D19", false, false, 0, 0, false, false);

        String skeleton = FormulaSkeletonGenerator.generate("SUM(D18,D19)", List.of(t1, t2));
        assertThat(skeleton).isEqualTo("SUM($ABS$,$ABS$)");
    }

    @Test
    void preservesSheetNameAndReplacesCoordinateWithAbs() {
        FormulaToken t1 = new FormulaToken(0, "'P  L '!F35", "cross_sheet_cell", "P  L ", "F35", false, false, 0, 0, false, false);

        String skeleton = FormulaSkeletonGenerator.generate("'P  L '!F35+10", List.of(t1));
        assertThat(skeleton).isEqualTo("'P  L '!$ABS$+10");
    }

    @Test
    void replacesRangeReferencesWithAbs() {
        FormulaToken t1 = new FormulaToken(0, "A1:B10", "local_range", null, "A1:B10", false, false, 0, 0, false, false);

        String skeleton = FormulaSkeletonGenerator.generate("AVERAGE(A1:B10)", List.of(t1));
        assertThat(skeleton).isEqualTo("AVERAGE($ABS$)");
    }
}
