package com.resurgent.tev.doc.cli;

import com.resurgent.tev.doc.embed.EmbeddingClients;
import com.resurgent.tev.doc.embed.SimilaritySearch;
import com.resurgent.tev.doc.query.MandateQa;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code tev-doc similar}: top-K clauses or passages in a mandate, ranked by
 * cosine similarity to a free-text query or to another clause/passage id.
 * Each hit names the document, the page, and a verbatim snippet.
 */
@Command(name = "similar",
        description = "Find the top-K similar clauses or passages in a mandate")
public final class DocSimilarCommand implements Callable<Integer> {

    private static final int SNIPPET_LIMIT = 240;

    @Option(names = "--mandate", required = true, description = "Mandate id")
    long mandateId;

    @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
    Path db;

    @Option(names = "--kind", required = true, description = "clause or passage")
    String kind;

    @Option(names = "--text", description = "Free-text query")
    String text;

    @Option(names = "--id", description = "Reference clause or passage id")
    Long id;

    @Option(names = "--top-k", defaultValue = "5", description = "How many hits to return (default 5)")
    int topK;

    @Spec
    CommandSpec spec;

    /** Runs the search and prints hits, best first. */
    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        try {
            List<MandateQa.SimilarHit> hits;
            try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
                hits = SimilaritySearch.topK(workspace.connection(), mandateId, kind, text, id,
                        topK, EmbeddingClients.forAssessment(workspace.connection(), mandateId));
            }
            for (MandateQa.SimilarHit hit : hits) {
                out.printf(Locale.ROOT, "%s %d score=%.3f | %s | %s | page %s%n",
                        hit.kind(), hit.id(), hit.score(),
                        blankToDash(hit.logicalName()),
                        blankToDash(hit.fileName()),
                        hit.page() == null ? "-" : hit.page().toString());
                out.printf("  %s%n", shorten(hit.snippet()));
            }
            out.printf(Locale.ROOT, "similar: %d%n", hits.size());
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("similar rejected: " + e.getMessage());
            return 3;
        } catch (Exception e) {
            err.println("similar failed: " + (e.getMessage() != null ? e.getMessage() : e));
            return 1;
        }
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= SNIPPET_LIMIT ? compact : compact.substring(0, SNIPPET_LIMIT) + "…";
    }
}
