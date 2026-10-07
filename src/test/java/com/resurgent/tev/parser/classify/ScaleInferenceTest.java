package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ScaleInferenceTest {

    private static InterpretationCellView cell(long id, long sheet, int row, String value) {
        return new InterpretationCellView(id, sheet, "C" + row, row, 3, "number", null, value, value, null, null,
                null, null, null, null, false, null, false, false, null, null);
    }

    /** A workbook whose "Summary" sheet states crores (ids 1-6), and a "Costs" sheet that states nothing. */
    private final List<InterpretationCellView> cells = new ArrayList<>();
    private final Map<Long, ReadingOutcome> settled = new HashMap<>();
    private final Map<Long, String> sheets = Map.of(1L, "Summary", 2L, "Costs");
    private final Map<Long, String[]> labels = new HashMap<>();
    private final UnstatedScales unknowns = new UnstatedScales();

    private ScaleInferenceTest withAnchors() {
        String[] values = {"61.97", "47.79", "30.20", "13.27", "9.19", "5.38"};
        for (int i = 0; i < values.length; i++) {
            InterpretationCellView c = cell(i + 1, 1L, i + 1, values[i]);
            cells.add(c);
            settled.put(c.cellId(), ReadingOutcome.typed(
                    ReadingOutcome.MONEY, CellScale.CRORE, "", "INR", ReadingOutcome.INPUT));
            labels.put(c.cellId(), new String[] {"Revenue", "Y1"});
        }
        return this;
    }

    private long unstated(long id, int row, String value, String rowLabel) {
        InterpretationCellView c = cell(id, 2L, row, value);
        cells.add(c);
        settled.put(id, ReadingOutcome.unstated("", "INR", ReadingOutcome.DERIVED, unknowns.fresh(id)));
        labels.put(id, new String[] {rowLabel, "Y1"});
        return id;
    }

    private final List<String> threadsReadingLabels = new ArrayList<>();

    private CellContext context() {
        return new CellContext() {
            @Override
            public String rowLabel(InterpretationCellView c) {
                threadsReadingLabels.add(Thread.currentThread().getName());
                return labels.get(c.cellId())[0];
            }

            @Override
            public String columnLabel(InterpretationCellView c) {
                return labels.get(c.cellId())[1];
            }

            @Override
            public RegionContext region(InterpretationCellView c) {
                return RegionContext.NONE;
            }

            @Override
            public String workbookKey() {
                return "wb";
            }
        };
    }

    private static ClassifierLlm answering(Function<String, String> reply, List<String> prompts) {
        return new ClassifierLlm() {
            @Override
            public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) {
                return List.of();
            }

            @Override
            public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
                return null;
            }

            @Override
            public String classifyCellJson(String systemPrompt, String userPrompt, int maxTokens) {
                prompts.add(userPrompt);
                return reply.apply(userPrompt);
            }
        };
    }

    private static String answer(String scale, double confidence, String reason) {
        return "{\"groups\":[{\"id\":1,\"scale\":\"" + scale + "\",\"confidence\":" + confidence
                + ",\"reason\":\"" + reason + "\"}]}";
    }

    private Map<Long, ScaleInference.Inference> run(ClassifierLlm llm) {
        return ScaleInference.apply(cells, settled, unknowns, sheets, context(), llm, 1);
    }

    @Test
    void anOperatingCostFarBelowTheWorkbooksMoneyIsReadInTheScaleItStates() {
        withAnchors();
        long om = unstated(100, 11, "0.7448", "O&M Cost");
        List<String> prompts = new ArrayList<>();
        var chosen = run(answering(p -> answer("crore", 0.92, "yearly operating cost of a plant"), prompts));

        assertThat(chosen).containsKey(om);
        assertThat(settled.get(om).scale).isEqualTo(CellScale.CRORE);
        assertThat(settled.get(om).scaleInferred).isTrue();
        assertThat(chosen.get(om).evidence()).contains("yearly operating cost").contains("crore x6");
        // The model is shown the labels, the values and what the workbook states.
        assertThat(prompts.get(0)).contains("O&M Cost").contains("0.7448").contains("crore");
    }

    @Test
    void anAmountOfOneItemCanBePlainUnitsWhateverTheTotalsAre() {
        withAnchors();
        long salary = unstated(100, 11, "100000", "Salary per employee");
        var chosen = run(answering(p -> answer("unit", 0.95, "salary of one employee"), new ArrayList<>()));

        assertThat(chosen).containsKey(salary);
        assertThat(settled.get(salary).scale).isEqualTo(CellScale.UNIT);
    }

    @Test
    void aSalaryInHundredsOfThousandsOfCroresIsRejectedWhateverTheModelSays() {
        withAnchors();
        long salary = unstated(100, 11, "100000", "Salary per employee");
        var chosen = run(answering(p -> answer("crore", 0.99, "the workbook is in crores"), new ArrayList<>()));

        assertThat(chosen).isEmpty();
        assertThat(settled.get(salary).scaleUnstated()).isTrue();
    }

    @Test
    void anAmountThatWouldBeLessThanOneUnitIsRejected() {
        withAnchors();
        long om = unstated(100, 11, "0.7448", "O&M Cost");
        var chosen = run(answering(p -> answer("unit", 0.99, "plain rupees"), new ArrayList<>()));

        assertThat(chosen).isEmpty();
        assertThat(settled.get(om).scaleUnstated()).isTrue();
    }

    @Test
    void aScaleTheWorkbookNeverStatesIsNotAllowedExceptPlainUnits() {
        withAnchors();
        long om = unstated(100, 11, "70000", "O&M Cost");
        var chosen = run(answering(p -> answer("lakh", 0.99, "looks like lakhs"), new ArrayList<>()));

        assertThat(chosen).isEmpty();
        assertThat(settled.get(om).scaleUnstated()).isTrue();
    }

    @Test
    void unknownLowConfidenceOrNoReasonLeavesTheMoneyUnknown() {
        withAnchors();
        long a = unstated(100, 11, "0.7", "A");
        for (String reply : new String[] {
                answer("unknown", 0.99, "cannot tell"), answer("crore", 0.6, "maybe"), answer("crore", 0.95, "")}) {
            assertThat(run(answering(p -> reply, new ArrayList<>()))).isEmpty();
            assertThat(settled.get(a).scaleUnstated()).isTrue();
        }
    }

    @Test
    void noModelAFailedCallOrNothingStatedMeansNoChange() {
        withAnchors();
        long om = unstated(100, 11, "0.7448", "O&M Cost");
        assertThat(run(null)).isEmpty();
        assertThat(run(answering(p -> {
            throw new IllegalStateException("down");
        }, new ArrayList<>()))).isEmpty();
        assertThat(settled.get(om).scaleUnstated()).isTrue();

        // A workbook that states no scale gives nothing to compare with, so nothing is asked.
        ScaleInferenceTest bare = new ScaleInferenceTest();
        bare.unstated(100, 11, "0.7448", "O&M Cost");
        List<String> prompts = new ArrayList<>();
        assertThat(bare.run(answering(p -> answer("crore", 0.99, "x"), prompts))).isEmpty();
        assertThat(prompts).isEmpty();
    }

    @Test
    void aGroupAnArithmeticLinkAlreadyPinnedIsNotAskedAbout() {
        withAnchors();
        long linked = unstated(100, 11, "0.7448", "Linked");
        unknowns.require(settled.get(linked).scaleUnknown, CellScale.LAKH, 100);
        List<String> prompts = new ArrayList<>();
        run(answering(p -> answer("crore", 0.99, "x"), prompts));
        assertThat(prompts).isEmpty();
    }

    @Test
    void labelsAreReadOnTheCallingThreadSoManyBatchesCanRunInParallel() {
        withAnchors();
        for (int i = 0; i < 60; i++) { // three batches of 25 groups' worth of questions
            unstated(100 + i, 20 + i, "0.5", "Cost " + i);
        }
        String caller = Thread.currentThread().getName();
        ScaleInference.apply(cells, settled, unknowns, sheets, context(),
                answering(p -> "{\"groups\":[]}", new ArrayList<>()), 4);

        // The label cache behind the real context is not safe to share between threads.
        assertThat(threadsReadingLabels).isNotEmpty().allMatch(caller::equals);
    }
}
