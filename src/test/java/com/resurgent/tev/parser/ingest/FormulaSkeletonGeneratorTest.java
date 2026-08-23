package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link FormulaSkeletonGenerator}: canonical, position-insensitive formula
 * skeleton emission per §7.4.1.
 *
 * <p>The cases above {@link #skeletonOf} hand-build their tokens to pin one substitution rule
 * each. Those below drive {@link FormulaTokenizer} into the generator, because the two agreeing
 * on a token's raw span is a contract neither can check alone.
 */
class FormulaSkeletonGeneratorTest {

    @Test
    void relativeLocalCellReferencesBecomeR() {
        FormulaToken t1 = new FormulaToken(0, "D18", "local_cell", null, "D18", false, false, 0, 0, false, false);
        FormulaToken t2 = new FormulaToken(1, "D19", "local_cell", null, "D19", false, false, 0, 0, false, false);

        String skeleton = FormulaSkeletonGenerator.generate("=SUM(D18,D19)", List.of(t1, t2));
        assertThat(skeleton).isEqualTo("=SUM(R,R)");
    }

    @Test
    void bothAbsoluteReferenceBecomesAbs() {
        FormulaToken t1 = new FormulaToken(0, "$D$18", "local_cell", null, "D18", true, true, 0, 0, false, false);

        String skeleton = FormulaSkeletonGenerator.generate("=$D$18+1", List.of(t1));
        assertThat(skeleton).isEqualTo("=$ABS$+1");
    }

    @Test
    void crossSheetReferenceReplacesEntireSpanIncludingSheetName() {
        FormulaToken t1 = new FormulaToken(0, "'P  L '!F35", "cross_sheet_cell", "P  L ", "F35", false, false, 0, 0, false, false);

        String skeleton = FormulaSkeletonGenerator.generate("='P  L '!F35+10", List.of(t1));
        assertThat(skeleton).isEqualTo("=R+10");
    }

    @Test
    void verticalRangeBecomesRangeVertical() {
        FormulaToken t1 = new FormulaToken(0, "D22:D28", "local_range", null, "D22:D28", false, false, 0, 0, false, false);

        String skeleton = FormulaSkeletonGenerator.generate("=SUM(D22:D28)", List.of(t1));
        assertThat(skeleton).isEqualTo("=SUM(RANGE_VERTICAL)");
    }

    @Test
    void horizontalRangeBecomesRangeHorizontal() {
        FormulaToken t1 = new FormulaToken(0, "D22:M22", "local_range", null, "D22:M22", false, false, 0, 0, false, false);

        String skeleton = FormulaSkeletonGenerator.generate("=SUM(D22:M22)", List.of(t1));
        assertThat(skeleton).isEqualTo("=SUM(RANGE_HORIZONTAL)");
    }

    @Test
    void sameShapeFormulasAcrossSheetsProduceIdenticalSkeletons() {
        FormulaToken vertical = new FormulaToken(0, "D22:D28", "local_range", null, "D22:D28", false, false, 0, 0, false, false);
        FormulaToken verticalOther = new FormulaToken(0, "H50:H56", "local_range", null, "H50:H56", false, false, 0, 0, false, false);

        String s1 = FormulaSkeletonGenerator.generate("=SUM(D22:D28)", List.of(vertical));
        String s2 = FormulaSkeletonGenerator.generate("=SUM(H50:H56)", List.of(verticalOther));
        assertThat(s1).isEqualTo(s2);
    }

    @Test
    void externalReferenceBecomesExt() {
        FormulaToken t1 = new FormulaToken(0, "[15]P  L !F35", "external", "P  L ", "F35", false, false, 0, 0, false, false);

        String skeleton = FormulaSkeletonGenerator.generate("=[15]P  L !F35", List.of(t1));
        assertThat(skeleton).isEqualTo("=EXT");
    }

    @Test
    void definedNameReferenceBecomesName() {
        FormulaToken t1 = new FormulaToken(0, "TaxRate", "defined_name", null, "TaxRate", false, false, null, null, false, false);

        String skeleton = FormulaSkeletonGenerator.generate("=TaxRate*10", List.of(t1));
        assertThat(skeleton).isEqualTo("=NAME*10");
    }

    @Test
    void constantFormulaBecomesConst() {
        String skeleton = FormulaSkeletonGenerator.generate("=200/2", List.of());
        assertThat(skeleton).isEqualTo("=CONST");
    }

    @Test
    void quotedStringLiteralIsNotRewrittenByCoincidentalTokenMatch() {
        FormulaToken t1 = new FormulaToken(0, "D18", "local_cell", null, "D18", false, false, 0, 0, false, false);

        String skeleton = FormulaSkeletonGenerator.generate("=D18&\"D18 literal\"", List.of(t1));
        assertThat(skeleton).isEqualTo("=R&\"D18 literal\"");
    }

    /**
     * The tests above hand-build their tokens, so they hold whatever the tokenizer does.
     * These drive the real tokenizer into the generator, which is the pairing that actually
     * runs in the pipeline: the generator abstracts a reference by replacing its raw span, so
     * a raw token the tokenizer renders differently from the source leaves the reference
     * un-abstracted and the skeleton position-sensitive.
     */
    private static String skeletonOf(String formula) {
        FormulaTokenizerResult tokenized = FormulaTokenizer.tokenize(formula, 19, 9, Map.of());
        return FormulaSkeletonGenerator.generate(formula, tokenized.tokens());
    }

    @Test
    void externalReferenceFromTheTokenizerBecomesExt() {
        assertThat(skeletonOf("[15]Manpower!F35")).isEqualTo("EXT");
    }

    @Test
    void unquotedCrossSheetReferenceFromTheTokenizerIsFullyAbstracted() {
        assertThat(skeletonOf("E23/SALESPROJECTION!E81*100")).isEqualTo("R/R*100");
    }

    @Test
    void quotedCrossSheetReferenceFromTheTokenizerIsFullyAbstracted() {
        assertThat(skeletonOf("'P  L '!D29-D10")).isEqualTo("R-R");
    }

    @Test
    void wholeColumnReferenceFromTheTokenizerBecomesARange() {
        assertThat(skeletonOf("SUM(A:A)")).isEqualTo("SUM(RANGE_VERTICAL)");
    }

    @Test
    void absoluteRangeFromTheTokenizerBecomesARange() {
        assertThat(skeletonOf("SUM($D$22:$D$28)")).isEqualTo("SUM(RANGE_VERTICAL)");
    }

    @Test
    void skeletonsFromTheTokenizerNeverRetainASheetName() {
        for (String formula : List.of(
                "[15]Manpower!F35",
                "SALESPROJECTION!E81",
                "'P  L '!D29-D10",
                "SUM(SALESPROJECTION!E81:E90)",
                "IRR_CASE_II!E33",
                "SUM(SALESPROJECTION!A:A)")) {
            assertThat(skeletonOf(formula))
                    .as("skeleton of '%s' must not carry a sheet reference", formula)
                    .doesNotContain("!");
        }
    }
}
