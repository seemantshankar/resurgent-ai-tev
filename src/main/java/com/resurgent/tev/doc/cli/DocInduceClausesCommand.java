package com.resurgent.tev.doc.cli;

import com.resurgent.tev.doc.induce.ClauseTypePromoter;
import com.resurgent.tev.doc.induce.DocInduceClausesRunner;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmEnvironment;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code tev-doc induce-clauses}: induction-family clause-type learning (M3-09).
 * Groups the open clause proposals by label normalisation and publishes a new pack
 * version for every recurring group that passes the M2 consensus and quality gates.
 * No human approval step: a one-off group stays open and is reported with its reason.
 */
@Command(name = "induce-clauses",
        description = "Learn clause types from recurring open clause proposals (auto-promotion)")
public final class DocInduceClausesCommand implements Callable<Integer> {

    @Option(names = "--mandate", required = true, description = "Mandate id")
    long mandateId;

    @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
    Path db;

    @Spec
    CommandSpec spec;

    private final DocLlmClient forcedClient;
    private final boolean clientForced;

    /** Picocli entry: resolves the live LLM seam from the environment. */
    public DocInduceClausesCommand() {
        this.forcedClient = null;
        this.clientForced = false;
    }

    /**
     * Test seam: forces the client ({@code null} = explicitly offline) instead of
     * {@link DocLlmEnvironment}, so the default suite never needs a network.
     */
    public DocInduceClausesCommand(DocLlmClient client) {
        this.forcedClient = client;
        this.clientForced = true;
    }

    /** Runs clause promotion and prints what was learned / kept open. */
    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        try {
            DocLlmClient client = clientForced ? forcedClient : DocLlmEnvironment.clientOrNull();
            List<DocInduceClausesRunner.Result> results =
                    DocInduceClausesRunner.run(mandateId, db, client);
            int promotedTotal = 0;
            int keptOpenTotal = 0;
            for (DocInduceClausesRunner.Result result : results) {
                for (ClauseTypePromoter.PromotedType promoted : result.promoted()) {
                    out.printf("promoted clause type %s into %s@%s from %d document(s)%n",
                            promoted.key(), promoted.packCode(), promoted.packVersion(),
                            promoted.sourceDocumentIds().size());
                }
                for (ClauseTypePromoter.SkippedGroup skipped : result.skipped()) {
                    out.printf("kept open: %s (%s)%n", skipped.key(), skipped.reason());
                }
                promotedTotal += result.promoted().size();
                keptOpenTotal += result.skipped().size();
            }
            out.printf("promotion: %d promoted, %d kept open%n", promotedTotal, keptOpenTotal);
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("induce-clauses rejected: " + e.getMessage());
            return 3;
        } catch (Exception e) {
            err.println("induce-clauses failed: "
                    + (e.getMessage() != null ? e.getMessage() : e));
            return 1;
        }
    }
}
