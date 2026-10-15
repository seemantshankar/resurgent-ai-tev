package com.resurgent.tev.doc.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code tev-doc assess}: run the M3 mandate assessment stages over ingested documents
 * (this ticket: profile — document dates).
 */
@Command(name = "assess",
        description = "Run the mandate assessment stages (document dates first)")
public final class DocAssessCommand implements Callable<Integer> {

    @Option(names = "--mandate", required = true, description = "Mandate id")
    long mandateId;

    @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
    Path db;

    @Spec
    CommandSpec spec;

    /** Runs the assessment pipeline and prints one summary line per stage. */
    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        try {
            var result = com.resurgent.tev.doc.assess.DocAssessRunner.run(mandateId, db);
            for (String line : result.summaryLines()) {
                out.println(line);
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("assess rejected: " + e.getMessage());
            return 3;
        } catch (Exception e) {
            err.println("assess failed: " + (e.getMessage() != null ? e.getMessage() : e));
            return 1;
        }
    }
}
