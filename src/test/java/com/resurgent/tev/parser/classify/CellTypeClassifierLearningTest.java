package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.InterpretationCellView;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Region prose, learning and learned-lookup behaviour of {@link CellTypeClassifierLlm}. */
class CellTypeClassifierLearningTest {

    @TempDir
    Path dir;

    private static final RegionContext EXPENSES =
            new RegionContext(5L, "expenses", "Project cost estimate by work item.", "Project cost", "ASSETS", true);

    private static InterpretationCellView number(long id, int row) {
        return new InterpretationCellView(
                id, 1L, "C" + id, row, 3, "number", "100", "100", "100", null, null, null, null, null,
                null, false, null, false, false, null, "input");
    }

    /** Hand-built labels and regions, keyed by cell id. */
    static final class TestContext implements CellContext {
        final Map<Long, String> rows = new HashMap<>();
        final Map<Long, String> columns = new HashMap<>();
        final Map<Long, RegionContext> regions = new HashMap<>();
        final String workbook;

        TestContext(String workbook) {
            this.workbook = workbook;
        }

        TestContext cell(long id, String row, String column, RegionContext region) {
            rows.put(id, row);
            columns.put(id, column);
            regions.put(id, region);
            return this;
        }

        @Override public String rowLabel(InterpretationCellView c) { return rows.getOrDefault(c.cellId(), ""); }
        @Override public String columnLabel(InterpretationCellView c) { return columns.getOrDefault(c.cellId(), ""); }
        @Override public RegionContext region(InterpretationCellView c) { return regions.getOrDefault(c.cellId(), RegionContext.NONE); }
        @Override public String workbookKey() { return workbook; }
    }

    /** Answers batch and single prompts from {@code answer}; records every user prompt. */
    static final class RecordingLlm extends CellTypeClassifierLlmTest.FakeClassifierLlm {
        final List<String> prompts = new ArrayList<>();
        final Function<Integer, String> answerN;

        RecordingLlm(Function<Integer, String> answerN) {
            this.answerN = answerN;
        }

        static String item(String kind, String scale) {
            return "{\"kind\":\"" + kind + "\",\"scale\":\"" + scale
                    + "\",\"unit\":\"\",\"currency\":\"INR\",\"confidence\":0.95}";
        }

        static RecordingLlm always(String kind, String scale) {
            return new RecordingLlm(n -> n == 0
                    ? item(kind, scale)
                    : "{\"results\":[" + String.join(",", java.util.Collections.nCopies(n, item(kind, scale))) + "]}");
        }

        @Override
        public String classifyCellJson(String systemPrompt, String userPrompt, int maxTokens) {
            prompts.add(userPrompt);
            Matcher m = Pattern.compile("Classify the following (\\d+) cells").matcher(userPrompt);
            return answerN.apply(m.find() ? Integer.parseInt(m.group(1)) : 0);
        }
    }

    private Map<Long, ReadingOutcome> untypable(long... ids) {
        Map<Long, ReadingOutcome> settled = new HashMap<>();
        for (long id : ids) {
            settled.put(id, ReadingOutcome.refused(ReadingOutcome.UNTYPABLE));
        }
        return settled;
    }

    private void run(DynamicKindTokens dict, RecordingLlm llm, TestContext ctx, long... ids) {
        List<InterpretationCellView> cells = new ArrayList<>();
        for (long id : ids) {
            cells.add(number(id, (int) id));
        }
        new CellTypeClassifierLlm(null, llm, dict).classifyRemaining(cells, untypable(ids), ctx);
        dict.persist();
    }

    private Map<Long, ReadingOutcome> runAndSettle(DynamicKindTokens dict, RecordingLlm llm, TestContext ctx, long... ids) {
        List<InterpretationCellView> cells = new ArrayList<>();
        for (long id : ids) {
            cells.add(number(id, (int) id));
        }
        Map<Long, ReadingOutcome> settled = untypable(ids);
        new CellTypeClassifierLlm(null, llm, dict).classifyRemaining(cells, settled, ctx);
        return settled;
    }

