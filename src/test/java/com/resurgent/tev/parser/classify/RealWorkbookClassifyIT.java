package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.discover.PacketCell;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import com.resurgent.tev.parser.nomenclature.NomenclatureCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves Layer A classify against the working client FM under {@code Project Docs/}.
 * Skips when that file is absent. Fake LLM only — no live provider, no gold-filed
 * schedule families or financial amounts as expected values.
 */
class RealWorkbookClassifyIT {

    private static final Path WORKBOOK =
            Path.of("Project Docs", "OM Arham Ventures.xlsx");
    private static final String AC_TEAROUT_LABEL =
            "Less : AC as per Quotation included Below";
    private static final String HOTEL_AC_PATH =
            "Project Cost > Plant & Machinery > Air Conditioning";

    @TempDir
    static Path tempDir;

    private static Path db;
    private static long parseRunId;
    private static List<CandidateRow> candidatesBefore;
    private static Map<Long, List<Long>> membersBefore;
    private static Set<String> sentinelAmounts;
    private static ClassifySummary summary;
    private static RecordingLlm llm;

    @BeforeAll
    static void ingestDiscoverClassifyOnce() throws Exception {
        assumeTrue(Files.exists(WORKBOOK),
                "Working workbook not found at " + WORKBOOK.toAbsolutePath()
                        + " -- place the client FM at Project Docs/OM Arham Ventures.xlsx"
                        + " to run this integration test; skipping.");
        db = tempDir.resolve("real-workbook-classify.db");
        IngestSummary ingest = new IngestService().ingest(WORKBOOK, 1L, db);
        parseRunId = ingest.parseRunId();
        new DiscoverService().discover(db, parseRunId);

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            candidatesBefore = List.copyOf(repo.selectCandidatesForParseRun(parseRunId));
            membersBefore = new HashMap<>();
            for (CandidateRow row : candidatesBefore) {
                membersBefore.put(row.candidateId(),
                        List.copyOf(repo.selectCandidateMemberCellIds(row.candidateId())));
            }
            sentinelAmounts = distinctiveAmounts(workspace);
            new NomenclatureCatalog(repo).confirmIndustry(1L, "hotel");
        }

