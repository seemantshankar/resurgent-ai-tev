package com.resurgent.tev.parser.classify;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.parser.db.CellReading;
import com.resurgent.tev.parser.db.FormulaLink;
import com.resurgent.tev.parser.db.InterpretationCellView;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.db.WorksheetRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Offline replay of Layer B's typing on a copy of a finished workspace, with no network: the
 * decision model is a recording stub that is never sure (by default), and the chat model answers nothing, so
 * the cells left untyped are exactly the cells that reached the decision model. The report says
 * how many there were and how they wait on each other, which is what the round-based pass 2 is
 * sized from.
 *
 * <p>Skipped unless {@code -Dtev.replay.db=<path to a workspace db>} is given. Optional:
 * {@code -Dtev.replay.scope="B  S"} (comma separated; default {@code B  S}; {@code *} is the whole workbook) and
 * {@code -Dtev.replay.out=<file>} for the report, and {@code -Dtev.replay.model=confident} for a decision
 * model that settles whatever it is asked. The database and the learned dictionary are
 * copied first, so neither the workspace nor {@code ~/.tev-parser} is touched.
 */
class DecisionPoolReplayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temp;

    /** What the model was asked, so the pool can be told apart from cells settled by labels. */
    private record Asked(String coord, String display, String formula) {}

    /** One live answer, with what the state it was asked from carried. */
    private record Answer(CellDecisionClient.Decision decision, boolean hadInputs, boolean askedScale,
            String sheet, String inputs) {}

    /**
     * A/B of the decision state against the stored run, with the live decision model: each variant
     * asks about the same cells (its answers are recorded but never applied), and the answers are
     * compared with what the stored run says. The stored run is itself a model's guess, so this
     * measures agreement, not truth; read the disagreements.
     *
     * <p>Needs {@code -Dtev.replay.db}, {@code -Dtev.replay.live=true} and the OpenRouter key in
     * {@code .env}. Variants: {@code legacy} (the state and wording as they were), {@code facts}
     * (adds sheet and stated scale, new wording), {@code inputs} (adds the kinds of what a formula reads).
     */
    @Test
    void compareDecisionStateVariantsOnTheLiveModel() throws Exception {
        String source = System.getProperty("tev.replay.db");
        assumeTrue(source != null && "true".equals(System.getProperty("tev.replay.live")),
                "set -Dtev.replay.db and -Dtev.replay.live=true to ask the live decision model");
        Map<String, String> env = LlmEnvironment.load();
        String modelId = LlmEnvironment.decisionModelId(env);
        String key = env.get(LlmEnvironment.API_KEY);
        assumeTrue(key != null && !key.isBlank() && modelId != null, "no OpenRouter key or decision model in .env");

        Path dictionaries = temp.resolve("dictionaries");
        Files.createDirectories(dictionaries);
        Path home = Path.of(System.getProperty("user.home"), ".tev-parser", "dictionaries");
        if (Files.isDirectory(home)) {
            try (var files = Files.list(home)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    if (Files.isRegularFile(file)) {
                        Files.copy(file, dictionaries.resolve(file.getFileName()));
                    }
                }
            }
        }
        System.setProperty(DynamicKindTokens.DIR_PROPERTY, dictionaries.toString());
        System.setProperty(DynamicKindTokens.LEARN_PROPERTY, "false");
        ClassifierLlm answersNothing = new ClassifierLlm() {
            @Override public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) { return List.of(); }
            @Override public LayerAJudgment classifyLayerA(LayerAPrompt prompt) { return null; }
        };

        Map<String, Map<String, Answer>> byVariant = new java.util.LinkedHashMap<>();
        Map<String, int[]> fanout = new HashMap<>(); // key -> {cells downstream, 1 if any is on another sheet, direct readers}
        Map<String, CellReading> stored = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (String variant : System.getProperty("tev.replay.variants", "legacy,facts,inputs").split(",")) {
            Path db = temp.resolve("live_" + variant + ".db");
            Files.copy(Path.of(source), db, StandardCopyOption.REPLACE_EXISTING);
            try (WorkspaceDatabase database = WorkspaceDatabase.open(db)) {
                WorkspaceRepository repo = new WorkspaceRepository(database.connection());
                long parseRunId;
                try (var st = database.connection().createStatement();
                        ResultSet rs = st.executeQuery("select max(parse_run_id) from parse_run")) {
                    rs.next();
                    parseRunId = rs.getLong(1);
                }
                List<String> scopeNames = List.of(System.getProperty("tev.replay.scope", "B  S").split(","));
                Set<Long> scope = "*".equals(scopeNames.get(0).trim())
                        ? null
                        : new ClassifyService(answersNothing).withSheetScope(scopeNames).resolveScope(repo, parseRunId);
                if (stored.isEmpty()) {
                    Map<Long, InterpretationCellView> byId = new HashMap<>();
                    repo.selectInterpretationCellsForParseRun(parseRunId).forEach(c -> byId.put(c.cellId(), c));
                    for (CellReading r : repo.selectCellReadingsForParseRun(parseRunId)) {
                        InterpretationCellView c = byId.get(r.cellId());
                        if (c != null && (scope == null || scope.contains(c.worksheetId()))) {
                            String k = oracleKey(c.coord(), c.displayValue() == null ? "" : c.displayValue(),
                                    c.formulaText() == null ? "" : c.formulaText());
                            if (stored.put(k, r) != null) {
                                ambiguous.add(k);
                            }
                        }
                    }
                }
                OpenRouterDecisionClient real = new OpenRouterDecisionClient(key, modelId);
                if ("legacy".equals(variant)) {
                    real.withLegacyWording();
                }
                Map<String, Answer> answers = new java.util.concurrent.ConcurrentHashMap<>();
                CellDecisionClient recording = new CellDecisionClient() {
                    @Override public Decision decide(String state) throws Exception {
                        return decide(state, true);
                    }
                    @Override public Decision decide(String state, boolean askScale) throws Exception {
                        JsonNode s = MAPPER.readTree(state);
                        Decision d = real.decide(state, askScale);
                        answers.put(oracleKey(s.path("cell").path("coord").asText(),
                                s.path("cell").path("display").asText(), s.path("cell").path("formula").asText("")),
                                new Answer(d, s.has("inputs"), askScale, s.path("sheet").asText(""),
                                        s.has("inputs") ? s.get("inputs").toString() : ""));
                        return new Decision("money", 0.0, "unit", 0.0); // asked, never applied
                    }
                };
                boolean facts = !"legacy".equals(variant);
                LlmStats.GLOBAL.reset();
                new CellReadingWriter()
                        .withTuning(new ClassifyTuning(15, 10, 8), scope)
                        .withDecisionModel(recording)
                        .withDecisionStateFacts(facts, facts, "inputs".equals(variant))
                        .replace(repo, parseRunId, answersNothing);
                byVariant.put(variant, answers);
                if (fanout.isEmpty()) {
                    computeFanout(repo, parseRunId, scope, answers.keySet(), fanout);
                }
                System.err.println("[live] " + variant + ": " + real.stats());
            }
        }
        String answersFile = System.getProperty("tev.replay.answers");
        if (answersFile != null && !answersFile.isBlank()) {
            // One line per answered cell (the last variant run), to join with the workbook's graph elsewhere.
            Map<String, Answer> last = byVariant.get(new ArrayList<>(byVariant.keySet()).get(byVariant.size() - 1));
            List<String> lines = new ArrayList<>();
            lines.add(String.join("\t", "sheet", "coord", "display", "formula", "asked_scale", "had_inputs",
                    "d1_kind", "d1_kind_conf", "d1_scale", "d1_scale_conf", "stored_kind", "stored_scale"));
            for (var e : last.entrySet()) {
                CellReading was = stored.get(e.getKey());
                if (was == null || ambiguous.contains(e.getKey())) {
                    continue;
                }
                String[] parts = e.getKey().split("\\|", 3);
                Answer a = e.getValue();
                lines.add(String.join("\t", a.sheet(), parts[0], parts[1], parts[2],
                        String.valueOf(a.askedScale()), String.valueOf(a.hadInputs()),
                        a.decision().kind(), String.valueOf(a.decision().kindConfidence()),
                        String.valueOf(a.decision().scale()), String.valueOf(a.decision().scaleConfidence()),
                        String.valueOf(was.kind()), String.valueOf(was.scale())));
            }
            Files.write(Path.of(answersFile), lines);
        }
        StringBuilder report = new StringBuilder(liveReport(byVariant, stored, ambiguous));
        report.append(fanoutReport(byVariant.get(byVariant.containsKey("inputs") ? "inputs" : byVariant.keySet().iterator().next()),
                stored, ambiguous, fanout));
        String out = System.getProperty("tev.replay.out");
        if (out != null && !out.isBlank()) {
            Files.writeString(Path.of(out), report.toString());
        }
        System.err.print(report);
    }

    private static String liveReport(
            Map<String, Map<String, Answer>> byVariant, Map<String, CellReading> stored, Set<String> ambiguous) {
        // The cells every variant answered, and the part of them whose state carried inputs.
        Set<String> common = null;
        for (Map<String, Answer> answers : byVariant.values()) {
            common = common == null ? new HashSet<>(answers.keySet()) : intersect(common, answers.keySet());
        }
        Set<String> withInputs = new HashSet<>();
        Map<String, Answer> inputsRun = byVariant.get("inputs");
        if (inputsRun != null) {
            common.forEach(k -> {
                if (inputsRun.get(k).hadInputs()) {
                    withInputs.add(k);
                }
            });
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[live] cells every variant answered: %d (typed in the stored run: %d); "
                + "with inputs in the inputs variant: %d%n", common.size(),
                common.stream().filter(k -> stored.get(k) != null && stored.get(k).kind() != null && !ambiguous.contains(k)).count(),
                withInputs.size()));
        sb.append(String.format("[live] %-8s %-14s %6s %10s %12s %14s%n", "variant", "cells", "n", "settled@.90",
                "kind agrees", "agree|settled"));
        for (var entry : byVariant.entrySet()) {
            for (var subset : List.of(Map.entry("all", common), Map.entry("with inputs", (Set<String>) withInputs))) {
                if (subset.getValue().isEmpty()) {
                    continue;
                }
                int n = 0;
                int settled = 0;
                int kindAgree = 0;
                int settledAgree = 0;
                for (String k : subset.getValue()) {
                    CellReading was = stored.get(k);
                    if (was == null || was.kind() == null || ambiguous.contains(k)) {
                        continue;
                    }
                    Answer a = entry.getValue().get(k);
                    n++;
                    boolean kindSame = a.decision().kind().equals(was.kind());
                    boolean scaleOk = !a.askedScale() || !"money".equals(was.kind()) || was.scale() == null
                            || java.util.Objects.equals(a.decision().scale(), was.scale());
                    boolean isSettled = a.decision().kindConfidence() >= 0.90
                            && (!a.askedScale() || !"money".equals(a.decision().kind())
                                    || a.decision().scaleConfidence() >= 0.90);
                    if (kindSame) {
                        kindAgree++;
                    }
                    if (isSettled) {
                        settled++;
                        if (kindSame && scaleOk) {
                            settledAgree++;
                        }
                    }
                }
                if (n == 0) {
                    continue;
                }
                sb.append(String.format(Locale.ROOT, "[live] %-8s %-14s %6d %9.1f%% %11.1f%% %13.1f%%%n",
                        entry.getKey(), subset.getKey(), n, 100.0 * settled / n, 100.0 * kindAgree / n,
                        settled == 0 ? 0.0 : 100.0 * settledAgree / settled));
            }
        }
        // What each threshold would settle on the inputs variant, and how often those answers agree.
        if (inputsRun != null) {
            sb.append(String.format("[live] threshold sweep, inputs variant (agreement is with the stored run, "
                    + "which is itself a guess)%n"));
            sb.append(String.format("[live] %-9s %-12s %6s %9s %10s%n", "threshold", "cells", "n", "settled", "agree"));
            for (var subset : List.of(Map.entry("all", common), Map.entry("with inputs", (Set<String>) withInputs))) {
                for (double cut : new double[] {0.70, 0.80, 0.85, 0.90, 0.95}) {
                    int n = 0;
                    int settled = 0;
                    int agree = 0;
                    for (String k : subset.getValue()) {
                        CellReading was = stored.get(k);
                        if (was == null || was.kind() == null || ambiguous.contains(k)) {
                            continue;
                        }
                        Answer a = inputsRun.get(k);
                        n++;
                        if (settledAt(a, cut)) {
                            settled++;
                            boolean scaleOk = !a.askedScale() || !"money".equals(was.kind()) || was.scale() == null
                                    || java.util.Objects.equals(a.decision().scale(), was.scale());
                            if (a.decision().kind().equals(was.kind()) && scaleOk) {
                                agree++;
                            }
                        }
                    }
                    if (n > 0) {
                        sb.append(String.format(Locale.ROOT, "[live] %-9.2f %-12s %6d %8.1f%% %9.1f%%%n", cut,
                                subset.getKey(), n, 100.0 * settled / n, settled == 0 ? 0.0 : 100.0 * agree / settled));
                    }
                }
            }
        }
        // Settled only below 0.90 and differing from the stored run: what a lower threshold would add.
        if (inputsRun != null) {
            sb.append("[live] answers a lower threshold adds (confidence 0.70 to 0.90) that differ from the stored run:\n");
            int shown = 0;
            for (String k : common) {
                CellReading was = stored.get(k);
                Answer a = inputsRun.get(k);
                if (was == null || was.kind() == null || ambiguous.contains(k)
                        || settledAt(a, 0.90) || !settledAt(a, 0.70)) {
                    continue;
                }
                boolean kindDiffers = !a.decision().kind().equals(was.kind());
                boolean scaleDiffers = a.askedScale() && "money".equals(was.kind()) && was.scale() != null
                        && !was.scale().equals(a.decision().scale());
                if (!kindDiffers && !scaleDiffers) {
                    continue;
                }
                sb.append(String.format(Locale.ROOT, "[live] ADDED %s!%s | %s | stored %s/%s | D1 %s (%.2f) %s (%.2f) | inputs %s%n",
                        a.sheet(), k.split("\\|")[0], k.split("\\|", 3)[2], was.kind(), was.scale(),
                        a.decision().kind(), a.decision().kindConfidence(),
                        a.askedScale() ? a.decision().scale() : "-", a.askedScale() ? a.decision().scaleConfidence() : 0.0,
                        a.inputs().isEmpty() ? "none" : a.inputs()));
                shown++;
            }
            sb.append("[live] added disagreements listed: ").append(shown).append('\n');
        }
        // The settled answers that differ from the stored run, so a person can say which is right.
        if (inputsRun != null) {
            int shown = 0;
            for (String k : withInputs) {
                CellReading was = stored.get(k);
                Answer a = inputsRun.get(k);
                if (was == null || was.kind() == null || ambiguous.contains(k)
                        || a.decision().kindConfidence() < 0.90) {
                    continue;
                }
                boolean kindDiffers = !a.decision().kind().equals(was.kind());
                boolean scaleDiffers = a.askedScale() && "money".equals(was.kind()) && was.scale() != null
                        && a.decision().scaleConfidence() >= 0.90 && !was.scale().equals(a.decision().scale());
                if (!kindDiffers && !scaleDiffers) {
                    continue;
                }
                Answer base = byVariant.containsKey("facts") ? byVariant.get("facts").get(k) : null;
                sb.append("[live] settled with inputs, differs from stored: ").append(k.replace("|", "  |  "))
                        .append("  stored ").append(was.kind()).append('/').append(was.scale())
                        .append("  inputs-variant ").append(a.decision().kind()).append('/').append(a.decision().scale())
                        .append(String.format(Locale.ROOT, " (%.2f)", a.decision().kindConfidence()))
                        .append(base == null ? "" : "  facts-variant " + base.decision().kind()
                                + String.format(Locale.ROOT, " (%.2f)", base.decision().kindConfidence()))
                        .append('\n');
                if (++shown >= 30) {
                    break;
                }
            }
        }
        return sb.toString();
    }

    /**
     * For each answered cell: how many cells read it, directly or through others, and whether any of
     * them is on another sheet. A cell whose influence stays inside one small calculation is the
     * "scratch" the person reading the sheet would call a check, a counter or a scenario.
     */
    private static void computeFanout(
            WorkspaceRepository repo, long parseRunId, Set<Long> scope, Set<String> keys, Map<String, int[]> out)
            throws Exception {
        Map<Long, InterpretationCellView> byId = new HashMap<>();
        Map<String, InterpretationCellView> byKey = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (InterpretationCellView c : repo.selectInterpretationCellsForParseRun(parseRunId)) {
            byId.put(c.cellId(), c);
            if (scope != null && !scope.contains(c.worksheetId())) {
                continue;
            }
            String k = oracleKey(c.coord(), c.displayValue() == null ? "" : c.displayValue(),
                    c.formulaText() == null ? "" : c.formulaText());
            if (!seen.add(k)) {
                byKey.remove(k); // ambiguous: left out
            } else {
                byKey.put(k, c);
            }
        }
        Map<Long, Set<Long>> readers = new HashMap<>();
        for (FormulaLink l : repo.selectFormulaLinksForParseRun(parseRunId)) {
            readers.computeIfAbsent(l.toCellId(), x -> new HashSet<>()).add(l.fromCellId());
        }
        for (String k : keys) {
            InterpretationCellView c = byKey.get(k);
            if (c == null) {
                continue;
            }
            Set<Long> reached = new HashSet<>();
            java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>(readers.getOrDefault(c.cellId(), Set.of()));
            boolean otherSheet = false;
            while (!queue.isEmpty()) {
                long id = queue.poll();
                if (!reached.add(id)) {
                    continue;
                }
                InterpretationCellView r = byId.get(id);
                if (r != null && r.worksheetId() != c.worksheetId()) {
                    otherSheet = true;
                }
                queue.addAll(readers.getOrDefault(id, Set.of()));
            }
            out.put(k, new int[] {reached.size(), otherSheet ? 1 : 0, readers.getOrDefault(c.cellId(), Set.of()).size()});
        }
    }

    /** Does D1's disagreement with the stored run concentrate in cells whose influence stays small and local? */
    private static String fanoutReport(
            Map<String, Answer> run, Map<String, CellReading> stored, Set<String> ambiguous, Map<String, int[]> fanout) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[fanout] D1 answers by how far the cell's influence reaches "
                + "(stored run is a guess: disagreement is an upper bound on D1's error)%n"));
        sb.append(String.format("[fanout] %-26s %5s %9s %9s %10s %10s%n", "group", "n", "settled.9", "settled.7",
                "disagree.9", "disagree.7"));
        String[] names = {"reaches another sheet", "stays on its sheet, >5 cells", "stays on its sheet, 1-5 cells", "read by nothing"};
        for (String name : names) {
            int n = 0, s9 = 0, s7 = 0, d9 = 0, d7 = 0;
            for (var e : run.entrySet()) {
                int[] f = fanout.get(e.getKey());
                CellReading was = stored.get(e.getKey());
                if (f == null || was == null || was.kind() == null || ambiguous.contains(e.getKey())) {
                    continue;
                }
                String group = f[1] == 1 ? names[0] : f[0] > 5 ? names[1] : f[0] >= 1 ? names[2] : names[3];
                if (!group.equals(name)) {
                    continue;
                }
                Answer a = e.getValue();
                n++;
                boolean differs = !a.decision().kind().equals(was.kind())
                        || (a.askedScale() && "money".equals(was.kind()) && was.scale() != null
                                && !was.scale().equals(a.decision().scale()));
                if (settledAt(a, 0.90)) {
                    s9++;
                    d9 += differs ? 1 : 0;
                }
                if (settledAt(a, 0.70)) {
                    s7++;
                    d7 += differs ? 1 : 0;
                }
            }
            if (n > 0) {
                sb.append(String.format(Locale.ROOT, "[fanout] %-26s %5d %4d %4.0f%% %4d %4.0f%% %5d/%-3d %4.1f%% %5d/%-3d %4.1f%%%n",
                        name, n, s9, 100.0 * s9 / n, s7, 100.0 * s7 / n, d9, s9, s9 == 0 ? 0.0 : 100.0 * d9 / s9,
                        d7, s7, s7 == 0 ? 0.0 : 100.0 * d7 / s7));
            }
        }
        // The disagreements that sit in cells the model depends on: these are the ones that matter most.
        sb.append("[fanout] settled >= 0.70, differing from the stored run, in cells that reach another sheet:\n");
        int shown = 0;
        for (var e : run.entrySet()) {
            int[] f = fanout.get(e.getKey());
            CellReading was = stored.get(e.getKey());
            Answer a = e.getValue();
            if (f == null || f[1] != 1 || was == null || was.kind() == null || ambiguous.contains(e.getKey())
                    || !settledAt(a, 0.70)) {
                continue;
            }
            boolean differs = !a.decision().kind().equals(was.kind())
                    || (a.askedScale() && "money".equals(was.kind()) && was.scale() != null
                            && !was.scale().equals(a.decision().scale()));
            if (differs && shown++ < 40) {
                sb.append(String.format(Locale.ROOT, "[fanout]   %s!%s | %s | stored %s/%s | D1 %s (%.2f) %s (%.2f) | reaches %d cells%n",
                        a.sheet(), e.getKey().split("\\|")[0], e.getKey().split("\\|", 3)[2], was.kind(), was.scale(),
                        a.decision().kind(), a.decision().kindConfidence(),
                        a.askedScale() ? a.decision().scale() : "-", a.askedScale() ? a.decision().scaleConfidence() : 0.0, f[0]));
            }
        }
        sb.append("[fanout] listed: ").append(Math.min(shown, 40)).append('\n');
        return sb.toString();
    }

    private static boolean settledAt(Answer a, double cut) {
        return a.decision().kindConfidence() >= cut
                && (!a.askedScale() || !"money".equals(a.decision().kind()) || a.decision().scaleConfidence() >= cut);
    }

    private static Set<String> intersect(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.retainAll(b);
        return out;
    }

    @Test
    void replayThePoolThatReachesTheDecisionModel() throws Exception {
        String source = System.getProperty("tev.replay.db");
        assumeTrue(source != null && !source.isBlank(), "set -Dtev.replay.db to replay a workspace");

        Path db = temp.resolve("replay.db");
        Files.copy(Path.of(source), db, StandardCopyOption.REPLACE_EXISTING);
        Path dictionaries = temp.resolve("dictionaries");
        Files.createDirectories(dictionaries);
        Path home = Path.of(System.getProperty("user.home"), ".tev-parser", "dictionaries");
        if (Files.isDirectory(home)) {
            try (var files = Files.list(home)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    if (Files.isRegularFile(file)) {
                        Files.copy(file, dictionaries.resolve(file.getFileName()));
                    }
                }
            }
        }
        System.setProperty(DynamicKindTokens.DIR_PROPERTY, dictionaries.toString());
        System.setProperty(DynamicKindTokens.LEARN_PROPERTY, "false");

        // "confident" settles every cell it is asked (as money): the cells it is asked about are then
        // the ones that cannot follow by arithmetic, a count of the questions a model has to answer.
        boolean confident = "confident".equals(System.getProperty("tev.replay.model"));
        boolean oracleMode = "oracle".equals(System.getProperty("tev.replay.model"));
        Map<String, CellReading> oracle = new HashMap<>(); // coord|display|formula -> the stored reading
        Set<String> ambiguous = new HashSet<>();
        Map<Long, CellReading> before = new HashMap<>();
        List<Asked> asked = new CopyOnWriteArrayList<>();
        CellDecisionClient neverSure = state -> {
            JsonNode s = MAPPER.readTree(state);
            asked.add(new Asked(
                    s.path("cell").path("coord").asText(),
                    s.path("cell").path("display").asText(),
                    s.path("cell").path("formula").asText("")));
            if (oracleMode) {
                String key = oracleKey(s.path("cell").path("coord").asText(),
                        s.path("cell").path("display").asText(), s.path("cell").path("formula").asText(""));
                CellReading stored = oracle.get(key);
                if (stored != null && stored.kind() != null && !ambiguous.contains(key)) {
                    return new CellDecisionClient.Decision(stored.kind(), 0.99,
                            stored.scale() == null ? "unit" : stored.scale(), 0.99);
                }
                return new CellDecisionClient.Decision("money", 0.0, "unit", 0.0);
            }
            double sure = confident ? 0.99 : 0.0;
            return new CellDecisionClient.Decision("money", sure, "unit", sure);
        };
        ClassifierLlm answersNothing = new ClassifierLlm() {
            @Override public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) { return List.of(); }
            @Override public LayerAJudgment classifyLayerA(LayerAPrompt prompt) { return null; }
        };

        StringBuilder report = new StringBuilder();
        try (WorkspaceDatabase database = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(database.connection());
            long parseRunId;
            try (var st = database.connection().createStatement();
                    ResultSet rs = st.executeQuery("select max(parse_run_id) from parse_run")) {
                rs.next();
                parseRunId = rs.getLong(1);
            }
            List<String> scopeNames = List.of(System.getProperty("tev.replay.scope", "B  S").split(","));
            Set<Long> scope = "*".equals(scopeNames.get(0).trim())
                    ? null // the whole workbook
                    : new ClassifyService(answersNothing).withSheetScope(scopeNames).resolveScope(repo, parseRunId);

            if (oracleMode) {
                Map<Long, InterpretationCellView> byId = new HashMap<>();
                repo.selectInterpretationCellsForParseRun(parseRunId).forEach(c -> byId.put(c.cellId(), c));
                for (CellReading r : repo.selectCellReadingsForParseRun(parseRunId)) {
                    before.put(r.cellId(), r);
                    InterpretationCellView c = byId.get(r.cellId());
                    if (c != null && (scope == null || scope.contains(c.worksheetId()))) {
                        String key = oracleKey(c.coord(), c.displayValue() == null ? "" : c.displayValue(),
                                c.formulaText() == null ? "" : c.formulaText());
                        if (oracle.put(key, r) != null) {
                            ambiguous.add(key); // the same coord, display and formula on two sheets
                        }
                    }
                }
            }
            LlmStats.GLOBAL.reset();
            new CellReadingWriter()
                    .withTuning(new ClassifyTuning(15, 10, 8), scope)
                    .withDecisionModel(neverSure)
                    .replace(repo, parseRunId, answersNothing);

            report.append(analyse(repo, parseRunId, asked, scope));
            if (oracleMode) {
                report.append(compareWithStored(repo, parseRunId, asked, scope, before));
            }
            String dump = System.getProperty("tev.replay.dump");
            if (dump != null && !dump.isBlank()) {
                dumpReadings(repo, parseRunId, scope, Path.of(dump));
            }
        }
        String out = System.getProperty("tev.replay.out");
        if (out != null && !out.isBlank()) {
            Files.writeString(Path.of(out), report.toString());
        }
        System.err.print(report);
    }

    /** One line per in-scope formula cell: where it is, its formula and how it was read, to diff two builds. */
    private static void dumpReadings(WorkspaceRepository repo, long parseRunId, Set<Long> scope, Path file)
            throws Exception {
        Map<Long, InterpretationCellView> byId = new HashMap<>();
        repo.selectInterpretationCellsForParseRun(parseRunId).forEach(c -> byId.put(c.cellId(), c));
        Map<Long, String> sheets = new HashMap<>();
        for (WorksheetRef sheet : repo.selectWorksheetsForParseRun(parseRunId)) {
            sheets.put(sheet.worksheetId(), sheet.sheetName());
        }
        Set<Long> formulas = new HashSet<>();
        repo.selectFormulaLinksForParseRun(parseRunId).forEach(l -> formulas.add(l.fromCellId()));
        List<String> lines = new ArrayList<>();
        for (CellReading r : repo.selectCellReadingsForParseRun(parseRunId)) {
            InterpretationCellView c = byId.get(r.cellId());
            if (c == null || !formulas.contains(c.cellId()) || (scope != null && !scope.contains(c.worksheetId()))) {
                continue;
            }
            lines.add(String.join("\t", sheets.get(c.worksheetId()) + "!" + c.coord(),
                    c.formulaText() == null ? "" : c.formulaText(), String.valueOf(r.kind()),
                    String.valueOf(r.scale()), String.valueOf(r.typeSource()), String.valueOf(r.refusal())));
        }
        java.util.Collections.sort(lines);
        Files.write(file, lines);
    }

    private static String oracleKey(String coord, String display, String formula) {
        return coord + "|" + display + "|" + formula;
    }

    /**
     * Cells typed now against what the stored run said, split by how they were typed this time:
     * asked of the model (which here repeats the stored answer, so they agree by construction)
     * or settled without it (dictionary or arithmetic), where a disagreement is a real difference.
     */
    private static String compareWithStored(
            WorkspaceRepository repo, long parseRunId, List<Asked> asked, Set<Long> scope,
            Map<Long, CellReading> before) throws Exception {
        Map<Long, InterpretationCellView> byId = new HashMap<>();
        repo.selectInterpretationCellsForParseRun(parseRunId).forEach(c -> byId.put(c.cellId(), c));
        Map<Long, String> sheets = new HashMap<>();
        for (WorksheetRef sheet : repo.selectWorksheetsForParseRun(parseRunId)) {
            sheets.put(sheet.worksheetId(), sheet.sheetName());
        }
        Set<String> askedKeys = new HashSet<>();
        asked.forEach(a -> askedKeys.add(oracleKey(a.coord(), a.display(), a.formula())));
        Set<Long> formulas = new HashSet<>();
        repo.selectFormulaLinksForParseRun(parseRunId).forEach(l -> formulas.add(l.fromCellId()));

        int comparable = 0;
        int kindAgree = 0;
        int bothAgree = 0;
        int newlyUntyped = 0;
        int newlyTyped = 0;
        Map<String, Integer> pairs = new TreeMap<>();
        List<String> samples = new ArrayList<>();
        for (CellReading now : repo.selectCellReadingsForParseRun(parseRunId)) {
            InterpretationCellView c = byId.get(now.cellId());
            if (c == null || !formulas.contains(c.cellId()) || (scope != null && !scope.contains(c.worksheetId()))) {
                continue;
            }
            String key = oracleKey(c.coord(), c.displayValue() == null ? "" : c.displayValue(),
                    c.formulaText() == null ? "" : c.formulaText());
            if (askedKeys.contains(key)) {
                continue; // the model's own answer, copied from the stored run
            }
            CellReading was = before.get(now.cellId());
            boolean wasTyped = was != null && was.kind() != null;
            boolean isTyped = now.kind() != null;
            if (wasTyped && !isTyped) {
                newlyUntyped++;
                pairs.merge("typed before, " + now.refusal() + " now", 1, Integer::sum);
                continue;
            }
            if (!wasTyped && isTyped) {
                newlyTyped++;
                continue;
            }
            if (!wasTyped) {
                continue;
            }
            comparable++;
            boolean kindSame = was.kind().equals(now.kind());
            boolean scaleSame = !"money".equals(now.kind()) || java.util.Objects.equals(was.scale(), now.scale());
            if (kindSame) {
                kindAgree++;
            }
            if (kindSame && scaleSame) {
                bothAgree++;
            } else {
                pairs.merge(was.kind() + "/" + was.scale() + " -> " + now.kind() + "/" + now.scale(), 1, Integer::sum);
                if (samples.size() < 12) {
                    samples.add(sheets.get(c.worksheetId()) + "!" + c.coord() + "  " + c.formulaText()
                            + "  stored " + was.kind() + "/" + was.scale() + "  now " + now.kind() + "/" + now.scale()
                            + " (" + now.typeSource() + ")");
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[replay] settled without the model, against the stored run:\n");
        sb.append(String.format("[replay]   %d typed in both; kind agrees %d, kind and scale agree %d%n",
                comparable, kindAgree, bothAgree));
        sb.append(String.format("[replay]   typed before but not now: %d; untyped before, typed now: %d%n",
                newlyUntyped, newlyTyped));
        pairs.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(10)
                .forEach(e -> sb.append(String.format("[replay]   %5d  %s%n", e.getValue(), e.getKey())));
        samples.forEach(x -> sb.append("[replay]   e.g. ").append(x).append('\n'));
        return sb.toString();
    }

    /** The pool is the in-scope numeric cells still untypable once no model could settle anything. */
    private static String analyse(
            WorkspaceRepository repo, long parseRunId, List<Asked> asked, Set<Long> scope) throws Exception {
        List<InterpretationCellView> cells = repo.selectInterpretationCellsForParseRun(parseRunId);
        Map<Long, InterpretationCellView> byId = new HashMap<>();
        cells.forEach(c -> byId.put(c.cellId(), c));
        Map<Long, String> sheets = new HashMap<>();
        for (WorksheetRef sheet : repo.selectWorksheetsForParseRun(parseRunId)) {
            sheets.put(sheet.worksheetId(), sheet.sheetName());
        }
        Map<Long, CellReading> readings = new HashMap<>();
        for (CellReading r : repo.selectCellReadingsForParseRun(parseRunId)) {
            readings.put(r.cellId(), r);
        }
        Map<Long, Set<Long>> precedents = new HashMap<>();
        for (FormulaLink link : repo.selectFormulaLinksForParseRun(parseRunId)) {
            precedents.computeIfAbsent(link.fromCellId(), id -> new HashSet<>()).add(link.toCellId());
        }

        Set<Long> pool = new HashSet<>();
        for (CellReading r : readings.values()) {
            // Cells on sheets outside the scope are never sent to a model, so they are not the pool.
            boolean inScope = scope == null || scope.contains(byId.get(r.cellId()).worksheetId());
            if (inScope && ReadingOutcome.UNTYPABLE.equals(r.refusal())) {
                pool.add(r.cellId());
            }
        }
        long formulasInPool = pool.stream().filter(id -> precedents.containsKey(id)).count();

        // Why each pool formula cannot be typed yet: what its numeric precedents are.
        Map<String, Integer> waits = new TreeMap<>();
        Set<Long> roots = new HashSet<>();
        for (long id : pool) {
            Set<Long> preds = precedents.get(id);
            if (preds == null) {
                waits.merge("input, no precedents (label could not type it)", 1, Integer::sum);
                continue;
            }
            boolean onPoolFormula = false;
            boolean onPoolInput = false;
            boolean onOtherRefusal = false;
            boolean anyNumeric = false;
            for (long p : preds) {
                CellReading pr = readings.get(p);
                if (pr == null) {
                    continue; // not a numeric cell
                }
                anyNumeric = true;
                if (ReadingOutcome.UNTYPABLE.equals(pr.refusal())) {
                    if (precedents.containsKey(p)) {
                        onPoolFormula = true;
                    } else {
                        onPoolInput = true;
                    }
                } else if (pr.refusal() != null) {
                    onOtherRefusal = true;
                }
            }
            String why = !anyNumeric ? "formula, no numeric precedent"
                    : onPoolFormula ? "waits on another pool formula"
                    : onPoolInput ? "waits on an untyped input"
                    : onOtherRefusal ? "precedent refused (kind_conflict etc.)"
                    : "all precedents typed by now, never re-propagated";
            waits.merge(why, 1, Integer::sum);
            if (!onPoolFormula) {
                roots.add(id);
            }
        }

        // Depth: 1 for a root, else 1 + the deepest pool formula it waits on. Cycles count as 1.
        Map<Long, Integer> depth = new HashMap<>();
        for (long id : pool) {
            depthOf(id, pool, precedents, depth, new HashSet<>());
        }
        TreeMap<Integer, Integer> depthHistogram = new TreeMap<>();
        depth.values().forEach(d -> depthHistogram.merge(d, 1, Integer::sum));

        Map<String, Integer> bySheet = new TreeMap<>();
        for (long id : pool) {
            bySheet.merge(sheets.getOrDefault(byId.get(id).worksheetId(), "?"), 1, Integer::sum);
        }
        long askedWithFormula = asked.stream().filter(a -> !a.formula().isBlank()).count();

        StringBuilder sb = new StringBuilder();
        sb.append("[replay] numeric cells with a reading: ").append(readings.size()).append('\n');
        sb.append("[replay] typed by dictionary (cells_typed_dictionary): ")
                .append(stat("cells_typed_dictionary")).append('\n');
        sb.append("[replay] reached the decision model (cells_to_decision_model): ")
                .append(stat("cells_to_decision_model")).append(", settled: ")
                .append(stat("cells_settled_decision_model")).append(" (asked: ").append(asked.size())
                .append(", of which with a formula: ").append(askedWithFormula).append(")\n");
        sb.append("[replay] reached the chat model (cells_to_chat): ").append(stat("cells_to_chat")).append('\n');
        sb.append("[replay] followed by arithmetic after a stage (cells_typed_repropagation): ")
                .append(stat("cells_typed_repropagation")).append(", decision rounds: ")
                .append(stat("decision_rounds")).append('\n');
        sb.append("[replay] untypable at the end = the pool: ").append(pool.size())
                .append(" (formulas: ").append(formulasInPool).append(")\n");
        sb.append("[replay] why pool cells wait:\n");
        waits.forEach((k, v) -> sb.append(String.format("[replay]   %5d  %s%n", v, k)));
        sb.append("[replay] roots (waiting on no other pool formula): ").append(roots.size()).append('\n');
        sb.append("[replay] chain depth among pool cells (1 = root): ");
        depthHistogram.forEach((d, n) -> sb.append(d).append(":").append(n).append(' '));
        sb.append("\n[replay] pool by sheet:\n");
        bySheet.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> sb.append(String.format("[replay]   %5d  %s%n", e.getValue(), e.getKey())));
        return sb.toString();
    }

    private static int depthOf(
            long id, Set<Long> pool, Map<Long, Set<Long>> precedents, Map<Long, Integer> memo, Set<Long> path) {
        Integer known = memo.get(id);
        if (known != null) {
            return known;
        }
        if (!path.add(id)) {
            return 1; // a cycle: count it as a root rather than recurse forever
        }
        int deepest = 0;
        for (long p : precedents.getOrDefault(id, Set.of())) {
            if (pool.contains(p) && precedents.containsKey(p)) {
                deepest = Math.max(deepest, depthOf(p, pool, precedents, memo, path));
            }
        }
        path.remove(id);
        memo.put(id, deepest + 1);
        return deepest + 1;
    }

    private static String stat(String name) {
        Double v = LlmStats.GLOBAL.stat("layer-b", name);
        return v == null ? "-" : Long.toString(v.longValue());
    }
}
