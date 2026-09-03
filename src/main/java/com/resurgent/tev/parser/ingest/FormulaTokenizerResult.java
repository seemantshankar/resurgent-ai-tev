package com.resurgent.tev.parser.ingest;

import java.util.List;

/**
 * Result of tokenizing a formula string into reference tokens, function tokens, and parse state.
 */
public record FormulaTokenizerResult(
        String formulaState,
        List<FormulaToken> tokens,
        List<String> functionTokens
) {}
