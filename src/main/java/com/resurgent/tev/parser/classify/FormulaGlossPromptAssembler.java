package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Assembles number-redacted formula-gloss messages. Omits resulting values and
 * cached amounts; keeps formula text, annotation structure, headers, and binding.
 */
final class FormulaGlossPromptAssembler {

    static final String SYSTEM = """
            You explain one spreadsheet formula for an analyst.
            Return plain prose only (1-3 short sentences). No JSON, no markdown.
            Do not compute, validate, or invent modelling rules. Do not invent amounts.
            Describe what the formula appears to combine using labels and dependency hints.
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FormulaGlossPromptAssembler() {}

    static String userMessage(FormulaGlossPrompt prompt) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("formulaText", prompt.formulaText() == null ? "" : prompt.formulaText());
            putIfPresent(root, "scheduleFamily", prompt.scheduleFamily());
            putIfPresent(root, "nomenclaturePath", prompt.nomenclaturePath());
            putIfPresent(root, "amountRole", prompt.amountRole());
            putIfPresent(root, "nomenclatureStatus", prompt.nomenclatureStatus());

            ArrayNode annotations = root.putArray("annotations");
            for (FormulaAnnotation annotation : prompt.annotations()) {
                ObjectNode node = annotations.addObject();
                node.put("ordinal", annotation.ordinal());
                node.put("rawToken", annotation.rawToken());
                node.put("refKind", annotation.refKind());
                putIfPresent(node, "targetSheetName", annotation.targetSheetName());
                putIfPresent(node, "targetRange", annotation.targetRange());
                node.put("completeness", annotation.completeness());
                putIfPresent(node, "enclosingFunction", annotation.enclosingFunction());
                putIfPresent(node, "sharedDependencyPath", annotation.sharedDependencyPath());
                putIfPresent(node, "sharedDependencyKind", annotation.sharedDependencyKind());
                ArrayNode members = node.putArray("members");
                for (FormulaAnnotationMember member : annotation.members()) {
                    ObjectNode m = members.addObject();
                    m.put("coord", member.coord());
                    putIfPresent(m, "nomenclaturePath", member.nomenclaturePath());
                    putIfPresent(m, "nomenclatureStatus", member.nomenclatureStatus());
                }
            }

            ArrayNode evidence = root.putArray("evidence");
            for (InterpretationEvidence item : prompt.evidence()) {
                ObjectNode node = evidence.addObject();
                node.put("role", item.role());
                node.put("resolution", item.resolution());
                putIfPresent(node, "sourceText", item.sourceText());
                putIfPresent(node, "normalizedValue", item.normalizedValue());
            }
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("formula gloss prompt assembly failed", e);
        }
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }
}
