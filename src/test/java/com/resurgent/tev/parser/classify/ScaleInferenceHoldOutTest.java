package com.resurgent.tev.parser.classify;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.resurgent.tev.parser.db.CellReading;
import com.resurgent.tev.parser.db.InterpretationCellView;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.db.WorksheetRef;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Developer check, not part of the suite (it needs a saved run): hide the scales the workbook states for
 * some money, ask {@link ScaleInference} to work them out from the rest, and compare with the truth.
 * {@code -Dtev.holdout.db=<workspace.db>}
 */
class ScaleInferenceHoldOutTest {

    @Test
    void howOftenTheInferenceIsRightWhenItKnowsTheAnswer() throws Exception {
        String dbPath = System.getProperty("tev.holdout.db");
        assumeTrue(dbPath != null && !dbPath.isBlank(), "set -Dtev.holdout.db to run");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(Path.of(dbPath))) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            long run = repo.selectWorksheetsForParseRun(1L).isEmpty() ? 1L : 1L;
            List<InterpretationCellView> cells = repo.selectInterpretationCellsForParseRun(run);
            Map<Long, String> sheetNames = new HashMap<>();
            for (WorksheetRef sheet : repo.selectWorksheetsForParseRun(run)) {
                sheetNames.put(sheet.worksheetId(), sheet.sheetName());
            }
            Map<Long, CellReading> readings = new HashMap<>();
            for (CellReading r : repo.selectCellReadingsForParseRun(run)) {
                readings.put(r.cellId(), r);
            }
            // A saved run can hold scales a model guessed and recorded as stated. The readings of a replay
            // with the guess rule fixed (-Dtev.holdout.readings=<replay dump>) hold only scales the workbook states.
            String replay = System.getProperty("tev.holdout.readings");
            if (replay != null && !replay.isBlank()) {
                Map<String, String[]> clean = new HashMap<>();
                for (String line : java.nio.file.Files.readAllLines(Path.of(replay))) {
                    String[] p = line.split("\t", -1);
                    if (p.length >= 4) {
                        clean.put(p[0], p);
                    }
                }
                Map<Long, CellReading> replayed = new HashMap<>();
                for (InterpretationCellView cell : cells) {
                    String[] p = clean.get(sheetNames.get(cell.worksheetId()) + "!" + cell.coord());
                    if (p != null && !"null".equals(p[2])) {
                        replayed.put(cell.cellId(), new CellReading(run, cell.cellId(), p[2],
                                "null".equals(p[3]) ? null : p[3], null, null, null, p[4], null, null));
                    }
                }
                readings = replayed;
            }
            ClassifierLlm llm = Boolean.getBoolean("tev.holdout.live") ? LlmEnvironment.classifierOrUnconfigured() : null;
            String only = System.getProperty("tev.holdout.sheets");
            java.util.Set<String> onlySheets = only == null || only.isBlank()
                    ? null : new java.util.HashSet<>(java.util.Arrays.asList(only.split("\\|")));
            for (String mode : new String[] {"whole sheet hidden"}) {
                int right = 0;
                int wrong = 0;
                int abstained = 0;
                List<String> misses = new ArrayList<>();
                Map<String, int[]> perSheet = new java.util.TreeMap<>();
                Random random = new Random(7);
                for (String held : new java.util.TreeSet<>(sheetNames.values())) {
                    if (onlySheets != null && !onlySheets.contains(held)) {
                        continue;
                    }
                    Map<Long, ReadingOutcome> settled = new HashMap<>();
                    Map<Long, CellScale> truth = new HashMap<>();
                    UnstatedScales unknowns = new UnstatedScales();
                    Map<String, Integer> rowUnknown = new HashMap<>();
                    Map<Integer, Boolean> hiddenRows = new HashMap<>();
                    for (InterpretationCellView cell : cells) {
                        CellReading r = readings.get(cell.cellId());
                        if (r == null || r.kind() == null) {
                            continue;
                        }
                        String sheet = sheetNames.get(cell.worksheetId());
                        CellScale scale = r.scale() == null ? null : CellScale.fromWire(r.scale());
                        boolean stated = scale != null && !CellReading.SCALE_INFERRED.equals(r.scaleBasis());
                        boolean hide = "money".equals(r.kind()) && stated && sheet.equals(held)
                                && (mode.startsWith("whole")
                                        || hiddenRows.computeIfAbsent(cell.rowNum(), k -> random.nextBoolean()));
                        if (hide) {
                            String rowKey = cell.worksheetId() + ":" + cell.rowNum();
                            int unknown = rowUnknown.containsKey(rowKey)
                                    ? rowUnknown.get(rowKey) : unknowns.fresh(cell.cellId());
                            if (rowUnknown.containsKey(rowKey)) {
                                unknowns.same(unknown, unknowns.fresh(cell.cellId()));
                            }
                            rowUnknown.putIfAbsent(rowKey, unknown);
                            settled.put(cell.cellId(), ReadingOutcome.unstated("", "", ReadingOutcome.INPUT, unknown));
                            truth.put(cell.cellId(), scale);
                        } else if (scale != null && "money".equals(r.kind())) {
                            ReadingOutcome o = ReadingOutcome.typed("money", scale, "", "", ReadingOutcome.INPUT);
                            settled.put(cell.cellId(), CellReading.SCALE_INFERRED.equals(r.scaleBasis())
                                    ? o.withScale(scale, true) : o);
                        }
                    }
                    if (truth.size() < 20) {
                        continue;
                    }
                    Map<Long, ScaleInference.Inference> got = ScaleInference.apply(
                            cells, settled, unknowns, sheetNames, CellContext.scan(cells), llm, 4);
                    int[] counts = perSheet.computeIfAbsent(held, k -> new int[3]);
                    for (Map.Entry<Long, CellScale> e : truth.entrySet()) {
                        ScaleInference.Inference inf = got.get(e.getKey());
                        if (inf == null) {
                            abstained++;
                            counts[2]++;
                        } else if (inf.scale() == e.getValue()) {
                            right++;
                            counts[0]++;
                        } else {
                            wrong++;
                            counts[1]++;
                            if (misses.size() < 6) {
                                misses.add(held + " cell " + e.getKey() + ": said " + inf.scale().wireName()
                                        + ", truth " + e.getValue().wireName() + " | " + inf.evidence());
                            }
                        }
                    }
                }
                System.out.println("HOLDOUT [" + mode + "] right=" + right + " wrong=" + wrong + " abstained=" + abstained);
                perSheet.forEach((s, c) -> System.out.println("HOLDOUT   " + s + ": right " + c[0] + ", wrong " + c[1] + ", abstained " + c[2]));
                misses.forEach(m -> System.out.println("HOLDOUT   miss: " + m));
            }
        }
    }
}
