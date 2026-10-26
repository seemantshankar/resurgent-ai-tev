package com.resurgent.tev.doc.cli;

import com.resurgent.tev.doc.extract.DocExtractRunner;
import com.resurgent.tev.doc.extract.ExtractionStabilityService;
import com.resurgent.tev.doc.extract.ExtractClient;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.residency.DataResidency;
import com.resurgent.tev.doc.db.MandatePolicyRepository;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code tev-doc stability}: two-run extraction stability check (#157). Uses the small
 * approved sample (one version at a time) — never a full corpus sweep.
 */
@Command(name = "stability",
        description = "Run a two-pass extract stability check for one document version")
public final class DocStabilityCommand implements Callable<Integer> {

    @Option(names = "--mandate", required = true, description = "Mandate id")
    long mandateId;

    @Option(names = "--version", required = true, description = "document_version_id")
    long documentVersionId;

    @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
    Path db;

    @Option(names = "--pack", description = "Pack code (default: classified pack)")
    String pack;

    @Option(names = "--extractor",
            description = "doc-llm-extract|fixture|llama-extract (default: doc-llm-extract)")
    String extractor;

    @Option(names = "--compare-only", description = "Compare two existing parse runs (no extract)")
    boolean compareOnly;

    @Option(names = "--run-a", description = "First parse_run_id (with --compare-only)")
    Long runA;

    @Option(names = "--run-b", description = "Second parse_run_id (with --compare-only)")
    Long runB;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        try {
            DocTypePack packModel = resolvePack();
            ExtractClient client = DocExtractRunner.resolveExtractor(extractor);
            if (localOnly() && client instanceof com.resurgent.tev.doc.extract.LlamaExtractAdapter) {
                err.println("stability rejected: data residency is local_only");
                return 3;
            }
            ExtractionStabilityService.StabilityOutcome outcome;
            if (compareOnly) {
                if (runA == null || runB == null) {
                    err.println("stability rejected: --compare-only requires --run-a and --run-b");
                    return 3;
                }
                try (var workspace = com.resurgent.tev.parser.db.WorkspaceDatabase.open(db)) {
                    outcome = ExtractionStabilityService.compareParseRuns(
                            workspace.connection(),
                            mandateId,
                            documentVersionId,
                            packModel,
                            runA,
                            runB,
                            client.id());
                }
            } else {
                outcome = ExtractionStabilityService.runTwoPassExtract(
                        db, mandateId, documentVersionId, packModel, client);
            }
            out.printf(
                    "stability: compared %d slots, %d unstable (runs %d vs %d)%n",
                    outcome.comparedSlots(),
                    outcome.unstableSlots(),
                    outcome.parseRunIdA(),
                    outcome.parseRunIdB());
            if (outcome.canonicalParseRunId() != null) {
                out.printf("canonical extract run flagged: %d%n", outcome.canonicalParseRunId());
            }
            return outcome.unstableSlots() > 0 ? 0 : 0;
        } catch (IllegalArgumentException e) {
            err.println("stability rejected: " + e.getMessage());
            return 3;
        } catch (Exception e) {
            err.println("stability failed: " + (e.getMessage() != null ? e.getMessage() : e));
            return 1;
        }
    }

    private DocTypePack resolvePack() throws Exception {
        String code = pack != null && !pack.isBlank()
                ? pack.trim()
                : DocExtractRunner.classifiedPackCode(db, documentVersionId);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("--pack is required (classify version first)");
        }
        for (DocTypePack candidate : PackLoader.loadSeedPacks()) {
            if (candidate.code().equals(code)) {
                return candidate;
            }
        }
        Path root = db.toAbsolutePath().getParent().resolve("packs");
        for (DocTypePack candidate : new com.resurgent.tev.doc.induce.PackStore(root).loadAll()) {
            if (candidate.code().equals(code)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unknown pack: " + code);
    }

    private boolean localOnly() throws Exception {
        try (var workspace = com.resurgent.tev.parser.db.WorkspaceDatabase.open(db)) {
            String row = new MandatePolicyRepository(workspace.connection()).dataResidency(mandateId);
            return DataResidency.isLocalOnly(
                    DataResidency.effective(System.getenv(DataResidency.ENV), row));
        }
    }
}
