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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Offline replay of Layer B's typing on a copy of a finished workspace, with no network: the
 * decision model is a recording stub that is never sure, and the chat model answers nothing, so
 * the cells left untyped are exactly the cells that reached the decision model. The report says
 * how many there were and how they wait on each other, which is what the round-based pass 2 is
 * sized from.
 *
 * <p>Skipped unless {@code -Dtev.replay.db=<path to a workspace db>} is given. Optional:
 * {@code -Dtev.replay.scope="B  S"} (comma separated; default {@code B  S}) and
 * {@code -Dtev.replay.out=<file>} for the report. The database and the learned dictionary are
 * copied first, so neither the workspace nor {@code ~/.tev-parser} is touched.
 */
class DecisionPoolReplayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temp;

    /** What the model was asked, so the pool can be told apart from cells settled by labels. */
    private record Asked(String coord, String display, String formula) {}

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

        List<Asked> asked = new CopyOnWriteArrayList<>();
        CellDecisionClient neverSure = state -> {
            JsonNode s = MAPPER.readTree(state);
            asked.add(new Asked(
                    s.path("cell").path("coord").asText(),
                    s.path("cell").path("display").asText(),
                    s.path("cell").path("formula").asText("")));
            return new CellDecisionClient.Decision("money", 0.0, "unit", 0.0);
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
            Set<Long> scope = new ClassifyService(answersNothing).withSheetScope(scopeNames)
                    .resolveScope(repo, parseRunId);

            LlmStats.GLOBAL.reset();
            new CellReadingWriter()
                    .withTuning(new ClassifyTuning(15, 10, 8), scope)
                    .withDecisionModel(neverSure)
                    .replace(repo, parseRunId, answersNothing);

            report.append(analyse(repo, parseRunId, asked, scope));
        }
        String out = System.getProperty("tev.replay.out");
        if (out != null && !out.isBlank()) {
            Files.writeString(Path.of(out), report.toString());
        }
        System.err.print(report);
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
                .append(stat("cells_to_decision_model")).append(" (asked: ").append(asked.size())
                .append(", of which with a formula: ").append(askedWithFormula).append(")\n");
        sb.append("[replay] reached the chat model (cells_to_chat): ").append(stat("cells_to_chat")).append('\n');
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
