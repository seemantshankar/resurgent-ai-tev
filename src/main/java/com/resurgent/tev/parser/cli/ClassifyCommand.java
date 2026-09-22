package com.resurgent.tev.parser.cli;

import com.resurgent.tev.parser.classify.ClassifierLlm;
import com.resurgent.tev.parser.classify.ClassifyException;
import com.resurgent.tev.parser.classify.ClassifyLimits;
import com.resurgent.tev.parser.classify.ClassifyService;
import com.resurgent.tev.parser.classify.ClassifySummary;
import com.resurgent.tev.parser.classify.LlmEnvironment;
import com.resurgent.tev.parser.discover.DiscoverService;
import java.time.Duration;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** {@code tev-parse classify}: Layer A Packet disposition for an ingested parse run. */
@Command(name = "classify", description = "Classify Packets (Layer A disposition) for a parse run")
public final class ClassifyCommand implements Callable<Integer> {

    @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
    Path db;

    @Option(names = "--parse-run", required = true, description = "Parse run id to classify")
    long parseRunId;

    @Option(names = "--parallelism",
            description = "Concurrent LLM calls (default: " + ClassifyLimits.DEFAULT_PARALLELISM + ")")
    Integer parallelism;

    @Option(names = "--attempt-deadline-seconds",
            description = "Per-call hang budget in seconds (default: 180)")
    Long attemptDeadlineSeconds;

    @Option(names = "--classify-deadline-minutes",
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

    private ClassifyLimits limits() {
        if (parallelism == null && attemptDeadlineSeconds == null && classifyDeadlineMinutes == null) {
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
        try {
            ClassifySummary summary = new ClassifyService(llm, new DiscoverService(), limits)
                    .classify(db, parseRunId);
            out.printf(
                    "Classified parse_run %d: %d dispositions (%d coverage parents),"
                            + " %d Layer B bindings (%s), %d interpretations.%n",
                    summary.parseRunId(),
                    summary.dispositionCount(),
                    summary.coverageParentCount(),
                    summary.bindingCount(),
                    summary.layerBStats().summaryLine(),
                    summary.interpretationCount());
            if (!summary.unclassifiedCandidateIds().isEmpty()) {
                out.printf(
                        "Unclassified candidates (%d): %s%n",
                        summary.unclassifiedCandidateIds().size(),
                        summary.unclassifiedCandidateIds());
            }
            return 0;
        } catch (ClassifyException e) {
            err.println("classify rejected: " + e.getMessage());
            return 3;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            err.println("classify failed: " + msg);
            return 1;
        }
    }
}
