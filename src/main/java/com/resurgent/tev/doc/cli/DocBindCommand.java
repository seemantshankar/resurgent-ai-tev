package com.resurgent.tev.doc.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code tev-doc bind}: pack-path ontology binding for an extract parse_run.
 */
@Command(name = "bind",
        description = "Bind extracted facts to pack ontology paths (deterministic, pack-path only)")
public final class DocBindCommand implements Callable<Integer> {

    @Option(names = "--mandate", required = true, description = "Mandate id")
    long mandateId;

    @Option(names = "--run", required = true, description = "parse_run_id from extract")
    long parseRunId;

    @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
    Path db;

    @Option(names = "--pack", description = "Pack code override (default: from run config)")
    String pack;

    @Spec
    CommandSpec spec;

    /** Runs bind and prints bound/unbound counts and paths touched. */
    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        try {
            var result = com.resurgent.tev.doc.bind.DocBindRunner.run(
                    mandateId, parseRunId, db, pack);
            out.printf("bound=%d unbound=%d not_applicable=%d%n",
                    result.bound(), result.unbound(), result.notApplicable());
            for (String path : result.paths()) {
                out.printf("  path: %s%n", path);
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("bind rejected: " + e.getMessage());
            return 3;
        } catch (Exception e) {
            err.println("bind failed: " + (e.getMessage() != null ? e.getMessage() : e));
            return 1;
        }
    }
}
