package com.resurgent.tev.parser.cli;

import com.resurgent.tev.parser.classify.BindSummary;
import com.resurgent.tev.parser.classify.ClassifierLlm;
import com.resurgent.tev.parser.classify.ClassifyException;
import com.resurgent.tev.parser.classify.ClassifyLimits;
import com.resurgent.tev.parser.classify.ClassifyService;
import com.resurgent.tev.parser.classify.ClassifyTuning;
import com.resurgent.tev.parser.classify.ClassifySummary;
import com.resurgent.tev.parser.classify.LlmEnvironment;
import com.resurgent.tev.parser.classify.LlmStats;
import com.resurgent.tev.parser.classify.OpenRouterClassifierLlm;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.discover.DiscoverService;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
/** {@code tev-parse classify}: Layer A disposition + about for main/helper Candidates. */
@Command(
        name = "classify",
        description = "Classify main/helper Packets (Layer A disposition + about)")
public final class ClassifyCommand implements Callable<Integer> {

    @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
    Path db;

    @Option(names = "--parse-run", required = true, description = "Parse run id to classify")
    long parseRunId;

    @Option(
            names = "--sheet",
            description = "Bind Layer B on this sheet (repeatable). Skips region layout and Layer A.")
    List<String> sheets;

    @Option(
            names = "--only-sheet",
            description = "Send only this sheet (repeatable) to the model, plus any sheet its formulas"
                    + " read. All cells and formula links stay loaded so formulas still resolve.")
    List<String> onlySheets;

    @Option(
            names = "--cell-batch-size",
            description = "Cells per Layer B call (default: " + ClassifyTuning.DEFAULT_CELL_BATCH_SIZE + ")")
    Integer cellBatchSize;

    @Option(
            names = "--layer-a-batch-size",
            description = "Candidates per Layer A call (default: " + ClassifyTuning.DEFAULT_LAYER_A_BATCH_SIZE + ")")
    Integer layerABatchSize;

    @Option(
            names = "--parallelism",
            description = "Concurrent LLM calls (default: " + ClassifyLimits.DEFAULT_PARALLELISM + ")")
    Integer parallelism;

    @Option(
            names = "--attempt-deadline-seconds",
            description = "Per-call hang budget in seconds (default: 180)")
    Long attemptDeadlineSeconds;

    @Option(
            names = "--classify-deadline-minutes",
            description = "Whole-run abort budget in minutes (default: 15)")
    Long classifyDeadlineMinutes;

    @Spec
    CommandSpec spec;

    private final ClassifierLlm llm;

    public ClassifyCommand() {
        this(LlmEnvironment.classifierOrUnconfigured());
    }

    public ClassifyCommand(ClassifierLlm llm) {
        this.llm = llm;
    }

    private void printUsage(PrintWriter out) {
        printChatUsage(out);
        // The whole run, every model it called: the decision model keeps its own counter, so the line
        // above (the chat models only) does not include it.
        com.resurgent.tev.parser.classify.UsageReport.lines(LlmStats.GLOBAL.usageRows()).forEach(out::println);
    }

    private void printChatUsage(PrintWriter out) {
        if (!(llm instanceof OpenRouterClassifierLlm open)) {
            return;
        }
        OpenRouterClassifierLlm.UsageTotals usage = open.usageTotals();
        String cost = usage.costKnown()
                ? String.format(Locale.US, "%.6f", usage.costUsd())
                : "unknown";
        out.printf(
                "LLM_USAGE calls=%d prompt_tokens=%d completion_tokens=%d cost_usd=%s cost_missing=%d%n",
                usage.calls(),
                usage.promptTokens(),
                usage.completionTokens(),
                cost,
                usage.costMissing());
    }

    private ClassifyLimits limits() {
        if (parallelism == null
                && attemptDeadlineSeconds == null
                && classifyDeadlineMinutes == null) {
            return ClassifyLimits.defaults();
        }
        return new ClassifyLimits(
                parallelism != null ? parallelism : ClassifyLimits.DEFAULT_PARALLELISM,
                attemptDeadlineSeconds != null
                        ? Duration.ofSeconds(attemptDeadlineSeconds)
                        : ClassifyLimits.DEFAULT_ATTEMPT_DEADLINE,
                classifyDeadlineMinutes != null
                        ? Duration.ofMinutes(classifyDeadlineMinutes)
                        : ClassifyLimits.DEFAULT_CLASSIFY_DEADLINE);
    }

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        ClassifyLimits limits;
        try {
            limits = limits();
        } catch (IllegalArgumentException e) {
            err.println("invalid classify limits: " + e.getMessage());
            return 2;
        }
        java.io.PrintStream originalErr = System.err;
        System.setErr(com.resurgent.tev.parser.TimestampedStream.wrap(originalErr));
        LlmStats.GLOBAL.reset();
        LlmEnvironment.recordSettings(LlmStats.GLOBAL);
        LlmStats.GLOBAL.put("run", "cell_batch_size",
                cellBatchSize != null ? cellBatchSize : ClassifyTuning.DEFAULT_CELL_BATCH_SIZE);
        LlmStats.GLOBAL.put("run", "layer_a_batch_size",
                layerABatchSize != null ? layerABatchSize : ClassifyTuning.DEFAULT_LAYER_A_BATCH_SIZE);
        LlmStats.GLOBAL.put("run", "parallelism", limits.parallelism());
        LlmStats.GLOBAL.putText("run", "scope",
                onlySheets == null || onlySheets.isEmpty() ? "whole workbook" : String.join(", ", onlySheets));
        System.err.println("[classify] models in order: " + LlmEnvironment.describeModels());
        try (Heartbeat heartbeat = new Heartbeat(30)) {
            ClassifyService service = new ClassifyService(llm, new DiscoverService(), limits)
                    .withTuning(new ClassifyTuning(
                            cellBatchSize != null ? cellBatchSize : ClassifyTuning.DEFAULT_CELL_BATCH_SIZE,
                            layerABatchSize != null ? layerABatchSize : ClassifyTuning.DEFAULT_LAYER_A_BATCH_SIZE,
                            limits.parallelism()))
                    .withSheetScope(onlySheets)
                    .withRegionTriageFromEnvironment();
            if (sheets != null && !sheets.isEmpty()) {
                BindSummary bound = service.bindSheets(db, parseRunId, sheets);
                out.printf(
                        "Bound parse_run %d: %d cells with a path, %d left unbound (errors and scratch).%n",
                        bound.parseRunId(),
                        bound.boundCells(),
                        bound.skippedCells());
                printUsage(out);
                return 0;
            }
            ClassifySummary summary = service.classify(db, parseRunId);
            out.printf(
                    "Classified parse_run %d: %d dispositions (%d eligible, %d skipped).%n",
                    summary.parseRunId(),
                    summary.dispositionCount(),
                    summary.eligibleCount(),
                    summary.skippedCount());
            printUsage(out);
            return 0;
        } catch (ClassifyException e) {
            err.println("classify rejected: " + e.getMessage());
            return 3;
        } finally {
            System.setErr(originalErr);
            persistStats(out, parseRunId);
        }
    }
    /** Saves per-stage, per-model usage, settings, counts and timings; also after a failed run. */
    private void persistStats(PrintWriter out, long parseRunId) {
        if (LlmStats.GLOBAL.usageRows().isEmpty() && LlmStats.GLOBAL.timings().isEmpty()) {
            return;
        }
        StatsRecorder.persist(out, db, parseRunId);
    }

}