    @Test
    void regionProseIsSentOncePerBatch() {
        var llm = RecordingLlm.always("money", "lakh");
        var ctx = new TestContext("wb1")
                .cell(1, "Fire Fighting Work", "FY23", EXPENSES)
                .cell(2, "Plumbing Works", "FY23", EXPENSES);

        run(new DynamicKindTokens(dir), llm, ctx, 1, 2);

        assertThat(llm.prompts).hasSize(1);
        assertThat(llm.prompts.get(0)).contains("Region: family=expenses", "about=Project cost estimate by work item.");
        assertThat(llm.prompts.get(0).split("Region:", -1)).hasSize(2);
    }

    @Test
    void cellsFromDifferentRegionsShareOneCallWithEachRegionDescribed() {
        var other = new RegionContext(6L, "assets", "Asset list.", "", "ASSETS", true);
        var llm = RecordingLlm.always("money", "lakh");
        var ctx = new TestContext("wb1")
                .cell(1, "Fire Fighting Work", "FY23", EXPENSES)
                .cell(2, "Plumbing Works", "FY23", other);

        run(new DynamicKindTokens(dir), llm, ctx, 1, 2);

        assertThat(llm.prompts).hasSize(1);
        String prompt = llm.prompts.get(0);
        assertThat(prompt).contains("family=expenses", "family=assets");
        assertThat(prompt.indexOf("family=expenses")).isLessThan(prompt.indexOf("Fire Fighting Work"));
        assertThat(prompt.indexOf("Fire Fighting Work")).isLessThan(prompt.indexOf("family=assets"));
        assertThat(prompt.indexOf("family=assets")).isLessThan(prompt.indexOf("Plumbing Works"));
    }

    @Test
    void batchSizeCapsHowManyCellsShareACall() {
        var llm = RecordingLlm.always("money", "lakh");
        var ctx = new TestContext("wb1");
        for (long id = 1; id <= 5; id++) {
            ctx.cell(id, "Item " + id, "FY23", EXPENSES);
        }
        List<InterpretationCellView> cells = new ArrayList<>();
        for (long id = 1; id <= 5; id++) {
            cells.add(number(id, (int) id));
        }
        var settled = untypable(1, 2, 3, 4, 5);

        new CellTypeClassifierLlm(null, llm, new DynamicKindTokens(dir))
                .withBatching(2, 1)
                .classifyRemaining(cells, settled, ctx);

        assertThat(llm.prompts).hasSize(3); // 2 + 2 + 1
        assertThat(settled.values()).allMatch(o -> "money".equals(o.kind));
    }

    @Test
    void wavesOfBatchesRunConcurrentlyAndEveryCellStillGetsItsOwnAnswer() {
        var inFlight = new java.util.concurrent.atomic.AtomicInteger();
        var peak = new java.util.concurrent.atomic.AtomicInteger();
        var llm = new RecordingLlm(n -> {
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            return "{\"results\":[" + String.join(",", java.util.Collections.nCopies(n,
                    RecordingLlm.item("money", "lakh"))) + "]}";
        });
        var ctx = new TestContext("wb1");
        List<InterpretationCellView> cells = new ArrayList<>();
        for (long id = 1; id <= 6; id++) {
            ctx.cell(id, "Item " + id, "FY23", EXPENSES);
            cells.add(number(id, (int) id));
        }
        var settled = untypable(1, 2, 3, 4, 5, 6);

        new CellTypeClassifierLlm(null, new RecordingLlmThreadSafe(llm), new DynamicKindTokens(dir))
                .withBatching(2, 3)
                .classifyRemaining(cells, settled, ctx);

        assertThat(peak.get()).isGreaterThan(1);
        assertThat(settled.values()).allMatch(o -> "money".equals(o.kind));
    }

