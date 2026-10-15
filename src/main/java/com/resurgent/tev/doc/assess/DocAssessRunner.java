package com.resurgent.tev.doc.assess;

import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmEnvironment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI facade for the M3 mandate assessment. Runs the registered stages in pipeline
 * order — profile now; passages, clauses, conflicts and checklist slot in as the later
 * M3 tickets land — each replacing its previous results for the mandate. Processing new
 * documents (extract/bind) ends by re-running this.
 */
public final class DocAssessRunner {

    /** Parser version stamped on the assess {@code parse_run}s carrying stage usage. */
    public static final String PARSER_VERSION = "0.1.0-doc-assess";

    /** Pipeline contract: later M3 tickets append their stage runners here, in order. */
    private static final List<AssessStage> STAGES = List.of(
            new AssessProfileRunner(), new AssessClausesRunner(), new AssessChecklistRunner());

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

        /** One printable line per executed stage, in pipeline order. */
        public List<String> summaryLines() {
            List<String> lines = new ArrayList<>();
            for (AssessStage.StageResult stage : stages) {
                lines.add(stage.stage() + ": " + stage.detail());
            }
            return lines;
        }
    }
}
