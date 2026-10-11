package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.ingest.IngestService;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Sheet scoping keeps formulas resolvable; tuning changes how many calls are made. */
class ClassifyScopeAndTuningTest {

    private static final RegionProposal GOOD = new RegionProposal("main", "A1:B6", "table", "main schedule");

    @TempDir
    Path tempDir;

    private Path dbPath;
    private long parseRunId;

    @BeforeEach
    void ingestThreeSheets() throws Exception {
        Path xlsx = tempDir.resolve("scope.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet inputs = workbook.createSheet("INPUTS");
            Sheet summary = workbook.createSheet("SUMMARY");
            Sheet other = workbook.createSheet("OTHER");
            for (Sheet sheet : new Sheet[] {inputs, summary, other}) {
                for (int r = 0; r < 6; r++) {
                    Row row = sheet.createRow(r);
                    row.createCell(0).setCellValue("Item " + r);
                    row.createCell(1).setCellValue(10.0 * (r + 1));
                }
            }
            summary.getRow(2).createCell(2).setCellFormula("INPUTS!B2*2"); // SUMMARY reads INPUTS
            try (FileOutputStream out = new FileOutputStream(xlsx.toFile())) {
                workbook.write(out);
            }
        }
        dbPath = tempDir.resolve("scope.db");
        parseRunId = new IngestService().ingest(xlsx, 1L, dbPath).parseRunId();
        new DiscoverService().discover(dbPath, parseRunId);
    }

    /** Records which sheets were asked about and how Layer A was called. */
    private static final class CountingLlm implements ClassifierLlm {
        final List<String> regionSheets = new CopyOnWriteArrayList<>();
        final List<String> layerABatchCalls = new CopyOnWriteArrayList<>();

        @Override
        public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) {
            regionSheets.add(prompt.sheetName());
            return List.of(GOOD);
        }

