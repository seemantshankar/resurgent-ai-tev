package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link FormulaTokenizer}: POI primary path & salvage fallback.
 */
class FormulaTokenizerTest {

    @Test
    void tokenizeLocalRefCapturesAbsFlagsAndOffsets() {
        FormulaTokenizerResult result = FormulaTokenizer.tokenize("$D$18", 22, 4, Map.of());
        assertThat(result.formulaState()).isEqualTo("ok");
        assertThat(result.tokens()).hasSize(1);

        FormulaToken token = result.tokens().get(0);
        assertThat(token.refKind()).isEqualTo("local_cell");
        assertThat(token.targetRange()).isEqualTo("D18");
        assertThat(token.absRow()).isTrue();
        assertThat(token.absCol()).isTrue();
        assertThat(token.rowOffset()).isEqualTo(-4); // 18 - 22
        assertThat(token.colOffset()).isEqualTo(0);  // 4 - 4
    }

    @Test
    void tokenizeRelativeRefCapturesOffsets() {
        FormulaTokenizerResult result = FormulaTokenizer.tokenize("D10", 22, 4, Map.of());
        assertThat(result.formulaState()).isEqualTo("ok");
        assertThat(result.tokens()).hasSize(1);

        FormulaToken token = result.tokens().get(0);
        assertThat(token.refKind()).isEqualTo("local_cell");
        assertThat(token.targetRange()).isEqualTo("D10");
        assertThat(token.absRow()).isFalse();
        assertThat(token.absCol()).isFalse();
        assertThat(token.rowOffset()).isEqualTo(-12); // 10 - 22
        assertThat(token.colOffset()).isEqualTo(0);
    }

    @Test
    void tokenizeQuotedSheetNamePreservesTrailingSpacesVerbatim() {
        FormulaTokenizerResult result = FormulaTokenizer.tokenize("'P  L '!D23", 23, 4, Map.of());
        assertThat(result.formulaState()).isEqualTo("ok");
        assertThat(result.tokens()).hasSize(1);

        FormulaToken token = result.tokens().get(0);
        assertThat(token.refKind()).isEqualTo("cross_sheet_cell");
        assertThat(token.targetSheetName()).isEqualTo("P  L ");
        assertThat(token.targetRange()).isEqualTo("D23");
    }

    @Test
    void fallbackSalvageOnParseExceptionReturnsParseErrorWithTokens() {
        // Formula with invalid syntax that POI parser rejects
        FormulaTokenizerResult result = FormulaTokenizer.tokenize("INVALID_SYNTAX(,,)'Sheet1'!A1", 1, 1, Map.of());
        assertThat(result.formulaState()).isEqualTo("parse_error");
        assertThat(result.tokens()).isNotEmpty();
    }
}
