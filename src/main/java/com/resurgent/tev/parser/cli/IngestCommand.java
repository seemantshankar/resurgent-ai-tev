package com.resurgent.tev.parser.cli;

import com.resurgent.tev.parser.config.ConfigLoader;
import com.resurgent.tev.parser.config.ConfigValidationException;
import com.resurgent.tev.parser.config.ParserConfig;
import com.resurgent.tev.parser.db.DestructiveResetRequiredException;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.ingest.IngestRejectionException;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** {@code tev-parse ingest}: lands a client FM file in a SQLite workspace database. */
@Command(name = "ingest", description = "Ingest a client FM file into a SQLite workspace database")
public final class IngestCommand implements Callable<Integer> {

    @Option(names = "--input", required = true, description = "Path to the client FM file (.csv, .xlsx, .xlsm, .xls)")
    Path input;

    @Option(names = "--mandate-id", required = true, description = "Mandate this file belongs to")
    long mandateId;

    @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
    Path db;

    @Option(names = "--report", description = "Optionally write the parse metrics as JSON to this path")
    Path report;

    @Option(names = "--config", description = "Optional config.json overriding embedded defaults")
    Path config;

    @Option(names = "--allow-destructive-reset",
            description = "Allow Sprint 3b to erase parser-owned operational data in a populated pre-live workspace")
    boolean allowDestructiveReset;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        ParserConfig parserConfig;
        try {
            parserConfig = ConfigLoader.load(config);
        } catch (ConfigValidationException e) {
            err.println("invalid config: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            err.println("config load failed: " + e.getMessage());
            return 2;
        }
        try {
            WorkspaceDatabase.OpenOptions openOptions = allowDestructiveReset
                    ? WorkspaceDatabase.OpenOptions.allowDestructiveReset()
                    : WorkspaceDatabase.OpenOptions.defaults();
            IngestSummary summary = new IngestService().ingest(input, mandateId, db, parserConfig, openOptions);
            if (summary.existingRun()) {
                out.printf("Reused existing parse run for %s (worksheet '%s': %d cells from"
                        + " %d rows; source_file %d, parse_run %d, sha256 %s).%n",
                        summary.fileName(), summary.worksheetName(), summary.cellCount(),
                        summary.rowCount(), summary.sourceFileId(), summary.parseRunId(),
                        summary.fileHash().substring(0, 12));
            } else {
                out.printf("Ingested %s into worksheet '%s': %d cells from %d rows written to %s"
                        + " (source_file %d, parse_run %d, sha256 %s).%n",
                        summary.fileName(), summary.worksheetName(), summary.cellCount(),
                        summary.rowCount(), summary.dbPath(), summary.sourceFileId(),
                        summary.parseRunId(), summary.fileHash().substring(0, 12));
            }
            if (!"success".equals(summary.status())) {
                err.println("WARNING: parse run finished with status '" + summary.status()
                        + "' — QA gates were not fully satisfied; see parse_run.metrics"
                        + " for reasons (parse_run " + summary.parseRunId() + ").");
            }
            if (report != null) {
                summary.writeReport(report);
                err.println("parse report written to " + report);
            }
            return 0;
        } catch (DestructiveResetRequiredException e) {
            err.println(e.getMessage());
            return 1;
        } catch (IngestRejectionException e) {
            err.println("ingest rejected: " + e.getMessage());
            return 3;
        } catch (ConfigValidationException e) {
            err.println("invalid config: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            err.println("ingest failed: " + msg);
            return 1;
        }
    }
}
