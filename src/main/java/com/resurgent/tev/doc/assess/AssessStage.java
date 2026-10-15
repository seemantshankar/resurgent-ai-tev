package com.resurgent.tev.doc.assess;

import com.resurgent.tev.doc.llm.DocLlmClient;
import java.nio.file.Path;

/**
 * One M3 mandate-assessment stage. {@link DocAssessRunner} executes the registered
 * stages in pipeline order (profile, then — as M3 lands them — passages, clauses,
 * conflicts, checklist); each stage replaces its own persisted results for the mandate
 * within one transaction.
 */
public interface AssessStage {

    /** Exact stage name written to {@code assessment_run}/{@code run_stat}/{@code llm_usage}. */
    String name();

    /**
     * Runs the stage for a mandate.
     *
     * @param mandateId mandate scope
     * @param dbPath workspace database
     * @param client document LLM seam, or null when offline
     * @return stage summary
     * @throws Exception on runtime failure (the stage rolls back before rethrowing)
     */
    StageResult run(long mandateId, Path dbPath, DocLlmClient client) throws Exception;

    /**
     * Stage summary for the CLI.
     *
     * @param stage stage name ({@code assess_profile}/…)
     * @param items primary item count the stage processed (e.g. document versions)
     * @param detail one-line human detail
     */
    record StageResult(String stage, int items, String detail) {}
}
