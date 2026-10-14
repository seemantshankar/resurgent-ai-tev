package com.resurgent.tev.doc.cli;

import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code tev-doc facts}: retrieve citable facts + evidence without re-parsing PDFs.
 */
@Command(name = "facts",
        description = "Retrieve extracted facts with evidence (no PDF re-parse)")
public final class DocFactsCommand implements Callable<Integer> {

    @Option(names = "--mandate", required = true, description = "Mandate id")
    long mandateId;

    @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
    Path db;

    @Option(names = "--path", description = "Ontology path prefix filter")
    String path;

    @Option(names = "--document", description = "Logical document id filter")
    Long documentId;

    @Option(names = "--kind", description = "value_kind filter (money, date, ...)")
    String kind;

    @Option(names = "--current", description = "Only is_current=1 (default true)")
    boolean current = true;

    @Option(names = "--json", description = "Machine-readable JSON export")
    boolean json;

    @Spec
    CommandSpec spec;

    /** Queries facts and prints text or JSON with evidence. */
    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        try {
            FactQuery query = FactQuery.forMandate(mandateId)
                    .withPathPrefix(path)
                    .withValueKind(kind);
            if (documentId != null) {
                query = query.withDocument(documentId);
            }
            if (!current) {
                query = query.includingHistory();
            }
            List<FactRow> rows = com.resurgent.tev.doc.db.FactQueryRunner.query(db, query);
            if (json) {
                out.println(com.resurgent.tev.doc.query.FactJson.write(rows));
            } else {
                for (FactRow row : rows) {
                    out.printf("%s | %s | %s%n",
                            row.ontologyPath() != null ? row.ontologyPath() : "(unbound)",
                            row.fieldKey() != null ? row.fieldKey() : row.valueKind(),
                            row.valueText() != null ? row.valueText() : String.valueOf(row.valueNumber()));
                    for (FactRow.EvidenceRow ev : row.evidence()) {
                        out.printf("    [%s] p%s %s%n",
                                ev.role(),
                                ev.pageIndex() != null ? ev.pageIndex() : "?",
                                ev.verbatim());
                    }
                }
                out.printf("(%d facts)%n", rows.size());
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("facts rejected: " + e.getMessage());
            return 3;
        } catch (Exception e) {
            err.println("facts failed: " + (e.getMessage() != null ? e.getMessage() : e));
            return 1;
        }
    }
}
