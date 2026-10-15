package com.resurgent.tev.doc.cli;

import com.resurgent.tev.doc.db.MandateQaQueryRunner;
import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.doc.query.MandateQaQuery;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code tev-doc qa}: print the persisted mandate assessment results — per-document
 * document date, precision, source and upload date, the assessment runs, and the
 * mandate entities with their roles, aliases and source pages — without re-parsing
 * anything. Read-through-API only, like {@code facts}.
 */
@Command(name = "qa",
        description = "Show persisted mandate QA: document dates, stages and entities")
public final class DocQaCommand implements Callable<Integer> {

    @Option(names = "--mandate", required = true, description = "Mandate id")
    long mandateId;

    @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
    Path db;

    @Option(names = "--json", description = "Machine-readable JSON export")
    boolean json;

    @Spec
    CommandSpec spec;

    /** Queries the mandate QA read and prints text or JSON. */
    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        try {
            MandateQa qa = MandateQaQueryRunner.query(db, MandateQaQuery.forMandate(mandateId));
            if (json) {
                out.println(com.resurgent.tev.doc.query.MandateQaJson.write(qa));
                return 0;
            }
            for (MandateQa.DocumentDateRow row : qa.documents()) {
                out.printf("%s %s | %s | date %s (%s, %s) kind=%s%s%n",
                        blankToDash(row.logicalName()),
                        blankToDash(row.versionLabel()),
                        blankToDash(row.fileName()),
                        row.documentDate() != null ? row.documentDate() : "(none)",
                        blankToDash(row.datePrecision()),
                        blankToDash(row.dateSource()),
                        blankToDash(row.dateKind()),
                        row.pageIndex() != null ? " page=" + row.pageIndex() : "");
                out.printf("  upload_date %s%n",
                        row.uploadDate() != null ? row.uploadDate() : "-");
                if (row.statedText() != null) {
                    out.printf("  stated: %s%n", row.statedText());
                }
            }
            out.printf("documents: %d%n", qa.documents().size());
            for (MandateQa.AssessmentRunRow stage : qa.stages()) {
                out.printf("stage %s: %s (started %s, finished %s)%s%n",
                        stage.stage(),
                        stage.status(),
                        blankToDash(stage.startedAt()),
                        stage.finishedAt() != null ? stage.finishedAt() : "-",
                        stage.parseRunId() != null ? " parse_run=" + stage.parseRunId() : "");
            }
            out.printf("stages: %d%n", qa.stages().size());
            for (MandateQa.EntityRow entity : qa.entities()) {
                out.printf("%s: %s | %s%n", entity.role(), entity.name(),
                        blankToDash(entity.reason()));
                for (MandateQa.AliasRow alias : entity.aliases()) {
                    out.printf("  alias: %s%s%n", alias.alias(),
                            alias.pageIndex() != null ? " (page " + alias.pageIndex() + ")" : "");
                }
                out.printf("  pages: %s%n", pagesText(entity.pageIndexes()));
            }
            out.printf("entities: %d%n", qa.entities().size());
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("qa rejected: " + e.getMessage());
            return 3;
        } catch (Exception e) {
            err.println("qa failed: " + (e.getMessage() != null ? e.getMessage() : e));
            return 1;
        }
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String pagesText(List<Integer> pageIndexes) {
        if (pageIndexes == null || pageIndexes.isEmpty()) {
            return "-";
        }
        return pageIndexes.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }
}
