package com.resurgent.tev.parser.classify;

/** No-op gloss port: classify stays offline/fake without a gloss provider. */
public final class NoOpFormulaGlossLlm implements FormulaGlossLlm {

    @Override
    public String gloss(FormulaGlossPrompt prompt) {
        return null;
    }
}