    /** Wraps a recording fake so concurrent calls can be recorded safely. */
    static final class RecordingLlmThreadSafe extends CellTypeClassifierLlmTest.FakeClassifierLlm {
        private final RecordingLlm delegate;

        RecordingLlmThreadSafe(RecordingLlm delegate) {
            this.delegate = delegate;
        }

        @Override
        public String classifyCellJson(String systemPrompt, String userPrompt, int maxTokens) {
            synchronized (delegate.prompts) {
                delegate.prompts.add(userPrompt);
            }
            Matcher m = Pattern.compile("Classify the following (\\d+) cells").matcher(userPrompt);
            return delegate.answerN.apply(m.find() ? Integer.parseInt(m.group(1)) : 0);
        }
    }

    @Test
    void onlyCellsOnScopedWorksheetsAreSentToTheModel() {
        var llm = RecordingLlm.always("money", "lakh");
        var ctx = new TestContext("wb1")
                .cell(1, "Fire Fighting Work", "FY23", EXPENSES)
                .cell(2, "Plumbing Works", "FY23", EXPENSES);
        var inScope = number(1, 1);
        var outOfScope = new InterpretationCellView(
                2L, 99L, "C2", 2, 3, "number", "100", "100", "100", null, null, null, null, null,
                null, false, null, false, false, null, "input");
        var settled = untypable(1, 2);

        new CellTypeClassifierLlm(null, llm, new DynamicKindTokens(dir))
                .withWorksheetScope(java.util.Set.of(1L))
                .classifyRemaining(List.of(inScope, outOfScope), settled, ctx);

        assertThat(settled.get(1L).kind).isEqualTo("money");
        assertThat(settled.get(2L).refusal).isEqualTo(ReadingOutcome.UNTYPABLE); // untouched
        assertThat(llm.prompts).hasSize(1);
        assertThat(llm.prompts.get(0)).doesNotContain("Plumbing Works");
    }

    @Test
    void llmAnswersInAKnownRegionAreLearnedOnceTwoWorkbooksAgree() {
        for (String workbook : new String[] {"wb1", "wb2"}) {
            var ctx = new TestContext(workbook).cell(1, "Firefighting & Misc.", "FY23", EXPENSES);
            run(new DynamicKindTokens(dir), RecordingLlm.always("money", "lakh"), ctx, 1);
        }

        assertThat(new DynamicKindTokens(dir).lookup("expenses", "FIREFIGHTING and misc"))
                .hasValueSatisfying(t -> assertThat(t.kind()).isEqualTo("money"));
    }

    @Test
    void nothingIsLearnedWithoutAKnownMainRegion() {
        var scratch = new RegionContext(9L, "expenses", "Scratch work.", "", "S", false);
        for (String workbook : new String[] {"wb1", "wb2"}) {
            var ctx = new TestContext(workbook)
                    .cell(1, "Firefighting & Misc.", "FY23", RegionContext.NONE)
                    .cell(2, "Plumbing Works", "FY23", scratch);
            run(new DynamicKindTokens(dir), RecordingLlm.always("money", "lakh"), ctx, 1, 2);
        }

        var reloaded = new DynamicKindTokens(dir);
        assertThat(reloaded.lookup("expenses", "Firefighting & Misc.")).isEmpty();
        assertThat(reloaded.lookup("expenses", "Plumbing Works")).isEmpty();
    }

    @Test
    void staticallyTypableLabelsAreNotLearned() {
        for (String workbook : new String[] {"wb1", "wb2"}) {
            // The deterministic pass would have typed this, so it can only reach the LLM through a
            // forced path; learnFrom must still refuse it.
            var ctx = new TestContext(workbook).cell(1, "Sales", "FY23", EXPENSES);
            run(new DynamicKindTokens(dir), RecordingLlm.always("money", "lakh"), ctx, 1);
        }

        assertThat(new DynamicKindTokens(dir).lookup("expenses", "Sales")).isEmpty();
    }

