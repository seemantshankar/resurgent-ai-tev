package com.resurgent.tev.doc.cli;

import com.resurgent.tev.doc.induce.DocInduceRunner;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code tev-doc induce}: automatic industry pack induction (no analyst authoring).
 */
@Command(name = "induce",
        description = "Induce an industry-generic doc type pack from sample documents")
public final class DocInduceCommand implements Callable<Integer> {

    static final class WorkspaceOptions {
        @Option(names = "--mandate", required = true, description = "Mandate id in the paired database")
        long mandateId;

        @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
        Path db;
    }

    @ArgGroup(exclusive = false, multiplicity = "1..*")
    List<WorkspaceOptions> workspaces;

    @Option(names = "--industry", description = "auto|<tag>")
    String industry;

    @Option(names = "--file-role", description = "Filter samples by file_role")
    String fileRole;

    @Option(names = "--doc-type", description = "Restrict to one pack/doc type code")
    String docType;

    @Option(names = "--versions", split = ",",
            description = "Comma-separated document_version_ids to sample (scopes a live run)")
    java.util.List<Long> versions;

    @Option(names = "--dry-run", description = "Print proposed pack + gates without publishing")
    boolean dryRun;

    @Option(names = "--platform",
            description = "Platform registry. Created when this path is new.")
    Path platform;

    @Spec
    CommandSpec spec;

    /** Runs induction and prints gate results / published pack identity. */
    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        try {
            var inputs = workspaces.stream()
                    .map(w -> new DocInduceRunner.MandateWorkspace(w.mandateId, w.db))
                    .toList();
            var results = DocInduceRunner.run(
                    inputs, industry, fileRole, docType, versions, dryRun, platform);
            for (var result : results) {
                if (dryRun) {
                    out.printf("dry-run pack %s@%s (gates %d/%d):%n",
                            result.packCode(), result.packVersion(),
                            result.gatesPassed(), result.gatesTotal());
                    out.println(result.packYaml());
                } else if (result.published()) {
                    out.printf("published pack %s@%s (industry %s, gates %d/%d)%n",
                            result.packCode(), result.packVersion(), result.industryTag(),
                            result.gatesPassed(), result.gatesTotal());
                } else {
                    out.printf("no pack published for %s (gates %d/%d)%n",
                            result.packCode(), result.gatesPassed(), result.gatesTotal());
                }
                for (String dropped : result.droppedFields()) {
                    out.printf("  dropped: %s%n", dropped);
                }
                for (String gate : result.gateMessages()) {
                    out.printf("  gate: %s%n", gate);
                }
                for (String notice : result.notices()) {
                    out.println(notice);
                }
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("induce rejected: " + e.getMessage());
            return 3;
        } catch (Exception e) {
            err.println("induce failed: " + (e.getMessage() != null ? e.getMessage() : e));
            return 1;
        }
    }
}
