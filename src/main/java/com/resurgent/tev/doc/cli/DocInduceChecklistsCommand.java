package com.resurgent.tev.doc.cli;

import com.resurgent.tev.doc.induce.ChecklistLearner;
import com.resurgent.tev.doc.induce.DocInduceChecklistsRunner;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmEnvironment;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Automatic client-set learning; repeat the --mandate/--db pair for each workspace. */
@Command(name = "induce-checklists", mixinStandardHelpOptions = true,
        description = "Learn versioned industry and country checklists from client document sets")
public final class DocInduceChecklistsCommand implements Callable<Integer> {
    static final class WorkspaceOptions {
        @Option(names = "--mandate", required = true, description = "Mandate id in the paired database")
        long mandateId;
        @Option(names = "--db", required = true, description = "Existing assessed workspace database")
        Path db;
    }

    @ArgGroup(exclusive = false, multiplicity = "1..*")
    List<WorkspaceOptions> workspaces;

    @Option(names = "--min-observed-mandates", description = "Minimum providing mandates (default: 2); also needs a strict majority")
    int minObserved = ChecklistLearner.DEFAULT_MIN_OBSERVED_MANDATES;
    @Option(names = "--min-missing-mandates", description = "Minimum mandates for a never-provided item to become optional (default: 2)")
    int minMissing = ChecklistLearner.DEFAULT_MIN_MISSING_MANDATES;
    @Option(names = "--min-industries-for-spine", description = "Minimum distinct industries before LLM independence judgement (default: 2, minimum: 2)")
    int minIndustries = ChecklistLearner.DEFAULT_MIN_INDUSTRIES_FOR_SPINE;

    @Spec CommandSpec spec;
    private final DocLlmClient forcedClient;
    private final boolean clientForced;

    public DocInduceChecklistsCommand() {
        forcedClient = null;
        clientForced = false;
    }

    /** External test seam: null explicitly selects offline, without reading credentials. */
    public DocInduceChecklistsCommand(DocLlmClient client) {
        forcedClient = client;
        clientForced = true;
    }

    @Override
    public Integer call() {
        try {
            var config = new ChecklistLearner.Config(minObserved, minMissing, minIndustries);
            var inputs = workspaces.stream().map(w ->
                    new DocInduceChecklistsRunner.MandateWorkspace(w.mandateId, w.db)).toList();
            var result = DocInduceChecklistsRunner.run(inputs,
                    clientForced ? forcedClient : DocLlmEnvironment.clientOrNull(), config);
            var out = spec.commandLine().getOut();
            for (var update : result.updates()) {
                out.printf("learned %s@%s (%s -> %s)%n", update.code(), update.newVersion(),
                        update.previousVersion(), update.newVersion());
                for (var change : update.changes()) {
                    out.printf("  %s %s: %s%n", change.kind(), change.itemKey(), change.reason());
                }
            }
            out.printf("checklist learning: %d mandates, %d definitions updated%n",
                    result.mandatesRead(), result.updates().size());
            return 0;
        } catch (IllegalArgumentException e) {
            spec.commandLine().getErr().println("induce-checklists rejected: " + e.getMessage());
            return 3;
        } catch (Exception e) {
            spec.commandLine().getErr().println("induce-checklists failed: " + e.getMessage());
            return 1;
        }
    }
}
