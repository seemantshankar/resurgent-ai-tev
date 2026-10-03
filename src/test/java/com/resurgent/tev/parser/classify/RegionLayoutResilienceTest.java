package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.ingest.IngestService;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One bad AI answer for a sheet must cost a retry of that sheet, never the whole run. */
class RegionLayoutResilienceTest {

    private static final RegionProposal GOOD = new RegionProposal("main", "A1:D11", "capex_table", "main schedule");
    private static final RegionProposal EMPTY = new RegionProposal("main", "T14:Z45", "ghost", "nothing here");
    private static final RegionProposal GARBAGE = new RegionProposal("main", "not-a-bbox", "junk", "");

    @TempDir
    Path tempDir;

    private Path dbPath;
    private long parseRunId;

    @BeforeEach
    void ingestTwoSheets() throws Exception {
        Path xlsx = tempDir.resolve("two.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            for (String name : new String[] {"ASSETS", "BAD"}) {
                Sheet sheet = workbook.createSheet(name);
                for (int r = 0; r < 11; r++) {
                    Row row = sheet.createRow(r);
                    row.createCell(0).setCellValue("Item " + r);
                    row.createCell(1).setCellValue(100.0 * r);
                    row.createCell(3).setCellValue(10.0 * r);
                }
            }
            try (FileOutputStream out = new FileOutputStream(xlsx.toFile())) {
                workbook.write(out);
            }
        }
        dbPath = tempDir.resolve("two.db");
        var ingest = new IngestService().ingest(xlsx, 1L, dbPath);
        parseRunId = ingest.parseRunId();
        new DiscoverService().discover(dbPath, parseRunId);
    }

    /** Fake whose answer depends on (sheet name, 1-based call number for that sheet). */
    private static final class ScriptedLlm implements ClassifierLlm {
        final BiFunction<String, Integer, List<RegionProposal>> script;
        final java.util.Map<String, AtomicInteger> calls = new java.util.concurrent.ConcurrentHashMap<>();
        final List<String> prompts = new java.util.concurrent.CopyOnWriteArrayList<>();

        ScriptedLlm(BiFunction<String, Integer, List<RegionProposal>> script) {
            this.script = script;
        }

        int calls(String sheet) {
            return calls.getOrDefault(sheet, new AtomicInteger()).get();
        }

        @Override
        public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) {
            prompts.add(prompt.toString());
            int n = calls.computeIfAbsent(prompt.sheetName(), k -> new AtomicInteger()).incrementAndGet();
            return script.apply(prompt.sheetName(), n);
        }

        @Override
        public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
            return null;
        }
    }

    private List<CandidateRow> children(String sheetName) throws Exception {
        try (WorkspaceDatabase database = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(database.connection());
            long sheetId = repo.selectWorksheetsForParseRun(parseRunId).stream()
                    .filter(s -> s.sheetName().equals(sheetName))
                    .findFirst().orElseThrow().worksheetId();
            return repo.selectCandidatesForParseRun(parseRunId).stream()
                    .filter(c -> c.worksheetId() == sheetId && "child".equals(c.candidateKind()))
                    .toList();
        }
    }

    private void materialize(ClassifierLlm llm) throws Exception {
        ClassifyService service = new ClassifyService(llm);
        service.regionRetryBackoffMillis = new long[] {0, 0};
        try (WorkspaceDatabase database = WorkspaceDatabase.open(dbPath)) {
            service.materializeLlmRegions(new WorkspaceRepository(database.connection()), parseRunId);
        }
    }

    @Test
    void numbersTheModelLeftOutOfEveryRegionBecomeARegionOfTheirOwn() throws Exception {
        // the model boxes only the labels and column B; the amounts in column D are unclaimed
        var llm = new ScriptedLlm((sheet, n) -> List.of(new RegionProposal("main", "A1:B11", "left", "labels")));

        materialize(llm);

        List<CandidateRow> regions = children("ASSETS");
        assertThat(regions).hasSize(2);
        CandidateRow residual = regions.stream().filter(c -> c.bboxMinCol() == 4).findFirst().orElseThrow();
        assertThat(residual.structuralRole()).isEqualTo("helper");
        assertThat(residual.bboxMinRow()).isEqualTo(1);
        assertThat(residual.bboxMaxRow()).isEqualTo(11);
    }

    @Test
    void emptyRegionIsRetriedForThatSheetAndTheRunContinues() throws Exception {
        var llm = new ScriptedLlm((sheet, n) -> n == 1 ? List.of(GOOD, EMPTY) : List.of(GOOD));

        materialize(llm);

        assertThat(llm.calls("ASSETS")).isEqualTo(2);
        assertThat(llm.calls("BAD")).isEqualTo(2);
        assertThat(children("ASSETS")).hasSize(1);
        assertThat(children("ASSETS").get(0).bboxMaxRow()).isEqualTo(11);
    }

    @Test
    void retryTellsTheModelWhichRegionWasWrong() throws Exception {
        var llm = new ScriptedLlm((sheet, n) -> n == 1 ? List.of(GOOD, EMPTY) : List.of(GOOD));

        materialize(llm);

        assertThat(llm.prompts.stream().filter(p -> p.contains("T14:Z45"))).isNotEmpty();
    }

    @Test
    void regionThatStaysEmptyIsDroppedWhileValidOnesAreKept() throws Exception {
        var llm = new ScriptedLlm((sheet, n) -> List.of(GOOD, EMPTY));

        materialize(llm);

        assertThat(llm.calls("ASSETS")).isEqualTo(3); // first answer + two retries, then give up
        assertThat(children("ASSETS")).hasSize(1);
        assertThat(children("BAD")).hasSize(1);
    }

    @Test
    void malformedBoundingBoxIsRetriedLikeAnEmptyRegion() throws Exception {
        var llm = new ScriptedLlm((sheet, n) -> n == 1 ? List.of(GARBAGE) : List.of(GOOD));

        materialize(llm);

        assertThat(llm.calls("ASSETS")).isEqualTo(2);
        assertThat(children("ASSETS")).hasSize(1);
    }

    @Test
    void transientLlmFailureIsRetried() throws Exception {
        var llm = new ScriptedLlm((sheet, n) -> {
            if (n < 3) {
                throw new IllegalStateException("HTTP 503");
            }
            return List.of(GOOD);
        });

        materialize(llm);

        assertThat(llm.calls("ASSETS")).isEqualTo(3);
        assertThat(children("ASSETS")).hasSize(1);
    }

    @Test
    void oneSheetThatAlwaysFailsDoesNotAbortTheOthers() throws Exception {
        var llm = new ScriptedLlm((sheet, n) -> {
            if (sheet.equals("BAD")) {
                throw new IllegalStateException("model refuses this sheet");
            }
            return List.of(GOOD);
        });

        materialize(llm);

        assertThat(children("ASSETS")).hasSize(1);
        assertThat(children("BAD")).isEmpty(); // keeps only its structural coverage region
    }

    @Test
    void everySheetFailingStillDoesNotStopTheRun() throws Exception {
        var llm = new ScriptedLlm((sheet, n) -> {
            throw new IllegalStateException("provider down");
        });

        materialize(llm); // must not throw

        assertThat(children("ASSETS")).isEmpty();
        assertThat(children("BAD")).isEmpty();
    }
}
