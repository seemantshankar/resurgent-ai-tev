package com.resurgent.tev.parser.classify;

import java.util.List;

/**
 * Number-redacted payload for formula gloss. Carries formula text, dependency
 * annotations, header evidence, schedule family, and binding context — never
 * cached amounts or resulting values (ADR 0008).
 */
public record FormulaGlossPrompt(
        long parseRunId,
        long cellId,
        String formulaText,
        List<FormulaAnnotation> annotations,
        List<InterpretationEvidence> evidence,
        String scheduleFamily,
        String nomenclaturePath,
        String amountRole,
        String nomenclatureStatus) {

    public FormulaGlossPrompt {
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
