package com.resurgent.tev.parser.cli;

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

        try {
            RedactService service = new RedactService();
            RedactSummary summary = allSheets
                    ? service.redactAllSheets(input, mandateId, db, outputDir)
                    : service.redact(input, mandateId, db, sheet, outputDir);
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
