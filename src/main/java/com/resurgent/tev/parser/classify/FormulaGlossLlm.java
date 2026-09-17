package com.resurgent.tev.parser.classify;

/**
 * Narrow LLM port for explanatory formula gloss prose (#119).
 * Explanation only — not computation, validation, or a modelling rule.
 */
public interface FormulaGlossLlm {

    /**
     * Produce short explanatory gloss from a number-redacted prompt.
     * Return null/blank to skip persistence for that cell.
     */
    String gloss(FormulaGlossPrompt prompt);
}
