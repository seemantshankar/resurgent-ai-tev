package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ConstantFormulaEvaluator}: pure evaluation of constant formulas.
 */
class ConstantFormulaEvaluatorTest {

    @Test
    void evaluatesBasicArithmetic() {
        ConstantFormulaEvaluator.EvalResult res = ConstantFormulaEvaluator.evaluate("1+2*3", List.of());
        assertThat(res).isNotNull();
        assertThat(res.numericValue()).isEqualByComparingTo(new BigDecimal("7"));
        assertThat(res.isError()).isFalse();
    }

    @Test
    void evaluatesUnaryMinusAndParentheses() {
        ConstantFormulaEvaluator.EvalResult res = ConstantFormulaEvaluator.evaluate("-(5+3)", List.of());
        assertThat(res).isNotNull();
        assertThat(res.numericValue()).isEqualByComparingTo(new BigDecimal("-8"));
    }

    @Test
    void evaluatesDivisionByZeroToDiv0Error() {
        ConstantFormulaEvaluator.EvalResult res = ConstantFormulaEvaluator.evaluate("10/0", List.of());
        assertThat(res).isNotNull();
        assertThat(res.isError()).isTrue();
        assertThat(res.errorType()).isEqualTo("#DIV/0!");
    }

    @Test
    void returnsNullWhenFormulaContainsCellReferences() {
        FormulaToken token = new FormulaToken(0, "A1", "local_cell", null, "A1", false, false, 0, 0, false, false);
        ConstantFormulaEvaluator.EvalResult res = ConstantFormulaEvaluator.evaluate("A1+10", List.of(token));
        assertThat(res).isNull();
    }

    @Test
    void evaluatesExplicitErrorLiteral() {
        ConstantFormulaEvaluator.EvalResult res = ConstantFormulaEvaluator.evaluate("#VALUE!", List.of());
        assertThat(res).isNotNull();
        assertThat(res.isError()).isTrue();
        assertThat(res.errorType()).isEqualTo("#VALUE!");
    }
}
