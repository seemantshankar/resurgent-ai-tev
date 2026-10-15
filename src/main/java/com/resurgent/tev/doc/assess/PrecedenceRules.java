package com.resurgent.tev.doc.assess;

import java.util.List;
import java.util.Optional;

/**
 * The versioned source-precedence rule set of the M3 conflict assessment (issue #145,
 * ADR 0026 tier 1): deterministic rules over the document types of the two sides that
 * settle a disagreement without asking the model. Versioned data-in-code like a pack:
 * every rule has an id and a reason, and every settlement records both the rule id and
 * the set's version on the conflict row. The first matching rule wins; a pair no rule
 * covers falls through to the AI tier.
 *
 * <p>Rules:
 * <ul>
 *   <li>{@code agreement-over-narrative}: a governing agreement (any
 *       {@code agreement.*} pack) beats a narrative restatement in a project report
 *       ({@code report.dpr}).</li>
 *   <li>{@code audited-over-quoted}: audited financial statements
 *       ({@code financials.audited_summary}) beat a vendor's quoted figure
 *       ({@code quotation.vendor}).</li>
 * </ul>
 */
public final class PrecedenceRules {

    /** Version of the rule set, recorded on every rule-settled conflict. */
    public static final String VERSION = "1.0.0";

    /**
     * One precedence rule: when one side's document type is a winner type (prefix)
     * and the other's is a loser type, the winner's value governs.
     */
    private record Rule(String id, String reason, String winnerTypePrefix, String loserType) {}

    private static final List<Rule> RULES = List.of(
            new Rule("agreement-over-narrative",
                    "A governing agreement beats a narrative restatement of the same term"
                            + " in a project report (ADR 0026)",
                    "agreement.", "report.dpr"),
            new Rule("audited-over-quoted",
                    "Audited financial statements beat a quoted figure (ADR 0026)",
                    "financials.audited_summary", "quotation.vendor"));

    private PrecedenceRules() {}

    /**
     * Settles one disagreement by precedence, when a rule covers the two document
     * types. Either orientation matches: the winner type may be on either side.
     *
     * @param leftDocType left side's classified document type, or null
     * @param rightDocType right side's classified document type, or null
     * @return the decision (rule id, set version, reason, chosen side), or empty
     */
    public static Optional<Decision> settle(String leftDocType, String rightDocType) {
        if (leftDocType == null || rightDocType == null) {
            return Optional.empty();
        }
        for (Rule rule : RULES) {
            if (matches(rule, leftDocType, rightDocType)) {
                return Optional.of(new Decision(rule.id(), VERSION, rule.reason(), true));
            }
            if (matches(rule, rightDocType, leftDocType)) {
                return Optional.of(new Decision(rule.id(), VERSION, rule.reason(), false));
            }
        }
        return Optional.empty();
    }

    private static boolean matches(Rule rule, String winnerType, String loserType) {
        return winnerType.startsWith(rule.winnerTypePrefix())
                && loserType.equals(rule.loserType());
    }

    /**
     * One rule's decision.
     *
     * @param ruleId the rule that decided
     * @param rulesVersion version of the rule set
     * @param reason why the rule settles this kind of disagreement
     * @param choseLeft true when the left side's value governs
     */
    public record Decision(String ruleId, String rulesVersion, String reason, boolean choseLeft) {}
}
