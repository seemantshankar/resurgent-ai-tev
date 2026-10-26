package com.resurgent.tev.doc.bind;

import com.resurgent.tev.doc.llm.DocLlmEnvironment;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.doc.pack.PackLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI facade for pack-path bind. Delegates to {@link PackPathBinder}.
 */
public final class DocBindRunner {

    private DocBindRunner() {}

    /**
     * Runs bind for an extract parse_run.
     *
     * @param mandateId mandate scope
     * @param parseRunId extract parse_run id
     * @param db workspace database
     * @param packCode pack code used at extract (required for M2)
     * @return bind summary
     * @throws Exception on runtime failure
     */
    public static Result run(long mandateId, long parseRunId, Path db, String packCode)
            throws Exception {
        return run(mandateId, parseRunId, db, packCode, null);
    }

    /**
     * Same as {@link #run(long, long, Path, String)}. {@code platform} is the
     * registry the command was given, so the document model honours that ceiling.
     */
    public static Result run(long mandateId, long parseRunId, Path db, String packCode,
            Path platform) throws Exception {
        if (packCode == null || packCode.isBlank()) {
            throw new IllegalArgumentException("--pack is required for bind");
        }
        DocTypePack pack = resolvePack(db, packCode);
        PackPathBinder binder = new PackPathBinder();
        PackPathBinder.BindOutcome outcome = binder.bind(db, parseRunId, mandateId, pack,
                DocLlmEnvironment.clientOrNull(db, mandateId, platform, 0));
        List<String> paths = new ArrayList<>();
        for (PackField path : pack.fields()) {
            paths.add(path.path());
        }
        return new Result(outcome.bound(), outcome.unbound(), outcome.notApplicable(), paths);
    }

    private static DocTypePack resolvePack(Path db, String packCode) throws Exception {
        String code = packCode.trim();
        for (DocTypePack pack : loadAllPacks(db)) {
            if (pack.code().equals(code)) {
                return pack;
            }
        }
        throw new IllegalArgumentException("unknown pack: " + code);
    }

    /** Seed packs plus every pack published under {@code {db-parent}/packs}. */
    private static List<DocTypePack> loadAllPacks(Path db) throws Exception {
        java.util.Map<String, DocTypePack> byCode = new java.util.LinkedHashMap<>();
        for (DocTypePack pack : PackLoader.loadSeedPacks()) {
            byCode.put(pack.code(), pack);
        }
        Path root = db.toAbsolutePath().getParent() == null
                ? Path.of("packs")
                : db.toAbsolutePath().getParent().resolve("packs");
        try {
            for (DocTypePack pack : new com.resurgent.tev.doc.induce.PackStore(root).loadAll()) {
                byCode.put(pack.code(), pack);
            }
        } catch (RuntimeException e) {
            System.err.println("[doc-bind] ignoring pack store " + root + ": " + e.getMessage());
        }
        return List.copyOf(byCode.values());
    }

    /**
     * Bind summary.
     *
     * @param bound bound fact count
     * @param unbound unbound fact count
     * @param notApplicable not_applicable count
     * @param paths ontology paths touched
     */
    public record Result(int bound, int unbound, int notApplicable, List<String> paths) {
    }
}
