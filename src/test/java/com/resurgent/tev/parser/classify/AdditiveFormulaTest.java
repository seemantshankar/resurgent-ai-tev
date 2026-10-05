package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdditiveFormulaTest {

    @Test
    void sumsAndPlusMinusChainsOfCellsAreAdditive() {
        assertThat(ReadingArithmetic.additive("SUM(H264:H266)")).isTrue();
        assertThat(ReadingArithmetic.additive("=H267+H274")).isTrue();
        assertThat(ReadingArithmetic.additive("P7+Q7-R7+S7-T7")).isTrue();
        assertThat(ReadingArithmetic.additive("SUM(B2:B5)-B7")).isTrue();
        assertThat(ReadingArithmetic.additive("AVERAGE(B2:B5)+MAX(C1:C3)")).isTrue();
        assertThat(ReadingArithmetic.additive("'Cash Flow'!P91+Q91")).isTrue();
        assertThat(ReadingArithmetic.additive("SUM($B$2:$B$5,D9)")).isTrue();
        assertThat(ReadingArithmetic.additive("(B2+B3)-(B4)")).isTrue();
    }

    @Test
    void productsQuotientsComparisonsAndOtherFunctionsAreNot() {
        assertThat(ReadingArithmetic.additive("B1*B2")).isFalse();
        assertThat(ReadingArithmetic.additive("B1/B2")).isFalse();
        assertThat(ReadingArithmetic.additive("B1+B2*2")).isFalse();
        assertThat(ReadingArithmetic.additive("IF(B1>0,B2,B3)")).isFalse();
        assertThat(ReadingArithmetic.additive("ROUND(B1+B2,2)")).isFalse();
        assertThat(ReadingArithmetic.additive("SUMPRODUCT(B1:B3,C1:C3)")).isFalse();
        assertThat(ReadingArithmetic.additive("B1&B2")).isFalse();
        assertThat(ReadingArithmetic.additive("LOG10(B1)")).isFalse();
        assertThat(ReadingArithmetic.additive("B1^2")).isFalse();
    }

    @Test
    void aFormulaThatReadsNoCellOrIsEmptyIsNot() {
        assertThat(ReadingArithmetic.additive("5+3")).isFalse();
        assertThat(ReadingArithmetic.additive("")).isFalse();
        assertThat(ReadingArithmetic.additive(null)).isFalse();
    }
}
