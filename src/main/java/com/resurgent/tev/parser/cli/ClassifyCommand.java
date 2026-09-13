package com.resurgent.tev.parser.cli;

import com.resurgent.tev.parser.classify.ClassifierLlm;
import com.resurgent.tev.parser.classify.ClassifyException;
import com.resurgent.tev.parser.classify.ClassifyService;
import com.resurgent.tev.parser.classify.ClassifySummary;
import com.resurgent.tev.parser.classify.LlmEnvironment;
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

    @Spec
    CommandSpec spec;

    private final ClassifierLlm llm;

    public ClassifyCommand() {
        this(LlmEnvironment.classifierOrUnconfigured());
    }

    public ClassifyCommand(ClassifierLlm llm) {
        this.llm = llm;
    }

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        try {
            ClassifySummary summary = new ClassifyService(llm).classify(db, parseRunId);
            out.printf(
                    "Classified parse_run %d: %d dispositions (%d coverage parents).%n",
                    summary.parseRunId(),
                    summary.dispositionCount(),
                    summary.coverageParentCount());
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
