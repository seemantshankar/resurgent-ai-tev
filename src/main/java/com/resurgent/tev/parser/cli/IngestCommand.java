package com.resurgent.tev.parser.cli;

import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/** {@code tev-parse ingest}: lands a client FM file in a SQLite workspace database. */
@Command(name = "ingest", description = "Ingest a client FM file into a SQLite workspace database")
public final class IngestCommand implements Callable<Integer> {

    @Option(names = "--input", required = true, description = "Path to the client FM file (.csv for now)")
    Path input;

    @Option(names = "--mandate-id", required = true, description = "Mandate this file belongs to")
    long mandateId;

    @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
    Path db;

    @Option(names = "--report", description = "Optionally write the parse metrics as JSON to this path")
    Path report;

    @Option(names = "--config", description = "Optional config.json overriding embedded defaults")
    Path config;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        if (config != null) {
            err.println("note: --config is accepted but not yet applied; embedded defaults are in effect");
        }
        try {
            IngestSummary summary = new IngestService().ingest(input, mandateId, db);
            out.printf("Ingested %s into worksheet '%s': %d cells from %d rows written to %s"
                    + " (source_file %d, parse_run %d, sha256 %s).%n",
                    summary.fileName(), summary.worksheetName(), summary.cellCount(),
                    summary.rowCount(), summary.dbPath(), summary.sourceFileId(),
                    summary.parseRunId(), summary.fileHash().substring(0, 12));
            if (report != null) {
                summary.writeReport(report);
                err.println("parse report written to " + report);
            }
            return 0;
        } catch (Exception e) {
            err.println("ingest failed: " + e.getMessage());
            return 1;
        }
    }
}