    @Test
    void learnedLabelTypesWithoutTheLlmWhenScaleIsStated() {
        teach("Fire Fighting Work");
        var llm = RecordingLlm.always("quantity", "unit");
        var ctx = new TestContext("wb3").cell(1, "Fire Fighting Work", "Figures (in Million)", EXPENSES);

        var settled = runAndSettle(new DynamicKindTokens(dir), llm, ctx, 1);

        assertThat(llm.prompts).isEmpty();
        assertThat(settled.get(1L).kind).isEqualTo("money");
        assertThat(settled.get(1L).scale).isEqualTo(CellScale.MILLION);
        assertThat(settled.get(1L).typeSource).isEqualTo(ReadingOutcome.DERIVED);
    }

    @Test
    void learnedLabelWithoutAStatedScaleFallsThroughToTheLlm() {
        teach("Fire Fighting Work");
        var llm = RecordingLlm.always("money", "lakh");
        var ctx = new TestContext("wb3").cell(1, "Fire Fighting Work", "FY23", EXPENSES);

        var settled = runAndSettle(new DynamicKindTokens(dir), llm, ctx, 1);

        assertThat(llm.prompts).hasSize(1);
        assertThat(settled.get(1L).scale).isEqualTo(CellScale.LAKH);
    }

    @Test
    void learnedLabelIsIgnoredInAnotherFamily() {
        teach("Fire Fighting Work");
        var llm = RecordingLlm.always("count", "unit");
        var ctx = new TestContext("wb3")
                .cell(1, "Fire Fighting Work", "Figures (in Million)",
                        new RegionContext(7L, "capacity", "Seat counts.", "", "S", true));

        var settled = runAndSettle(new DynamicKindTokens(dir), llm, ctx, 1);

        assertThat(llm.prompts).hasSize(1);
        assertThat(settled.get(1L).kind).isEqualTo("count");
    }

    @Test
    void llmAnswersWhoseCountDoesNotMatchAreRetriedPerCell() {
        var llm = new RecordingLlm(n -> n == 0
                ? RecordingLlm.item("money", "lakh")
                : "{\"results\":[" + RecordingLlm.item("money", "lakh") + "]}");
        var ctx = new TestContext("wb1")
                .cell(1, "Fire Fighting Work", "FY23", EXPENSES)
                .cell(2, "Plumbing Works", "FY23", EXPENSES);

        var settled = runAndSettle(new DynamicKindTokens(dir), llm, ctx, 1, 2);

        assertThat(settled.get(1L).kind).isEqualTo("money");
        assertThat(settled.get(2L).kind).isEqualTo("money");
        assertThat(llm.prompts).hasSize(3); // one batch, two single retries
    }

    @Test
    void oddCaseKindAndScaleSpellingsAreAccepted() {
        var llm = RecordingLlm.always("Money", "lacs");
        var ctx = new TestContext("wb1").cell(1, "Fire Fighting Work", "FY23", EXPENSES);

        var settled = runAndSettle(new DynamicKindTokens(dir), llm, ctx, 1);

        assertThat(settled.get(1L).kind).isEqualTo("money");
        assertThat(settled.get(1L).scale).isEqualTo(CellScale.LAKH);
    }

    @Test
    void anUnknownScaleLeavesTheCellUntyped() {
        var llm = RecordingLlm.always("money", "hundreds");
        var ctx = new TestContext("wb1").cell(1, "Fire Fighting Work", "FY23", EXPENSES);

        var settled = runAndSettle(new DynamicKindTokens(dir), llm, ctx, 1);

        assertThat(settled.get(1L).refusal).isEqualTo(ReadingOutcome.UNTYPABLE);
    }

    @Test
    void cellsWithNoLabelsAtAllStillClassify() {
        var llm = RecordingLlm.always("money", "unit");
        var settled = runAndSettle(new DynamicKindTokens(dir), llm, new TestContext("wb1"), 1);

        assertThat(settled.get(1L).kind).isEqualTo("money");
    }