        llm = new RecordingLlm(sentinelAmounts);
        summary = new ClassifyService(llm).classify(db, parseRunId);
    }

    @Test
    void oneDispositionPerCandidateWithCoverageParentCheapPass() throws Exception {
        assertThat(candidatesBefore).isNotEmpty();
        assertThat(summary.dispositionCount()).isEqualTo(candidatesBefore.size());
        long coverageParents = candidatesBefore.stream()
                .filter(c -> "coverage_parent".equals(c.candidateKind()))
                .count();
        assertThat(summary.coverageParentCount()).isEqualTo(coverageParents);
        assertThat(llm.cheapPassFlags).hasSize(candidatesBefore.size());

        int firstChild = llm.cheapPassFlags.indexOf(Boolean.FALSE);
        assertThat(firstChild).isGreaterThan(0);
        assertThat(llm.cheapPassFlags.subList(0, firstChild)).containsOnly(true);
        assertThat(llm.cheapPassFlags.subList(firstChild, llm.cheapPassFlags.size()))
                .containsOnly(false);
        assertThat(llm.cheapPassHadBareAmountLine).isFalse();
        assertThat(llm.childMissingParent).isFalse();

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<PacketDisposition> rows = repo.selectPacketDispositionsForParseRun(parseRunId);
            assertThat(rows).hasSize(candidatesBefore.size());
            assertThat(rows.stream().filter(PacketDisposition::cheapPass).count())
                    .isEqualTo(coverageParents);
            assertThat(rows.stream().filter(row -> !row.cheapPass())).isNotEmpty();
        }
    }

    @Test
    void sentPacketsAreNumberRedactedAndOmArhamAmountsStayOnTheCellGraph() throws Exception {
        assertThat(sentinelAmounts).isNotEmpty();
        assertThat(llm.leakedAmounts).isEmpty();
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db);
                ResultSet rs = workspace.connection().createStatement().executeQuery(
                        "SELECT numeric_value FROM cell WHERE numeric_value IS NOT NULL")) {
            Set<String> remaining = new HashSet<>();
            while (rs.next()) {
                String value = rs.getString(1);
                if (value != null) {
                    remaining.add(value);
                    int dot = value.indexOf('.');
                    remaining.add(dot < 0 ? value : value.substring(0, dot));
                }
            }
            assertThat(remaining).containsAll(sentinelAmounts);
        }
    }

    @Test
    void omArhamLabelsAndHotelSliceReachTheLlm() {
        assertThat(llm.sawAcTearoutLabel)
                .as("AC tear-out label must survive redaction into a Packet prompt")
                .isTrue();
        assertThat(llm.sawProjectCostNode).isTrue();
        assertThat(llm.sawHotelAcLeaf)
                .as("confirmed hotel slice must include P&M Air Conditioning")
                .isTrue();
    }

    @Test
    void cellMeaningLookupRunsAfterClassifyWithoutAmountOracles() throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            long worksheetId = repo.selectWorksheetsForParseRun(parseRunId).stream()
                    .filter(w -> "ASSETS".equalsIgnoreCase(w.sheetName()))
                    .findFirst()
                    .orElseThrow()
                    .worksheetId();
            String anyCoord = repo.selectCellsForWorksheet(worksheetId).stream()
                    .map(c -> c.coord())
                    .findFirst()
                    .orElseThrow();
            CellMeaning meaning = new CellMeaningService().lookup(db, parseRunId, "ASSETS!" + anyCoord);
            assertThat(meaning.cell()).isNotNull();
            assertThat(meaning.qualifiedCoord()).startsWith("ASSETS!");
        }
    }

    @Test
    void classifyDoesNotRewriteCandidateGeometry() throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<CandidateRow> after = repo.selectCandidatesForParseRun(parseRunId);
            assertThat(after).isEqualTo(candidatesBefore);
            for (CandidateRow row : after) {
                assertThat(repo.selectCandidateMemberCellIds(row.candidateId()))
                        .isEqualTo(membersBefore.get(row.candidateId()));
            }
        }
    }


    @Test
    void noRowCarriesMoreThanOnePathAcrossItsPeriodSeries() throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            Map<Long, com.resurgent.tev.parser.db.InterpretationCellView> cells =
                    repo.selectInterpretationCellsForParseRun(parseRunId).stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    com.resurgent.tev.parser.db.InterpretationCellView::cellId,
                                    cell -> cell));
            Map<String, Set<String>> pathsByRow = new HashMap<>();
            for (NomenclatureBinding binding : repo.selectNomenclatureBindingsForParseRun(
                    parseRunId)) {
                var cell = cells.get(binding.cellId());
                if (cell == null) {
                    continue;
                }
                pathsByRow
                        .computeIfAbsent(cell.worksheetId() + "!" + cell.rowNum(),
                                key -> new HashSet<>())
                        .add(binding.path());
            }
            assertThat(pathsByRow.values())
                    .as("a row means one thing across its period columns")
                    .allSatisfy(paths -> assertThat(paths).hasSize(1));
        }
    }

    @Test
    void noLabelIsGivenConflictingRolesWithinTheRun() throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            Map<Long, Set<Long>> membersByHead = new HashMap<>();
            for (AggregationRow aggregation : repo.selectAggregationsForParseRun(parseRunId)) {
                Set<Long> members = new HashSet<>();
                for (AggregationMemberRow member
                        : repo.selectAggregationMembers(aggregation.aggregationId())) {
                    members.add(member.memberCellId());
                }
                membersByHead.put(aggregation.headCellId(), members);
            }
            Map<String, Set<Long>> cellsByLabel = new HashMap<>();
            Map<String, Set<String>> rolesByLabel = new HashMap<>();
            for (NomenclatureBinding binding : repo.selectNomenclatureBindingsForParseRun(
                    parseRunId)) {
                if (binding.labelKey() == null) {
                    continue;
                }
                cellsByLabel
                        .computeIfAbsent(binding.labelKey(), key -> new HashSet<>())
                        .add(binding.cellId());
                rolesByLabel
                        .computeIfAbsent(binding.labelKey(), key -> new HashSet<>())
                        .add(binding.amountRole());
            }
            for (Map.Entry<String, Set<String>> entry : rolesByLabel.entrySet()) {
                if (entry.getValue().size() <= 1) {
                    continue;
                }
                assertThat(isOneHeadAndItsOwnMembers(
                                cellsByLabel.get(entry.getKey()), membersByHead))
                        .as("label %s carries roles %s outside one aggregation",
                                entry.getKey(), entry.getValue())
                        .isTrue();
            }
        }
    }

    /**
     * A label may carry several roles only when its cells are one aggregation's head
     * and that head's own members — the gross, deduction and net of one row. The same
     * label spread across unrelated groups is still a conflict.
     */
    private static boolean isOneHeadAndItsOwnMembers(
            Set<Long> cells, Map<Long, Set<Long>> membersByHead) {
        for (Map.Entry<Long, Set<Long>> head : membersByHead.entrySet()) {
            if (!cells.contains(head.getKey())) {
                continue;
            }
            Set<Long> group = new HashSet<>(head.getValue());
            group.add(head.getKey());
            if (group.containsAll(cells)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aCellInTwoCandidatesGetsExactlyOneBinding() throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<NomenclatureBinding> bindings =
                    repo.selectNomenclatureBindingsForParseRun(parseRunId);
            assertThat(bindings).extracting(NomenclatureBinding::cellId).doesNotHaveDuplicates();
        }
    }

    @Test
    void everyUnboundNumericCellCarriesAReason() throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<CellInterpretation> unbound =
                    repo.selectCellInterpretationsForParseRun(parseRunId).stream()
                            .filter(row -> NomenclatureStatus.UNBOUND.equals(
                                    row.nomenclatureStatus()))
                            .toList();
            assertThat(unbound).isNotEmpty();
            long withReason = unbound.stream()
                    .filter(row -> row.unboundReason() != null && !row.unboundReason().isBlank())
                    .count();
            assertThat(unbound.stream()
                    .filter(row -> row.unboundReason() != null)
                    .map(CellInterpretation::unboundReason))
                    .allMatch(reason -> UnboundReason.wireNames().contains(reason));
            assertThat(withReason)
                    .as("coverage comes from what can be proven; the rest says why not")
                    .isEqualTo(unbound.size());
            assertThat(unbound.stream()
                    .map(CellInterpretation::unboundReason)
                    .distinct()
                    .toList())
                    .as("reasons are specific, not one blanket default")
                    .hasSizeGreaterThan(1);
        }
    }

    @Test
    void noMemberOfANonMoneyAggregationCarriesACostRole() throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            Map<Long, String> roleByCell = new HashMap<>();
            for (NomenclatureBinding binding : repo.selectNomenclatureBindingsForParseRun(
                    parseRunId)) {
                roleByCell.put(binding.cellId(), binding.amountRole());
            }
            for (AggregationRow aggregation : repo.selectAggregationsForParseRun(parseRunId)) {
                if (aggregation.resolvedKind() == null
                        || aggregation.resolvedKind().allowsCostRole()) {
                    continue;
                }
                for (AggregationMemberRow member
                        : repo.selectAggregationMembers(aggregation.aggregationId())) {
                    assertThat(roleByCell.get(member.memberCellId()))
                            .as("a guest count is not a cost, whatever its sign")
                            .isNotIn(AmountRole.ADD, AmountRole.DEDUCT, AmountRole.TOTAL);
                }
            }
        }
    }

    @Test
    void layerBOutcomeIsReportedForTheRun() throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<NomenclatureBinding> bindings =
                    repo.selectNomenclatureBindingsForParseRun(parseRunId);
            Map<String, Long> bySource = new HashMap<>();
            Map<String, Long> byRole = new HashMap<>();
            for (NomenclatureBinding binding : bindings) {
                bySource.merge(binding.source(), 1L, Long::sum);
                byRole.merge(binding.amountRole(), 1L, Long::sum);
            }
            Map<String, Long> reasons = new HashMap<>();
            long unboundInBoundRow = 0;
            Map<String, Long> boundRows = new HashMap<>();
            Map<Long, com.resurgent.tev.parser.db.InterpretationCellView> cells =
                    repo.selectInterpretationCellsForParseRun(parseRunId).stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    com.resurgent.tev.parser.db.InterpretationCellView::cellId,
                                    cell -> cell));
            for (NomenclatureBinding binding : bindings) {
                var cell = cells.get(binding.cellId());
                if (cell != null) {
                    boundRows.merge(cell.worksheetId() + "!" + cell.rowNum(), 1L, Long::sum);
                }
            }
            for (CellInterpretation row : repo.selectCellInterpretationsForParseRun(parseRunId)) {
                if (!NomenclatureStatus.UNBOUND.equals(row.nomenclatureStatus())) {
                    continue;
                }
                reasons.merge(row.unboundReason(), 1L, Long::sum);
                var cell = cells.get(row.cellId());
                if (cell != null && cell.numericValue() != null
                        && boundRows.containsKey(cell.worksheetId() + "!" + cell.rowNum())) {
                    unboundInBoundRow++;
                }
            }
            System.out.println("layer-b outcome: bindings=" + bindings.size()
                    + " bySource=" + bySource
                    + " byRole=" + byRole
                    + " boundRows=" + boundRows.size()
                    + " unboundNumericInABoundRow=" + unboundInBoundRow);
            Set<String> awaitingNames = new HashSet<>();
            for (CellInterpretation row : repo.selectCellInterpretationsForParseRun(parseRunId)) {
                if (!UnboundReason.LLM_DECLINED.wireName().equals(row.unboundReason())) {
                    continue;
                }
                var cell = cells.get(row.cellId());
                if (cell != null) {
                    awaitingNames.add(cell.worksheetId() + "!" + cell.rowNum());
                }
            }
            System.out.println("layer-b unbound reasons: " + reasons);
            System.out.println("layer-b rows awaiting a name: " + awaitingNames.size());

            // This run uses a fake model that answers no Layer B line, so what binds
            // here is exactly what the graph proves on its own. Coverage beyond this
            // comes from the group-level naming question, which needs a live model.
            assertThat(bindings).isNotEmpty();
            assertThat(byRole.getOrDefault(AmountRole.ADD, 0L))
                    .as("add bindings now exist, so a leaf rollup means something")
                    .isNotZero();
            assertThat(bySource.getOrDefault(BindingSource.AGGREGATION_HEAD, 0L)
                    + bySource.getOrDefault(BindingSource.DERIVED, 0L))
                    .as("a formula cell can finally carry a graph-proven role")
                    .isNotZero();
        }
    }

    @Test
    void layoutDefeatersAreNotBoundAsThePresentationApproachWouldHave() throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            NomenclatureBinding j45 = bindingAt(repo, "P  L ", "J45");
            if (j45 != null) {
                assertThat(j45.path())
                        .as("'P  L '!J45 is operating cost, not a Civil Works asset")
                        .doesNotContain("Civil Works > Building");
            }
            NomenclatureBinding j33 = bindingAt(repo, "depreciation", "J33");
            if (j33 != null) {
                assertThat(j33.path().toLowerCase()).doesNotContain("furniture");
            }
            assertThat(roleAt(repo, "power cost", "J20"))
                    .as("power factor is a driver")
                    .isNotIn(AmountRole.ADD, AmountRole.DEDUCT, AmountRole.TOTAL);
            assertThat(roleAt(repo, "SALESPROJECTION", "G15"))
                    .as("tariff is a driver")
                    .isNotIn(AmountRole.ADD, AmountRole.DEDUCT, AmountRole.TOTAL);
            assertThat(roleAt(repo, "SALESPROJECTION", "H15"))
                    .as("guest-nights sit in a count group")
                    .isNotIn(AmountRole.ADD, AmountRole.DEDUCT, AmountRole.TOTAL);
        }
    }

    private static String roleAt(
            WorkspaceRepository repo, String sheetName, String coord) throws Exception {
        NomenclatureBinding binding = bindingAt(repo, sheetName, coord);
        return binding == null ? null : binding.amountRole();
    }

    private static NomenclatureBinding bindingAt(
            WorkspaceRepository repo, String sheetName, String coord) throws Exception {
        Long worksheetId = repo.selectWorksheetsForParseRun(parseRunId).stream()
                .filter(sheet -> sheetName.equalsIgnoreCase(sheet.sheetName().trim())
                        || sheetName.equalsIgnoreCase(sheet.sheetName()))
                .map(com.resurgent.tev.parser.db.WorksheetRef::worksheetId)
                .findFirst()
                .orElse(null);
        if (worksheetId == null) {
            return null;
        }
        Long cellId = repo.selectInterpretationCellsForParseRun(parseRunId).stream()
                .filter(cell -> cell.worksheetId() == worksheetId
                        && coord.equalsIgnoreCase(cell.coord()))
                .map(com.resurgent.tev.parser.db.InterpretationCellView::cellId)
                .findFirst()
                .orElse(null);
        if (cellId == null) {
            return null;
        }
        return repo.selectNomenclatureBindingsForParseRun(parseRunId).stream()
                .filter(binding -> binding.cellId() == cellId)
                .findFirst()
                .orElse(null);
    }

    @Test
    void theGraphAndItsTypingAreWrittenForTheRun() throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            assertThat(repo.selectAggregationsForParseRun(parseRunId)).isNotEmpty();
            assertThat(repo.selectCellTypesForParseRun(parseRunId)).isNotEmpty();
        }
    }

    private static Set<String> distinctiveAmounts(WorkspaceDatabase workspace) throws Exception {
        Set<String> sentinels = new LinkedHashSet<>();
        try (ResultSet rs = workspace.connection().createStatement().executeQuery(
                "SELECT numeric_value FROM cell WHERE numeric_value IS NOT NULL")) {
            while (rs.next()) {
                String value = rs.getString(1);
                if (value == null) {
                    continue;
                }
                int dot = value.indexOf('.');
                String integerPart = dot < 0 ? value : value.substring(0, dot);
                if (integerPart.startsWith("-")) {
                    integerPart = integerPart.substring(1);
                }
                if (integerPart.length() >= 7) {
                    sentinels.add(value);
                    sentinels.add(integerPart);
                }
            }
        }
        return Set.copyOf(sentinels);
    }

    /** Records Layer A prompts without retaining Packet cells (Om Arham coverage parents are large). */
    static final class RecordingLlm implements ClassifierLlm {
        private final Set<String> sentinelAmounts;
        // classify calls Layer A from a pool, so every recorder here is shared state.
        final List<Boolean> cheapPassFlags = Collections.synchronizedList(new ArrayList<>());
        final Set<String> leakedAmounts =
                Collections.synchronizedSet(new LinkedHashSet<>());
        volatile boolean cheapPassHadBareAmountLine;
        volatile boolean childMissingParent;
        volatile boolean sawAcTearoutLabel;
        volatile boolean sawProjectCostNode;
        volatile boolean sawHotelAcLeaf;

        RecordingLlm(Set<String> sentinelAmounts) {
            this.sentinelAmounts = sentinelAmounts;
        }

        @Override
        public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
            cheapPassFlags.add(prompt.cheapPass());
            if (prompt.cheapPass()) {
                for (PacketCell cell : prompt.packet().cells()) {
                    if (isBareAmountLine(cell)) {
                        cheapPassHadBareAmountLine = true;
                    }
                }
            } else if (prompt.parentDisposition() == null) {
                childMissingParent = true;
            }
            if (prompt.ontologySlice().node("Project Cost").isPresent()) {
                sawProjectCostNode = true;
            }
            if (prompt.ontologySlice().node(HOTEL_AC_PATH).isPresent()) {
                sawHotelAcLeaf = true;
            }
            for (PacketCell cell : prompt.packet().cells()) {
                if (AC_TEAROUT_LABEL.equals(cell.textValue())) {
                    sawAcTearoutLabel = true;
                }
                leakIfPresent(cell.numericValue());
                leakIfPresent(cell.displayValue());
                leakIfPresent(cell.textValue());
            }
            return new LayerAJudgment(
                    ScheduleFamily.CAPEX_DETAIL,
                    Triage.MAIN,
                    Relevance.PRIMARY,
                    List.of(),
                    List.of(),
                    null);
        }

        @Override
        public List<LayerBLineJudgment> classifyLayerB(LayerBPrompt prompt) {
            for (PacketCell cell : prompt.packet().cells()) {
                leakIfPresent(cell.numericValue());
                leakIfPresent(cell.displayValue());
                leakIfPresent(cell.textValue());
            }
            return List.of();
        }

        private static boolean isBareAmountLine(PacketCell cell) {
            if (cell.formulaText() != null && !cell.formulaText().isBlank()) {
                return false;
            }
            return "number".equals(cell.valueType());
        }

        private void leakIfPresent(String value) {
            if (value == null || value.isEmpty()) {
                return;
            }
            for (String sentinel : sentinelAmounts) {
                if (value.contains(sentinel)) {
                    leakedAmounts.add(sentinel);
                }
            }
        }
    }
}