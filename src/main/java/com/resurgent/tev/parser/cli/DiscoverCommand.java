package com.resurgent.tev.parser.cli;

import com.resurgent.tev.parser.discover.DiscoverException;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.discover.DiscoverSummary;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** {@code tev-parse discover}: emit Candidates for an ingested parse run (DB-only). */
@Command(name = "discover", description = "Discover Candidates from an ingested parse run in SQLite")
public final class DiscoverCommand implements Callable<Integer> {

    @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
    Path db;

    @Option(names = "--parse-run", required = true, description = "Parse run id to discover")
    long parseRunId;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        try {
            DiscoverSummary summary = new DiscoverService().discover(db, parseRunId);
            out.printf(
                    "Discovered parse_run %d: %d worksheets, %d candidates"
                            + " (%d isolated hidden), coverage %s.%n",
                    summary.parseRunId(),
                    summary.worksheetCount(),
                    summary.candidateCount(),
                    summary.isolatedHiddenWorksheetCount(),
                    summary.coverageCheckPassed() ? "ok" : "FAILED");
            return 0;
        } catch (DiscoverException e) {
            err.println("discover rejected: " + e.getMessage());
            return 3;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            err.println("discover failed: " + msg);
            return 1;
        }
    }
}