    private static String numbered(int cell, String kind) {
        return "{\"cell\":" + cell + ",\"kind\":\"" + kind
                + "\",\"scale\":\"unit\",\"unit\":\"\",\"currency\":\"\",\"confidence\":0.95}";
    }

    private Map<Long, ReadingOutcome> classifyFive(java.util.function.Function<Integer, String> answerN, RecordingLlm[] holder) {
        var llm = new RecordingLlm(answerN);
        holder[0] = llm;
        var ctx = new TestContext("wb1");
        List<InterpretationCellView> cells = new ArrayList<>();
        for (long id = 1; id <= 5; id++) {
            ctx.cell(id, "Item " + id, "FY23", EXPENSES);
            cells.add(number(id, (int) id));
        }
        var settled = untypable(1, 2, 3, 4, 5);
        new CellTypeClassifierLlm(null, llm, new DynamicKindTokens(dir)).classifyRemaining(cells, settled, ctx);
        return settled;
    }

    @Test
    void aMissingAnswerRetriesOnlyThatCellNotTheWholeBatch() {
        RecordingLlm[] llm = new RecordingLlm[1];
        // batch answer skips cell 3; the single retry (n == 0) answers it as a count
        var settled = classifyFive(n -> n == 0
                ? numbered(0, "count")
                : "{\"results\":[" + String.join(",", numbered(1, "money"), numbered(2, "money"),
                        numbered(4, "money"), numbered(5, "money")) + "]}", llm);

        assertThat(llm[0].prompts).hasSize(2); // one batch + one single, not 1 + 5
        assertThat(settled.get(1L).kind).isEqualTo("money");
        assertThat(settled.get(3L).kind).isEqualTo("count");
        assertThat(settled.get(5L).kind).isEqualTo("money");
    }

    @Test
    void extraAndDuplicateAnswersAreIgnoredWithoutRetrying() {
        RecordingLlm[] llm = new RecordingLlm[1];
        var settled = classifyFive(n -> "{\"results\":[" + String.join(",",
                numbered(1, "money"), numbered(2, "money"), numbered(2, "count"), numbered(3, "money"),
                numbered(4, "money"), numbered(5, "money"), numbered(6, "money")) + "]}", llm);

        assertThat(llm[0].prompts).hasSize(1);
        assertThat(settled.get(2L).kind).isEqualTo("money"); // first answer for a cell wins
        assertThat(settled.values()).allMatch(o -> o.typed());
    }

    @Test
    void answersAreMatchedByCellNumberNotByPosition() {
        RecordingLlm[] llm = new RecordingLlm[1];
        var settled = classifyFive(n -> "{\"results\":[" + String.join(",",
                numbered(5, "percent"), numbered(4, "count"), numbered(3, "quantity"),
                numbered(2, "ratio"), numbered(1, "money")) + "]}", llm);

        assertThat(llm[0].prompts).hasSize(1);
        assertThat(settled.get(1L).kind).isEqualTo("money");
        assertThat(settled.get(3L).kind).isEqualTo("quantity");
        assertThat(settled.get(5L).kind).isEqualTo("percent");
    }

    @Test
    void promptAsksForTheCellNumberInEveryAnswer() {
        RecordingLlm[] llm = new RecordingLlm[1];
        classifyFive(n -> "{\"results\":[" + String.join(",", numbered(1, "money"), numbered(2, "money"),
                numbered(3, "money"), numbered(4, "money"), numbered(5, "money")) + "]}", llm);

        assertThat(llm[0].prompts.get(0)).contains("\"cell\"");
    }

    private void teach(String label) {
        for (String workbook : new String[] {"wb1", "wb2"}) {
            var ctx = new TestContext(workbook).cell(1, label, "FY23", EXPENSES);
            run(new DynamicKindTokens(dir), RecordingLlm.always("money", "lakh"), ctx, 1);
        }
    }
}
