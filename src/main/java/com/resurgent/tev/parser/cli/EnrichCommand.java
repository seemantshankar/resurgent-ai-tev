package com.resurgent.tev.parser.cli;

import com.resurgent.tev.parser.config.ConfigLoader;
import com.resurgent.tev.parser.config.ConfigValidationException;
import com.resurgent.tev.parser.config.ParserConfig;
import com.resurgent.tev.parser.db.DestructiveResetRequiredException;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.enrichment.EnrichService;
import com.resurgent.tev.parser.enrichment.EnrichSummary;
import com.resurgent.tev.parser.enrichment.EnrichmentInfrastructureException;
import com.resurgent.tev.parser.enrichment.EnrichmentModelClient;
import com.resurgent.tev.parser.enrichment.EnrichmentReportJson;
import com.resurgent.tev.parser.enrichment.LlmEnrichmentConfig;
import com.resurgent.tev.parser.enrichment.LlmEnrichmentConfigLoader;
import com.resurgent.tev.parser.redact.RedactException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** {@code tev-parse enrich}: produce an inspectable report for one redacted tab. */
@Command(name = "enrich", description = "Propose and validate semantic regions for one workbook tab")
public final class EnrichCommand implements Callable<Integer> {

    private final EnrichmentModelClient modelClient;

    @Option(names = "--input", required = true, description = "Path to the original client .xlsx file")
    Path input;

    @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
    Path db;

    @Option(names = "--mandate-id", required = true, description = "Mandate this file belongs to")
    long mandateId;

    @Option(names = "--sheet", required = true, description = "Worksheet tab name to enrich")
    String sheet;

    @Option(names = "--output-dir", required = true, description = "Directory for enrichment artifacts")
    Path outputDirectory;

    @Option(names = "--report", description = "Override the enrichment JSON report path")
    Path reportPath;

    @Option(names = "--config", description = "Config JSON containing parser and LLM settings")
    Path configPath;

    @Option(names = "--allow-destructive-reset",
            description = "Allow auto-ingest to erase parser-owned operational data in a populated pre-live workspace")
    boolean allowDestructiveReset;

    @Spec
    CommandSpec spec;

    public EnrichCommand(EnrichmentModelClient modelClient) {
        this.modelClient = modelClient;
    }

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        final ParserConfig parserConfig;
        final LlmEnrichmentConfig llmConfig;
        try {
            parserConfig = ConfigLoader.load(configPath);
            llmConfig = LlmEnrichmentConfigLoader.load(configPath);
        } catch (ConfigValidationException e) {
            err.println("invalid config: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            err.println("config load failed: " + message(e));
            return 2;
        }

        WorkspaceDatabase.OpenOptions openOptions = allowDestructiveReset
                ? WorkspaceDatabase.OpenOptions.allowDestructiveReset()
                : WorkspaceDatabase.OpenOptions.defaults();
        try {
            EnrichSummary summary = new EnrichService(modelClient).enrich(
                    input,
                    mandateId,
                    db,
                    sheet,
                    outputDirectory,
                    parserConfig,
                    llmConfig,
                    openOptions);
            Path destination = reportPath != null
                    ? reportPath
                    : summary.defaultReportPath(outputDirectory);
            Path parent = destination.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            EnrichmentReportJson.write(destination, summary.report());

            if (summary.autoIngested()) {
                out.printf("Auto-ingested %s into %s before enrichment.%n",
                        summary.report().fileName(), db.toAbsolutePath().normalize());
            }
            out.printf("Enriched sheet '%s' from %s: %d %s, types [%s], %d problems%n",
                    summary.report().sheetName(),
                    summary.report().fileName(),
                    summary.report().regions().size(),
                    summary.report().regions().size() == 1 ? "region" : "regions",
                    String.join(", ", summary.typesUsed()),
                    summary.report().problems().size());
            err.println("enrichment report written to " + destination);
            return summary.report().problems().isEmpty() ? 0 : 3;
        } catch (DestructiveResetRequiredException e) {
            err.println(e.getMessage());
            return 1;
        } catch (RedactException e) {
            err.println("enrich rejected: " + e.getMessage());
            return 3;
        } catch (EnrichmentInfrastructureException e) {
            err.println("enrich failed: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            err.println("enrich failed: " + message(e));
            return 1;
        }
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null ? exception.toString() : exception.getMessage();
    }
}
