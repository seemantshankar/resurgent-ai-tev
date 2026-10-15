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
 * entities with their roles, aliases and source pages, the clauses with their types
 * and slots, the mandate sites with their aliases and mentioning documents, the
 * chapter passages with their facts, the industries and country, and the checklist
 * with its statuses, ask-the-client list and counts — without re-parsing anything.
 * Read-through-API only, like {@code facts}.
 */
@Command(name = "qa",
        description = "Show persisted mandate QA: document dates, stages, entities,"
                + " clauses, sites, passages, industries, country and the checklist")
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
            // M3-08 (#141): clauses with their type, slots and provenance.
            for (MandateQa.ClauseRow clause : qa.clauses()) {
                out.printf("clause %s%s [%s%s] | %s | %s | importance=%s%n",
                        blankToDash(clause.heading()),
                        pageRange(clause),
                        clause.status(),
                        clause.novelty() != null ? ", " + clause.novelty() : "",
                        clause.typeLabel() != null ? clause.typeLabel() : "(untyped)",
                        blankToDash(clause.fileName()),
                        blankToDash(clause.importance()));
                if (clause.summary() != null) {
                    out.printf("  summary: %s%n", clause.summary());
                }
                out.printf("  %s%n", shorten(clause.verbatim()));
                for (MandateQa.ClauseSlotRow slot : clause.slots()) {
                    out.printf("  slot %s: %s%s%n",
                            slot.slotKey(),
                            slot.valueText(),
                            slot.valueNumber() != null
                                    ? String.format(java.util.Locale.ROOT, " (%s%s)",
                                            formatNumber(slot.valueNumber()),
                                            slot.unit() != null ? " " + slot.unit() : "")
                                    : "");
                }
            }
            out.printf("clauses: %d%n", qa.clauses().size());
            // M3-04 (#137): sites with their spelling variants and mentioning
            // documents — appended after #134's output.
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
            // M3-06 (#139): chapter passages from the stored parse output with their
            // per-chapter facts — appended at the end so the earlier sections stand.
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
            // M3-10 (#143): checklist results with their status, page evidence and
            // basis, the ask-the-client list, the unclear items and the persisted
            // counts — appended at the end so the earlier output is untouched.
            for (MandateQa.ChecklistResultRow row : qa.checklist()) {
                out.printf("checklist %s [%s] %s/%s%s | %s%n",
                        row.itemKey(),
                        row.status(),
                        row.level(),
                        row.granularity(),
                        checklistScope(row),
                        row.documentType());
                out.printf("  %s%n", row.reason());
                if (row.pageIndex() != null) {
                    out.printf("  page %s%s%n",
                            row.pageIndex(),
                            row.fileName() != null ? " of " + row.fileName() : "");
                }
                out.printf("  basis %s: %s%n", row.basisKind(), shorten(row.basisText()));
                if (row.note() != null) {
                    out.printf("  note: %s%n", row.note());
                }
            }
            List<MandateQa.ChecklistResultRow> asks = qa.checklist().stream()
                    .filter(r -> "not_provided".equals(r.status()))
                    .toList();
            if (!asks.isEmpty()) {
                out.println("ask the client:");
                for (MandateQa.ChecklistResultRow row : asks) {
                    out.printf("  %s%s: %s%n",
                            row.label(),
                            row.entityName() != null ? " (" + row.entityName() + ")" : "",
                            blankToDash(row.clientRequest()));
                }
            }
            List<MandateQa.ChecklistResultRow> unclear = qa.checklist().stream()
                    .filter(r -> "unclear".equals(r.status()))
                    .toList();
            if (!unclear.isEmpty()) {
                out.println("unclear items:");
                for (MandateQa.ChecklistResultRow row : unclear) {
                    out.printf("  %s%s: %s%n",
                            row.itemKey(),
                            row.entityName() != null ? " (" + row.entityName() + ")" : "",
                            row.reason());
                }
            }
            out.printf("checklist: %d items (%s)%n",
                    qa.checklist().size(),
                    statusCounts(qa.checklist()));
            for (MandateQa.ChecklistSummaryRow summary : qa.checklistSummary()) {
                out.printf("checklist summary %s: %d %s%n",
                        summaryScope(summary), summary.itemCount(), summary.status());
            }
            // M3-12 (#145): the conflicts between documents with both sides and their
            // settlement, and the revisions whose winner M4 chooses — appended at the
            // end so the earlier output is untouched.
            for (MandateQa.ConflictRow row : qa.conflicts()) {
                out.printf("conflict %s [%s] settlement=%s | %s vs %s%n",
                        row.dataPoint(),
                        row.kind(),
                        blankToDash(row.settlement()),
                        row.left().value(),
                        row.right().value());
                out.printf("  %s%n", conflictSides(row));
                if ("rule".equals(row.settlement())) {
                    out.printf("  settled by rule %s (rules %s): chose %s, rejected %s%n",
                            blankToDash(row.ruleId()),
                            blankToDash(row.rulesVersion()),
                            blankToDash(row.chosenValue()),
                            blankToDash(row.rejectedValue()));
                } else if ("ai".equals(row.settlement())) {
                    out.printf("  settled by AI: chose %s, rejected %s%n",
                            blankToDash(row.chosenValue()),
                            blankToDash(row.rejectedValue()));
                } else {
                    out.printf("  open: no settlement, flagged conflicting%n");
                }
                out.printf("  reasoning: %s%n", shorten(row.reasoning()));
            }
            out.printf("conflicts: %d%n", qa.conflicts().size());
            for (MandateQa.ConflictRow row : qa.versionsDiffer()) {
                out.printf("versions differ %s [%s] | newest %s (%s) vs %s (%s)%n",
                        row.dataPoint(),
                        row.kind(),
                        row.left().value(),
                        blankToDash(row.left().fileName()),
                        row.right().value(),
                        blankToDash(row.right().fileName()));
                out.printf("  reasoning: %s%n", shorten(row.reasoning()));
            }
            out.printf("versions differ: %d%n", qa.versionsDiffer().size());
            for (MandateQa.ConflictSkipRow row : qa.conflictSkips()) {
                out.printf("conflict skip %s [%s] cell %s: %s%n",
                        row.dataPoint(), row.kind(), row.fmSource(), row.reason());
            }
            out.printf("conflict skips: %d%n", qa.conflictSkips().size());
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

    /** {@code left: <file pN>, right: <file pN>} — where each conflict value was read. */
    private static String conflictSides(MandateQa.ConflictRow row) {
        return "left: " + conflictSide(row.left()) + ", right: "
                + ("doc-fm".equals(row.kind()) ? "cell " + row.right().quote()
                        : conflictSide(row.right()));
    }

    private static String conflictSide(MandateQa.ConflictSideRow side) {
        StringBuilder text = new StringBuilder(blankToDash(side.fileName()));
        if (side.pageIndex() != null) {
            text.append(" p").append(side.pageIndex());
        }
        return text.toString();
    }

    /** The scope suffix of a checklist row: entity, site or the mandate itself. */
    private static String checklistScope(MandateQa.ChecklistResultRow row) {
        if (row.entityName() != null) {
            return " entity=" + row.entityName();
        }
        if (row.siteName() != null) {
            return " site=" + row.siteName();
        }
        return "";
    }

    /** {@code 0 provided, 2 unclear, 16 not_provided} — the three shippable statuses always. */
    private static String statusCounts(List<MandateQa.ChecklistResultRow> rows) {
        List<String> shippable = List.of("provided", "unclear", "not_provided");
        StringBuilder text = new StringBuilder();
        for (String status : List.of("provided", "unclear", "not_provided", "conflicting",
                "not_applicable")) {
            long count = rows.stream().filter(r -> status.equals(r.status())).count();
            if (!shippable.contains(status) && count == 0) {
                continue;
            }
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(count).append(' ').append(status);
        }
        return text.toString();
    }

    /** {@code overall}, {@code entity <name>} or {@code site <name>} of a summary row. */
    private static String summaryScope(MandateQa.ChecklistSummaryRow summary) {
        return switch (summary.scopeKind()) {
            case "entity" -> summary.entityName() != null
                    ? "entity " + summary.entityName()
                    : "entity " + summary.entityId();
            case "site" -> summary.siteName() != null
                    ? "site " + summary.siteName()
                    : "site " + summary.siteId();
            default -> "overall";
        };
    }

    private static String pagesText(List<Integer> pageIndexes) {
        if (pageIndexes == null || pageIndexes.isEmpty()) {
            return "-";
        }
        return pageIndexes.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    private static String pageRange(MandateQa.ClauseRow clause) {
        if (clause.pageStart() == null) {
            return "";
        }
        return clause.pageEnd() == null || clause.pageEnd().equals(clause.pageStart())
                ? " p" + clause.pageStart()
                : " p" + clause.pageStart() + "-p" + clause.pageEnd();
    }

    private static String shorten(String text) {
        String compact = text.replaceAll("\\s+", " ").strip();
        return compact.length() <= 200 ? compact : compact.substring(0, 200) + "…";
    }

    private static String formatNumber(double value) {
        return value == java.lang.Math.rint(value)
                ? Long.toString((long) value)
                : java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
