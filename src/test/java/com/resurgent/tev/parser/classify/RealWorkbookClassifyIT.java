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
        final List<Boolean> cheapPassFlags = new ArrayList<>();
        final Set<String> leakedAmounts = new LinkedHashSet<>();
        boolean cheapPassHadBareAmountLine;
        boolean childMissingParent;
        boolean sawAcTearoutLabel;
        boolean sawProjectCostNode;
        boolean sawHotelAcLeaf;

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
