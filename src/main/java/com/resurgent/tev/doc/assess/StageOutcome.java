package com.resurgent.tev.doc.assess;

import com.resurgent.tev.doc.llm.DocLlmClient;
import java.util.List;

/**
 * What an assess stage records about its own run (#151). Every document-LLM call that hard-fails
 * reports itself into the stage's usage list, so a stage knows — without the stderr it has always
 * printed — whether the model was answering it at all.
 */
public final class StageOutcome {

    public static final String COMPLETED = StageStatus.COMPLETED.dbValue();
    public static final String PARTIAL = StageStatus.PARTIAL.dbValue();
    public static final String FAILED = StageStatus.FAILED.dbValue();

    private StageOutcome() {}

    /** Result of evaluating hard failures and recorded output for a stage. */
    public record Evaluation(StageStatus status, long failures) {
        public String statusDb() {
            return status.dbValue();
        }
    }

    /** Hard document-LLM failures among the calls this stage made. */
    public static long failures(List<DocLlmClient.Usage> usage) {
        return usage.stream().mapToLong(DocLlmClient.Usage::failedCalls).sum();
    }

    /**
     * The status the stage's run record carries.
     *
     * @param llmFailures hard LLM failures this stage counted
     * @param recordedResults results the stage did store, so a reader can tell a run that produced
     *     something from one the model was down for
     */
    public static StageStatus status(long llmFailures, long recordedResults) {
        if (llmFailures == 0) {
            return StageStatus.COMPLETED;
        }
        return recordedResults > 0 ? StageStatus.PARTIAL : StageStatus.FAILED;
    }

    /** Evaluates usage and output counts in one call, reducing runner duplication. */
    public static Evaluation evaluate(List<DocLlmClient.Usage> usage, long recordedResults) {
        long failures = failures(usage);
        return new Evaluation(status(failures, recordedResults), failures);
    }
}
