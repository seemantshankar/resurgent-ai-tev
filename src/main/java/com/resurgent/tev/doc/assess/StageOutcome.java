package com.resurgent.tev.doc.assess;

import com.resurgent.tev.doc.llm.DocLlmClient;
import java.util.List;

/**
 * What an assess stage records about its own run (#151). Every document-LLM call that hard-fails
 * reports itself into the stage's usage list, so a stage knows — without the stderr it has always
 * printed — whether the model was answering it at all.
 */
final class StageOutcome {

    static final String COMPLETED = "completed";
    static final String PARTIAL = "partial";
    static final String FAILED = "failed";

    private StageOutcome() {}

    /** Hard document-LLM failures among the calls this stage made. */
    static long failures(List<DocLlmClient.Usage> usage) {
        return usage.stream().mapToLong(DocLlmClient.Usage::failedCalls).sum();
    }

    /**
     * The status the stage's run record carries.
     *
     * @param llmFailures hard LLM failures this stage counted
     * @param recordedResults results the stage did store, so a reader can tell a run that produced
     *     something from one the model was down for
     */
    static String status(long llmFailures, long recordedResults) {
        if (llmFailures == 0) {
            return COMPLETED;
        }
        return recordedResults > 0 ? PARTIAL : FAILED;
    }
}