        @Override
        public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
            return new LayerAJudgment(
                    ScheduleFamily.ASSUMPTIONS, Triage.MAIN, Relevance.PRIMARY,
                    List.of(), List.of(), null, "A schedule.");
        }

        @Override
        public String classifyLayerAJson(String userPrompt, int maxTokens) {
            layerABatchCalls.add(userPrompt);
            return ""; // unusable on purpose: each candidate is then redone on its own
        }
    }

    private List<CandidateRow> candidatesOn(String sheet) throws Exception {
        try (WorkspaceDatabase database = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(database.connection());
            long id = repo.selectWorksheetsForParseRun(parseRunId).stream()
                    .filter(s -> s.sheetName().equals(sheet)).findFirst().orElseThrow().worksheetId();
            return repo.selectCandidatesForParseRun(parseRunId).stream()
                    .filter(c -> c.worksheetId() == id).toList();
        }
    }

    @Test
    void aSheetThatTheScopedSheetsFormulasReadIsPulledIn() throws Exception {
        var llm = new CountingLlm();
        var service = new ClassifyService(llm).withSheetScope(List.of("summary"));
        service.regionRetryBackoffMillis = new long[] {0, 0};
        try (WorkspaceDatabase database = WorkspaceDatabase.open(dbPath)) {
            var repo = new WorkspaceRepository(database.connection());
            var scope = service.resolveScope(repo, parseRunId);
            var names = repo.selectWorksheetsForParseRun(parseRunId).stream()
                    .filter(s -> scope.contains(s.worksheetId())).map(s -> s.sheetName()).toList();

            assertThat(names).containsExactlyInAnyOrder("SUMMARY", "INPUTS"); // OTHER is not read by anything
        }
    }

    @Test
    void scopedRegionLayoutAsksOnlyAboutScopedSheetsAndLeavesTheRestAlone() throws Exception {
        var llm = new CountingLlm();
        int otherBefore = candidatesOn("OTHER").size();
        var service = new ClassifyService(llm).withSheetScope(List.of("INPUTS"));
        service.regionRetryBackoffMillis = new long[] {0, 0};

        try (WorkspaceDatabase database = WorkspaceDatabase.open(dbPath)) {
            var repo = new WorkspaceRepository(database.connection());
            service.useScope(repo, parseRunId); // normally done by classify()
            service.materializeLlmRegions(repo, parseRunId);
        }

        assertThat(llm.regionSheets).containsOnly("INPUTS");
        assertThat(candidatesOn("OTHER")).hasSize(otherBefore);
    }

    @Test
    void anUnknownScopedSheetIsRejectedWithItsName() throws Exception {
        var service = new ClassifyService(new CountingLlm()).withSheetScope(List.of("NO SUCH TAB"));

        try (WorkspaceDatabase database = WorkspaceDatabase.open(dbPath)) {
            var repo = new WorkspaceRepository(database.connection());
            assertThatThrownBy(() -> service.resolveScope(repo, parseRunId))
                    .isInstanceOf(ClassifyException.class)
                    .hasMessageContaining("no such tab");
        }
    }

    @Test
    void noScopeMeansTheWholeWorkbook() throws Exception {
        var service = new ClassifyService(new CountingLlm());

        try (WorkspaceDatabase database = WorkspaceDatabase.open(dbPath)) {
            assertThat(service.resolveScope(new WorkspaceRepository(database.connection()), parseRunId)).isNull();
        }
    }

    @Test
    void layerABatchSizeControlsHowManyCallsAreMade() throws Exception {
        var small = new CountingLlm();
        new ClassifyService(small).withTuning(new ClassifyTuning(15, 1, 1)).classify(dbPath, parseRunId);
        int candidates = small.layerABatchCalls.size();

        // fresh database, same workbook, one big batch
        Path second = tempDir.resolve("scope2.db");
        Path xlsx = tempDir.resolve("scope.xlsx");
        long run2 = new IngestService().ingest(xlsx, 1L, second).parseRunId();
        new DiscoverService().discover(second, run2);
        var big = new CountingLlm();
        new ClassifyService(big).withTuning(new ClassifyTuning(15, 100, 1)).classify(second, run2);

        assertThat(candidates).isGreaterThan(1);
        assertThat(big.layerABatchCalls).hasSize(1);
    }

    @Test
    void concurrentLayerACallsStillProduceEveryDispositionInOrder() throws Exception {
        var llm = new CountingLlm();

        var summary = new ClassifyService(llm)
                .withTuning(new ClassifyTuning(15, 1, 3))
                .classify(dbPath, parseRunId);

        assertThat(summary.dispositionCount()).isEqualTo(llm.layerABatchCalls.size());
        assertThat(new ArrayList<>(llm.layerABatchCalls)).isNotEmpty();
    }

    /** Answers a Layer A batch the way the old prompt invited: every region "HELPER". */
    private static final class HelperAnsweringLlm implements ClassifierLlm {
        final List<String> prompts = new CopyOnWriteArrayList<>();

        @Override
        public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) {
            return List.of(GOOD);
        }

        @Override
        public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
            throw new IllegalStateException("only the batch path is expected");
        }

        @Override
        public String classifyLayerAJson(String userPrompt, int maxTokens) {
            prompts.add(userPrompt);
            int n = Integer.parseInt(userPrompt.replaceAll("(?s).*Classify exactly (\\d+) regions.*", "$1"));
            StringBuilder json = new StringBuilder("{\"results\":[");
            for (int i = 1; i <= n; i++) {
                json.append(i > 1 ? "," : "").append("{\"index\":").append(i)
                        .append(",\"scheduleFamily\":\"assumptions\",\"triage\":\"HELPER\",\"relevance\":\"SECONDARY\",")
                        .append("\"rowLabels\":[],\"columnHeaders\":[],\"packetDefaultHead\":null,")
                        .append("\"about\":\"A supporting block.\"}");
            }
            return json.append("]}").toString();
        }
    }

    /**
     * "Helper" is a region that supports the model. It used to be stored as scratch, which the rest of the pipeline
     * treats as noise and never binds. It is kept (main, supporting), and the prompt offers the stored vocabulary.
     */
    @Test
    void aRegionTheModelCallsHelperIsKeptAsSupportingNotThrownAwayAsScratch() throws Exception {
        var llm = new HelperAnsweringLlm();

        new ClassifyService(llm).withTuning(new ClassifyTuning(15, 100, 1)).classify(dbPath, parseRunId);

        assertThat(llm.prompts).isNotEmpty();
        assertThat(llm.prompts.get(0)).contains("triage: main | scratch | orphan").doesNotContain("HELPER only");
        try (WorkspaceDatabase database = WorkspaceDatabase.open(dbPath)) {
            var dispositions = new WorkspaceRepository(database.connection())
                    .selectPacketDispositionsForParseRun(parseRunId);
            assertThat(dispositions).isNotEmpty()
                    .allSatisfy(d -> {
                        assertThat(d.triage()).isEqualTo("main");
                        assertThat(d.relevance()).isEqualTo("supporting");
                    });
        }
    }

    @Test
    void tuningRejectsNonsense() {
        assertThatThrownBy(() -> new ClassifyTuning(0, 10, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassifyTuning(15, 10, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Answers Layer A batches with numbered results, optionally leaving some candidates out. */
    private static final class NumberedLayerALlm implements ClassifierLlm {
        final java.util.Set<Integer> leaveOut;
        final List<String> singles = new CopyOnWriteArrayList<>();
        final boolean numbered;

        NumberedLayerALlm(java.util.Set<Integer> leaveOut, boolean numbered) {
            this.leaveOut = leaveOut;
            this.numbered = numbered;
        }

        @Override
        public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) {
            return List.of(GOOD);
        }

        @Override
        public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
            singles.add(prompt.sheetName());
            return new LayerAJudgment(
                    ScheduleFamily.ASSUMPTIONS, Triage.MAIN, Relevance.PRIMARY, List.of(), List.of(), null, "Single.");
        }

        @Override
        public String classifyLayerAJson(String userPrompt, int maxTokens) {
            var m = java.util.regex.Pattern.compile("Classify exactly (\\d+) regions").matcher(userPrompt);
            int n = m.find() ? Integer.parseInt(m.group(1)) : 0;
            StringBuilder sb = new StringBuilder("{\"results\":[");
            boolean first = true;
            for (int i = 1; i <= n; i++) {
                if (leaveOut.contains(i)) {
                    continue;
                }
                sb.append(first ? "" : ",");
                first = false;
                sb.append("{").append(numbered ? "\"index\":" + i + "," : "")
                        .append("\"scheduleFamily\":\"assumptions\",\"triage\":\"MAIN\",\"relevance\":\"PRIMARY\",")
                        .append("\"rowLabels\":[],\"columnHeaders\":[],\"packetDefaultHead\":null,\"about\":\"Batch.\"}");
            }
            return sb.append("]}").toString();
        }
    }

    @Test
    void aMissingLayerAAnswerRetriesOnlyThatCandidate() throws Exception {
        var llm = new NumberedLayerALlm(java.util.Set.of(2), true);

        var summary = new ClassifyService(llm)
                .withTuning(new ClassifyTuning(15, 100, 1))
                .classify(dbPath, parseRunId);

        assertThat(llm.singles).hasSize(1); // just candidate 2, not the whole batch
        assertThat(summary.dispositionCount()).isGreaterThan(1);
    }

    @Test
    void numberedLayerAAnswersNeedNoRetryWhenComplete() throws Exception {
        var llm = new NumberedLayerALlm(java.util.Set.of(), true);

        var summary = new ClassifyService(llm)
                .withTuning(new ClassifyTuning(15, 100, 1))
                .classify(dbPath, parseRunId);

        assertThat(llm.singles).isEmpty();
        assertThat(summary.dispositionCount()).isGreaterThan(1);
    }

    @Test
    void unnumberedLayerAAnswersWithTheWrongCountStillFallBackToSingles() throws Exception {
        var llm = new NumberedLayerALlm(java.util.Set.of(2), false);

        var summary = new ClassifyService(llm)
                .withTuning(new ClassifyTuning(15, 100, 1))
                .classify(dbPath, parseRunId);

        assertThat(llm.singles.size()).isEqualTo(summary.dispositionCount()); // every candidate redone
    }
}
