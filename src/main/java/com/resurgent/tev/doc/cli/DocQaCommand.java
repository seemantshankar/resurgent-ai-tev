package com.resurgent.tev.doc.cli;

import com.resurgent.tev.doc.db.MandateQaQueryRunner;
import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.doc.query.MandateQaQuery;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code tev-doc qa}: print the persisted mandate assessment results — per-document
 * document date, precision, source and upload date, plus the assessment runs — without
 * re-parsing anything. Read-through-API only, like {@code facts}.
 */
@Command(name = "qa",
        description = "Show persisted mandate QA: document dates and assessment stages")
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
            for (MandateQa.PassageRow row : qa.passages()) {
                out.printf("passage %d | %s | topic=%s | pages %d-%d | page=%d%n",
                        row.passageIndex(),
                        blankToDash(row.heading()),
                        blankToDash(row.topic()),
                        row.pageFrom(),
                        row.pageTo(),
                        row.pageIndex());
                if (row.summary() != null) {
                    out.printf("  summary: %s%n", row.summary());
                }
                if (row.verbatim() != null) {
                    out.printf("  verbatim: %s%n",
                            com.resurgent.tev.doc.db.AssessmentRepository
                                    .normalizeWhitespace(row.verbatim()));
                }
                for (MandateQa.PassageFactRow fact : row.facts()) {
                    out.printf("  fact %s%s = %s%s%n",
                            fact.fieldKey(),
                            fact.lineKey() != null ? " [" + fact.lineKey() + "]" : "",
                            fact.valueText() != null
                                    ? fact.valueText()
                                    : fact.valueNumber() != null
                                            ? String.valueOf(fact.valueNumber())
                                            : fact.valueDate(),
                            fact.unit() != null ? " " + fact.unit() : "");
                    for (int i = 0; i < fact.evidenceQuotes().size(); i++) {
                        Integer page = i < fact.evidencePages().size()
                                ? fact.evidencePages().get(i)
                                : null;
                        out.printf("    quote%s: %s%n",
                                page != null ? " (page " + page + ")" : "",
                                fact.evidenceQuotes().get(i));
                    }
                }
            }
            out.printf("passages: %d%n", qa.passages().size());
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
}
