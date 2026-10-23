package com.resurgent.tev.doc.induce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.doc.db.InductionDecisionRepository;
import com.resurgent.tev.doc.db.InductionDecisionRepository.Decision;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Writes pack and checklist induction outcomes into the existing
 * {@code induction_decision} table. Clause induction has its own writer.
 */
public final class InductionDecisionLog {

    static final String PACK_STAGE = "induce_pack";
    static final String CHECKLIST_STAGE = "induce_checklist";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InductionDecisionLog() {}

    /** JSON detail: the sanitized web query and the domain rationale. */
    public static String detail(String sanitizedQuery, String rationale) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("sanitized_query", sanitizedQuery == null ? "" : sanitizedQuery);
        node.put("rationale", rationale == null ? "" : rationale);
        return node.toString();
    }

    /**
     * Replaces this run's pack decisions, then inserts one row per note.
     *
     * @param parseRunId optional; a null run is not deleted on the next call
     */
    public static void recordPack(
            Connection connection,
            long mandateId,
            Long parseRunId,
            String targetCode,
            List<PackInducer.InductionNote> notes) throws SQLException {
        InductionDecisionRepository repository = new InductionDecisionRepository(connection);
        if (parseRunId != null) {
            repository.deleteForRun(parseRunId, PACK_STAGE);
        }
        if (notes == null) {
            return;
        }
        for (PackInducer.InductionNote note : notes) {
            repository.insert(new Decision(
                    null,
                    mandateId,
                    parseRunId,
                    PACK_STAGE,
                    targetCode,
                    note.candidateKey(),
                    note.outcome(),
                    note.reasonCode(),
                    note.detailJson()));
        }
    }

    /** One row per checklist change (added, made optional, or promoted to the spine). */
    public static void recordChecklist(
            Connection connection,
            long mandateId,
            long parseRunId,
            ChecklistLearner.Outcome outcome) throws SQLException {
        InductionDecisionRepository repository = new InductionDecisionRepository(connection);
        repository.deleteForRun(parseRunId, CHECKLIST_STAGE);
        if (outcome == null) {
            return;
        }
        for (ChecklistLearner.DefinitionUpdate update : outcome.updates()) {
            for (ChecklistLearner.ItemChange change : update.changes()) {
                String kind = change.kind() == null ? "" : change.kind();
                String recorded = switch (kind) {
                    case "made_optional" -> "demoted_optional";
                    case "added", "promoted" -> "promoted";
                    default -> "promoted";
                };
                String reason = switch (kind) {
                    case "made_optional" -> "coverage_demoted";
                    case "promoted" -> "spine_promoted";
                    default -> "observed_in_mandates";
                };
                repository.insert(new Decision(
                        null,
                        mandateId,
                        parseRunId,
                        CHECKLIST_STAGE,
                        update.code(),
                        change.itemKey(),
                        recorded,
                        reason,
                        detail("", change.reason())));
            }
        }
    }
}
