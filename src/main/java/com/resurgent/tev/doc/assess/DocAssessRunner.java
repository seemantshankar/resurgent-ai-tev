package com.resurgent.tev.doc.assess;

import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmEnvironment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CLI facade for the M3 mandate assessment. Runs the registered stages in pipeline
 * order — profile, clauses, passages, conflicts and checklist now — each replacing its
 * previous results for the mandate. Conflicts run before the checklist: a conflict left
 * open is what makes a checklist item Conflicting. Processing new documents
 * (extract/bind) ends by re-running this.
 */
public final class DocAssessRunner {

    /** Parser version stamped on the assess {@code parse_run}s carrying stage usage. */
    public static final String PARSER_VERSION = "0.1.0-doc-assess";

    /** Pipeline contract: later M3/M4 tickets append their stage runners here, in order. */
    private static final List<AssessStage> STAGES = List.of(
            new AssessProfileRunner(), new AssessClausesRunner(), new AssessPassagesRunner(),
            new AssessConflictsRunner(), new AssessChecklistRunner(), new AssessFreshnessRunner());

    private DocAssessRunner() {}

    /**
     * Runs every stage with the configured live LLM seam, or offline when none is set.
     *
     * @param mandateId mandate scope
     * @param db workspace database
     * @return assessment summary
     * @throws Exception on runtime failure
     */
    public static Result run(long mandateId, Path db) throws Exception {
        return run(mandateId, db, DocLlmEnvironment.clientOrNull());
    }

    /**
     * Runs every stage in order.
     *
     * @param mandateId mandate scope
     * @param db workspace database
     * @param client document LLM seam, or null for the offline path
     * @return assessment summary
     * @throws Exception on runtime failure
     */
    public static Result run(long mandateId, Path db, DocLlmClient client) throws Exception {
        List<AssessStage.StageResult> stages = new ArrayList<>();
        for (AssessStage stage : STAGES) {
            stages.add(stage.run(mandateId, db, client));
        }
        return new Result(mandateId, List.copyOf(stages));
    }

    /**
     * Assessment summary.
     *
     * @param mandateId mandate scope
     * @param stages one summary per executed stage, in pipeline order
     */
    public record Result(long mandateId, List<AssessStage.StageResult> stages) {

        /**
         * One printable line per executed stage, in pipeline order. A stage that is not
         * clean names its status and how many document-LLM calls were lost (#151); a
         * clean line stays exactly as it always read.
         */
        public List<String> summaryLines() {
            List<String> lines = new ArrayList<>();
            for (AssessStage.StageResult stage : stages) {
                String line = stage.stage() + ": " + stage.detail();
                if (!StageOutcome.COMPLETED.equals(stage.status())) {
                    line += String.format(Locale.ROOT, " [%s, %d LLM calls failed]",
                            stage.status(), stage.llmFailures());
                }
                lines.add(line);
            }
            return lines;
        }
    }
}
