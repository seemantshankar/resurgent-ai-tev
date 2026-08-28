package com.resurgent.tev.parser.cli;

import com.resurgent.tev.parser.config.ConfigLoader;
import com.resurgent.tev.parser.config.ConfigValidationException;
import com.resurgent.tev.parser.config.ParserConfig;
import com.resurgent.tev.parser.db.DestructiveResetRequiredException;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.redact.RedactException;
import com.resurgent.tev.parser.redact.RedactService;
import com.resurgent.tev.parser.redact.RedactSummary;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** {@code tev-parse redact}: export sheet(s) with numeric literals replaced by dummies. */
@Command(name = "redact", description = "Export number-redacted workbook tab(s) for external model input")
public final class RedactCommand implements Callable<Integer> {

    @Option(names = "--input", required = true, description = "Path to the original client .xlsx or .xls file")
    Path input;

    @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
    Path db;

    @Option(names = "--mandate-id", required = true, description = "Mandate this file belongs to")
    long mandateId;

    @Option(names = "--sheet", description = "Worksheet tab name to redact (required unless --all-sheets)")
    String sheet;

    @Option(names = "--all-sheets", description = "Redact every tab in the workbook")
    boolean allSheets;

    @Option(names = "--output-dir", required = true, description = "Directory for the redacted workbook output")
    Path outputDir;

    @Option(names = "--report", description = "Optionally override the redaction audit JSON path (default: beside the redacted workbook)")
    Path report;

    @Option(names = "--config", description = "Optional config.json for auto-ingest when the file is not yet in the workspace DB")
    Path config;

    @Option(names = "--allow-destructive-reset",
            description = "Allow auto-ingest to erase parser-owned operational data in a populated pre-live workspace")
    boolean allowDestructiveReset;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();

        boolean sheetProvided = sheet != null && !sheet.isBlank();
        if (allSheets && sheetProvided) {
            err.println("redact rejected: --sheet and --all-sheets are mutually exclusive");
            return 2;
        }
        if (!allSheets && !sheetProvided) {
            err.println("redact rejected: --sheet is required unless --all-sheets is set");
            return 2;
        }

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

        WorkspaceDatabase.OpenOptions openOptions = allowDestructiveReset
                ? WorkspaceDatabase.OpenOptions.allowDestructiveReset()
                : WorkspaceDatabase.OpenOptions.defaults();

        try {
            RedactService service = new RedactService();
            RedactSummary summary = allSheets
                    ? service.redactAllSheets(input, mandateId, db, outputDir, parserConfig, openOptions)
                    : service.redact(input, mandateId, db, sheet, outputDir, parserConfig, openOptions);
            if (summary.autoIngested()) {
                out.printf("Auto-ingested %s into %s before redaction.%n",
                        summary.fileName(), db.toAbsolutePath().normalize());
            }
            if (summary.allSheets()) {
                out.printf("Redacted %d sheets from %s: %d cells replaced, wrote %s%n",
                        summary.sheetsProcessed(), summary.fileName(), summary.cellsRedacted(),
                        summary.outputPath());
            } else {
                out.printf("Redacted sheet '%s' from %s: %d cells replaced, wrote %s%n",
                        summary.sheetName(), summary.fileName(), summary.cellsRedacted(),
                        summary.outputPath());
            }
            Path reportPath = report != null ? report : summary.defaultReportPath();
            summary.writeReport(reportPath);
            err.println("redact report written to " + reportPath);
            return 0;
        } catch (DestructiveResetRequiredException e) {
            err.println(e.getMessage());
            return 1;
        } catch (RedactException e) {
            err.println("redact rejected: " + e.getMessage());
            return 3;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            err.println("redact failed: " + msg);
            return 1;
        }
    }
}
