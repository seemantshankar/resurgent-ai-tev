package com.resurgent.tev.doc.cli;

import com.resurgent.tev.doc.db.MandateQaQueryRunner;
import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.doc.query.MandateQaQuery;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code tev-doc qa}: print the persisted mandate assessment results — per-document
 * document date, precision, source and upload date, the assessment runs, the mandate
 * entities with their roles, aliases and source pages, and the mandate sites with their
 * aliases and mentioning documents — without re-parsing anything. Read-through-API only,
 * like {@code facts}.
 */
@Command(name = "qa",
        description = "Show persisted mandate QA: document dates, stages, entities and sites")
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
            // M3-03 (#136): entities with their roles, aliases and source pages.
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
            // M3-04 (#137): sites with their spelling variants and mentioning
            // documents — appended at the end so #134's output is untouched.
            for (MandateQa.SiteRow site : qa.sites()) {
                out.printf("site %d: %s%n", site.siteId(), site.siteName());
                for (MandateQa.SiteAliasRow alias : site.aliases()) {
                    out.printf("  alias %s | %s | score %s | %s%n",
                            blankToDash(alias.aliasText()),
                            blankToDash(alias.fileName()),
                            alias.matchScore() != null
                                    ? String.format(Locale.ROOT, "%.2f", alias.matchScore())
                                    : "-",
                            blankToDash(alias.matchReason()));
                }
                StringBuilder files = new StringBuilder();
                for (MandateQa.SiteDocumentRow doc : site.documents()) {
                    if (files.length() > 0) {
                        files.append(", ");
                    }
                    files.append(blankToDash(doc.fileName()));
                }
                out.printf("  documents: %s%n", files.length() == 0 ? "-" : files);
            }
            out.printf("sites: %d%n", qa.sites().size());
            // M3-05 (#138): the mandate's industries and country — appended at the end
            // so earlier tickets' output is untouched.
            for (MandateQa.IndustryRow industry : qa.industries()) {
                out.printf("industry %s%s | %s%s%n",
                        industry.industry(),
                        industry.isNewIndustry() ? " (new)" : "",
                        industry.reason(),
                        industry.pageIndex() != null ? " | page=" + industry.pageIndex() : "");
                if (industry.verbatim() != null) {
                    out.printf("  named: %s%n", industry.verbatim());
                }
            }
            out.printf("industries: %d%n", qa.industries().size());
            if (qa.country() != null) {
                out.printf("country %s | %s%s%n",
                        qa.country().country(),
                        qa.country().reason(),
                        qa.country().pageIndex() != null
                                ? " | page=" + qa.country().pageIndex() : "");
                if (qa.country().verbatim() != null) {
                    out.printf("  named: %s%n", qa.country().verbatim());
                }
            } else {
                out.printf("country: -%n");
            }
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
