package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
    void rawTokenKeepsTheUnquotedSheetPrefixTheFormulaActuallyUses() {
        // Excel only quotes a sheet name that needs it. SALESPROJECTION does not,
        // and §10.9 keeps the raw formula verbatim -- so the token's raw span must
        // be what the file says, not a re-rendering that adds quotes.
        FormulaTokenizerResult result = FormulaTokenizer.tokenize("SALESPROJECTION!E81", 23, 5, Map.of());
        assertThat(result.formulaState()).isEqualTo("ok");
        assertThat(result.tokens()).hasSize(1);

        FormulaToken token = result.tokens().get(0);
        assertThat(token.targetSheetName()).isEqualTo("SALESPROJECTION");
        assertThat(token.rawToken()).isEqualTo("SALESPROJECTION!E81");
    }

    @Test
    void rawTokenKeepsTheQuotedSheetPrefixWhenTheFormulaQuotesIt() {
        FormulaTokenizerResult result = FormulaTokenizer.tokenize("'P  L '!D23", 23, 4, Map.of());
        assertThat(result.tokens()).hasSize(1);
        assertThat(result.tokens().get(0).rawToken()).isEqualTo("'P  L '!D23");
    }

    @Test
    void rawTokenKeepsTheUnquotedPrefixOnAnExternalReference() {
        // §13: raw_token='[15]Manpower!F35' for CAPITAL COST!I19.
        FormulaTokenizerResult result = FormulaTokenizer.tokenize("[15]Manpower!F35", 19, 9, Map.of());
        assertThat(result.tokens()).hasSize(1);

        FormulaToken token = result.tokens().get(0);
        assertThat(token.refKind()).isEqualTo("external");
        assertThat(token.rawToken()).isEqualTo("[15]Manpower!F35");
    }

    @Test
    void rawTokenKeepsTheUnquotedSheetPrefixOnARange() {
        FormulaTokenizerResult result = FormulaTokenizer.tokenize("SUM(SALESPROJECTION!E81:E90)", 23, 5, Map.of());
        assertThat(result.tokens()).hasSize(1);
        assertThat(result.tokens().get(0).rawToken()).isEqualTo("SALESPROJECTION!E81:E90");
    }

    @Test
    void everyRawTokenAppearsVerbatimInTheFormulaItCameFrom() {
        // The invariant the skeleton generator depends on: it abstracts a reference by
        // replacing rawToken's span in the formula text, so a rawToken that is not
        // literally present silently leaves the reference un-abstracted.
        for (String formula : List.of(
                "SALESPROJECTION!E81",
                "'P  L '!D23",
                "[15]Manpower!F35",
                "E23/SALESPROJECTION!E81*100",
                "SUM(SALESPROJECTION!E81:E90)",
                "SUM(SALESPROJECTION!$E$81:$E$90)",
                "SALESPROJECTION!E81+SALESPROJECTION!E82",
                "SUM($D$22:$D$28)",
                "SUM(A:A)",
                "SUM(SALESPROJECTION!A:A)",
                "IRR_CASE_II!E33",
                "'P  L '!D29-D10")) {
            FormulaTokenizerResult result = FormulaTokenizer.tokenize(formula, 23, 5, Map.of());
            for (FormulaToken token : result.tokens()) {
                assertThat(formula)
                        .as("rawToken '%s' must appear verbatim in formula '%s'", token.rawToken(), formula)
                        .contains(token.rawToken());
            }
        }
    }

    @Test
    void eachRangeTokenKeepsItsOwnAbsoluteMarkersNotASiblingsSpan() {
        // Two ranges over the same cells, one relative and one absolute. §13's
        // "single-cell drift tolerated" fixture makes mixed-absolute siblings an
        // expected shape, not an exotic one.
        FormulaTokenizerResult result = FormulaTokenizer.tokenize("SUM(A1:A5)+SUM($A$1:$A$5)", 10, 1, Map.of());
        assertThat(result.tokens()).hasSize(2);
        assertThat(result.tokens().get(0).rawToken()).isEqualTo("A1:A5");
        assertThat(result.tokens().get(1).rawToken()).isEqualTo("$A$1:$A$5");
    }

    @Test
    void fallbackSalvageOnParseExceptionReturnsParseErrorWithTokens() {
        // Formula with invalid syntax that POI parser rejects
        FormulaTokenizerResult result = FormulaTokenizer.tokenize("INVALID_SYNTAX(,,)'Sheet1'!A1", 1, 1, Map.of());
        assertThat(result.formulaState()).isEqualTo("parse_error");
        assertThat(result.tokens()).isNotEmpty();
    }
}
