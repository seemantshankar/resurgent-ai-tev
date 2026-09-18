package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.discover.PacketCell;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import com.resurgent.tev.parser.nomenclature.NomenclatureCatalog;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seam: {@link ClassifyService} — Layer A disposition and Layer B bindings from
 * derived Packets plus a fake LLM, without rewriting Candidate geometry (#106/#107).
 */
class ClassifyServiceTest {

    @TempDir
    Path tempDir;

    private Path writeWorkbook(XSSFWorkbook workbook, String name) throws Exception {
        Path file = tempDir.resolve(name);
        try (FileOutputStream out = new FileOutputStream(file.toFile())) {
            workbook.write(out);
        }
        workbook.close();
        return file;
    }

    private Path costScheduleWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");
            Row body = sheet.createRow(1);
            body.createCell(0).setCellValue("Civil Works");
            body.createCell(1).setCellValue(100.0);
            return writeWorkbook(workbook, "costs.xlsx");
        }
    }

    @Test
    void classifyPersistsLayerADispositionForEachCandidate() throws Exception {
        Path xlsx = costScheduleWorkbook();
        Path db = tempDir.resolve("classify.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.judgment = new LayerAJudgment(
                ScheduleFamily.CAPEX_DETAIL,
                Triage.MAIN,
                Relevance.PRIMARY,
                List.of("Item"),
                List.of("Amount"),
                "Project Cost");

        ClassifySummary summary = new ClassifyService(llm).classify(db, ingest.parseRunId());

        assertThat(summary.parseRunId()).isEqualTo(ingest.parseRunId());
        assertThat(summary.dispositionCount()).isGreaterThanOrEqualTo(1);
        assertThat(llm.prompts).isNotEmpty();
        assertThat(llm.prompts.get(0).ontologySlice().node("Project Cost")).isPresent();

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<CandidateRow> candidates = repo.selectCandidatesForParseRun(ingest.parseRunId());
            List<PacketDisposition> rows = repo.selectPacketDispositionsForParseRun(
                    ingest.parseRunId());
            assertThat(rows).hasSize(candidates.size());
            assertThat(rows).allMatch(row -> ScheduleFamily.CAPEX_DETAIL.equals(row.scheduleFamily()));
            assertThat(rows).allMatch(row -> Triage.MAIN.equals(row.triage()));
            assertThat(rows).allMatch(row -> Relevance.PRIMARY.equals(row.relevance()));
            assertThat(rows.get(0).rowLabels()).contains("Item");
            assertThat(rows.get(0).columnHeaders()).contains("Amount");
            assertThat(rows.get(0).packetDefaultHead()).isEqualTo("Project Cost");
        }
    }

    @Test
    void sentPacketsAreNumberRedactedAndAmountsStayOnTheCellGraph() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Parallel");
            for (int r = 0; r < 3; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue("L" + r);
                row.createCell(1).setCellValue(r == 0 ? 9_998_887.0 : 10.0 + r);
                row.createCell(4).setCellValue("R" + r);
                row.createCell(5).setCellValue(20.0 + r);
            }
            xlsx = writeWorkbook(workbook, "redact.xlsx");
        }
        Path db = tempDir.resolve("redact.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        FakeClassifierLlm llm = new FakeClassifierLlm();
        new ClassifyService(llm).classify(db, ingest.parseRunId());

        assertThat(llm.prompts).isNotEmpty();
        assertThat(llm.prompts.stream()
                .flatMap(prompt -> prompt.packet().cells().stream())
                .anyMatch(cell -> cell.numericValue() != null)).isTrue();
        for (LayerAPrompt prompt : llm.prompts) {
            for (PacketCell cell : prompt.packet().cells()) {
                assertThat(nullToEmpty(cell.numericValue())).doesNotContain("9998887");
                assertThat(nullToEmpty(cell.displayValue())).doesNotContain("9998887");
                assertThat(nullToEmpty(cell.textValue())).doesNotContain("9998887");
            }
        }

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db);
                var rs = workspace.connection().createStatement().executeQuery(
                        "SELECT numeric_value FROM cell WHERE numeric_value IS NOT NULL")) {
            boolean found = false;
            while (rs.next()) {
                if (rs.getString(1).contains("9998887")) {
                    found = true;
                }
            }
            assertThat(found).isTrue();
        }
    }

    @Test
    void coverageParentCheapPassRunsBeforeChildrenWithParentContext() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Parallel");
            for (int r = 0; r < 3; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue("L" + r);
                row.createCell(1).setCellValue(10.0 + r);
                row.createCell(4).setCellValue("R" + r);
                row.createCell(5).setCellValue(20.0 + r);
            }
            xlsx = writeWorkbook(workbook, "parallel.xlsx");
        }
        Path db = tempDir.resolve("parent-context.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.judgment = new LayerAJudgment(
                ScheduleFamily.CAPEX_DETAIL, Triage.MAIN, Relevance.SUPPORTING,
                List.of(), List.of(), null);
        ClassifySummary summary = new ClassifyService(llm).classify(db, ingest.parseRunId());

        assertThat(summary.coverageParentCount()).isEqualTo(1);
        assertThat(llm.prompts.size()).isGreaterThan(1);
        LayerAPrompt first = llm.prompts.get(0);
        assertThat(first.cheapPass()).isTrue();
        assertThat(first.packet().candidateKind()).isEqualTo("coverage_parent");
        assertThat(first.parentDisposition()).isNull();
        assertThat(first.packet().cells())
                .noneMatch(cell -> "number".equals(cell.valueType()));

        List<LayerAPrompt> children = llm.prompts.stream().skip(1).toList();
        assertThat(children).isNotEmpty();
        assertThat(children).allMatch(prompt -> !prompt.cheapPass());
        assertThat(children).allMatch(prompt -> prompt.parentDisposition() != null);
        assertThat(children).allMatch(prompt ->
                ScheduleFamily.CAPEX_DETAIL.equals(prompt.parentDisposition().scheduleFamily()));

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<PacketDisposition> rows = repo.selectPacketDispositionsForParseRun(
                    ingest.parseRunId());
            assertThat(rows.stream().filter(PacketDisposition::cheapPass)).hasSize(1);
            assertThat(rows.stream().filter(row -> !row.cheapPass())).isNotEmpty();
            assertThat(rows.stream().filter(row -> !row.cheapPass()))
                    .allMatch(row -> row.parentCandidateId() != null);
        }
    }

    @Test
    void reclassifyReplacesLayerAWithoutRewritingCandidateGeometry() throws Exception {
        Path xlsx = costScheduleWorkbook();
        Path db = tempDir.resolve("reclassify.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        List<CandidateRow> before;
        List<Long> memberIds;
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            before = List.copyOf(repo.selectCandidatesForParseRun(ingest.parseRunId()));
            memberIds = repo.selectCandidateMemberCellIds(before.get(0).candidateId());
        }

        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.judgment = new LayerAJudgment(
                ScheduleFamily.CAPEX_DETAIL, Triage.MAIN, Relevance.PRIMARY,
                List.of(), List.of(), null);
        new ClassifyService(llm).classify(db, ingest.parseRunId());

        llm.judgment = new LayerAJudgment(
                ScheduleFamily.ASSUMPTIONS, Triage.SCRATCH, Relevance.NOISE,
                List.of(), List.of(), null);
        new ClassifyService(llm).classify(db, ingest.parseRunId());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<CandidateRow> after = repo.selectCandidatesForParseRun(ingest.parseRunId());
            assertThat(after).isEqualTo(before);
            assertThat(repo.selectCandidateMemberCellIds(before.get(0).candidateId()))
                    .isEqualTo(memberIds);
            List<PacketDisposition> rows = repo.selectPacketDispositionsForParseRun(
                    ingest.parseRunId());
            assertThat(rows).hasSize(before.size());
            assertThat(rows).allMatch(row -> ScheduleFamily.ASSUMPTIONS.equals(row.scheduleFamily()));
            assertThat(rows).allMatch(row -> Triage.SCRATCH.equals(row.triage()));
            assertThat(rows).allMatch(row -> Relevance.NOISE.equals(row.relevance()));
        }
    }

    @Test
    void softTriageCoercesNonNoiseRelevanceBeforePersist() throws Exception {
        Path xlsx = costScheduleWorkbook();
        Path db = tempDir.resolve("soft-triage.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.judgment = new LayerAJudgment(
                ScheduleFamily.ASSUMPTIONS, Triage.ORPHAN, Relevance.PRIMARY,
                List.of(), List.of(), null);
        new ClassifyService(llm).classify(db, ingest.parseRunId());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<PacketDisposition> rows = repo.selectPacketDispositionsForParseRun(
                    ingest.parseRunId());
            assertThat(rows).isNotEmpty();
            assertThat(rows).allMatch(row -> Triage.ORPHAN.equals(row.triage()));
            assertThat(rows).allMatch(row -> Relevance.NOISE.equals(row.relevance()));
        }
    }

    @Test
    void layerBBindingsPersistWithAmountRolesAndAddOnlyRollup() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");
            header.createCell(4).setCellValue("Other");
            header.createCell(5).setCellValue("Amt");
            Row add = sheet.createRow(1);
            add.createCell(0).setCellValue("Civil Works");
            add.createCell(1).setCellValue(100.0);
            add.createCell(4).setCellValue("Less: AC");
            add.createCell(5).setCellValue(15.0);
            Row more = sheet.createRow(2);
            more.createCell(0).setCellValue("Steel");
            more.createCell(1).setCellValue(40.0);
            more.createCell(4).setCellValue("Glass");
            more.createCell(5).setCellValue(20.0);
            xlsx = writeWorkbook(workbook, "roles.xlsx");
        }
        Path db = tempDir.resolve("layer-b.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.layerBFactory = prompt -> {
            List<LayerBLineJudgment> lines = new ArrayList<>();
            for (PacketCell cell : LayerBAmountSupport.amountCells(prompt.packet())) {
                String label = LayerBAmountSupport.resolveRowLabel(prompt.packet(), cell);
                if (label != null && label.toLowerCase().contains("civil")) {
                    lines.add(new LayerBLineJudgment(
                            cell.coord(), "Civil Works",
                            "Project Cost > Civil Works > Structure",
                            AmountRole.ADD, List.of(), 0.95));
                } else if (label != null && label.toLowerCase().contains("ac")) {
                    lines.add(new LayerBLineJudgment(
                            cell.coord(), "Less: AC",
                            "Project Cost > Plant & Machinery > Air Conditioning",
                            AmountRole.DEDUCT, List.of("AC Tear-out"), 0.8));
                }
            }
            return lines;
        };

        ClassifySummary summary = new ClassifyService(llm).classify(db, ingest.parseRunId());
        assertThat(summary.bindingCount()).isGreaterThanOrEqualTo(2);
        assertThat(summary.layerBStats().accepted()).isGreaterThanOrEqualTo(2);
        assertThat(summary.layerBStats().proposed()).isGreaterThanOrEqualTo(2);
        assertThat(llm.layerBPrompts).isNotEmpty();
        assertThat(llm.layerBPrompts)
                .noneMatch(prompt -> "coverage_parent".equals(prompt.packet().candidateKind()));

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<NomenclatureBinding> bindings = repo.selectNomenclatureBindingsForParseRun(
                    ingest.parseRunId());
            assertThat(bindings).hasSizeGreaterThanOrEqualTo(2);
            assertThat(bindings).anyMatch(b -> AmountRole.ADD.equals(b.amountRole())
                    && "Project Cost > Civil Works > Structure".equals(b.path())
                    && "Civil Works".equals(b.verbatim())
                    && b.softLeaf());
            assertThat(bindings).anyMatch(b ->
                    b.path().endsWith("Air Conditioning")
                    && b.softLeaf());
            assertThat(repo.sumAddAmountsForPath(
                    ingest.parseRunId(), "Project Cost > Civil Works > Structure"))
                    .isGreaterThanOrEqualTo(100.0);
        }
    }

    @Test
    void coverageParentClassifyEmitsNoLayerBLineBindings() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Parallel");
            for (int r = 0; r < 3; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue("L" + r);
                row.createCell(1).setCellValue(10.0 + r);
                row.createCell(4).setCellValue("R" + r);
                row.createCell(5).setCellValue(20.0 + r);
            }
            xlsx = writeWorkbook(workbook, "no-parent-bindings.xlsx");
        }
        Path db = tempDir.resolve("no-parent-bindings.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.bindFirstAmountAsCivilAdd = true;
        new ClassifyService(llm).classify(db, ingest.parseRunId());

        assertThat(llm.layerBPrompts)
                .noneMatch(prompt -> "coverage_parent".equals(prompt.packet().candidateKind()));
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<PacketDisposition> parents = repo.selectPacketDispositionsForParseRun(
                    ingest.parseRunId()).stream()
                    .filter(PacketDisposition::cheapPass)
                    .toList();
            assertThat(parents).isNotEmpty();
            List<NomenclatureBinding> bindings = repo.selectNomenclatureBindingsForParseRun(
                    ingest.parseRunId());
            assertThat(bindings).noneMatch(b -> parents.stream()
                    .anyMatch(p -> p.candidateId() == b.candidateId() && p.cheapPass()));
        }
    }

    @Test
    void scratchPacketsCanStillSoftBindLayerB() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Parallel");
            for (int r = 0; r < 3; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue("L" + r);
                row.createCell(1).setCellValue(10.0 + r);
                row.createCell(4).setCellValue("R" + r);
                row.createCell(5).setCellValue(20.0 + r);
            }
            xlsx = writeWorkbook(workbook, "scratch-bind.xlsx");
        }
        Path db = tempDir.resolve("scratch-bind.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.judgment = new LayerAJudgment(
                ScheduleFamily.ASSUMPTIONS, Triage.SCRATCH, Relevance.NOISE,
                List.of(), List.of(), null);
        llm.bindFirstAmountAsCivilAdd = true;
        ClassifySummary summary = new ClassifyService(llm).classify(db, ingest.parseRunId());

        assertThat(summary.bindingCount()).isGreaterThanOrEqualTo(1);
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            assertThat(repo.selectPacketDispositionsForParseRun(ingest.parseRunId()))
                    .allMatch(row -> Triage.SCRATCH.equals(row.triage())
                            && Relevance.NOISE.equals(row.relevance()));
            assertThat(repo.selectNomenclatureBindingsForParseRun(ingest.parseRunId()))
                    .isNotEmpty();
        }
    }

    @Test
    void midLevelLineBindingDerivesASoftLeafFromTheRowLabel() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");
            header.createCell(4).setCellValue("Other");
            header.createCell(5).setCellValue("Amt");
            Row add = sheet.createRow(1);
            add.createCell(0).setCellValue("CCTV System");
            add.createCell(1).setCellValue(100.0);
            add.createCell(4).setCellValue("Glass");
            add.createCell(5).setCellValue(20.0);
            Row more = sheet.createRow(2);
            more.createCell(0).setCellValue("Steel");
            more.createCell(1).setCellValue(40.0);
            more.createCell(4).setCellValue("Paint");
            more.createCell(5).setCellValue(30.0);
            xlsx = writeWorkbook(workbook, "mid-level.xlsx");
        }
        Path db = tempDir.resolve("mid-level.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.layerBFactory = prompt -> LayerBAmountSupport.amountCells(prompt.packet()).stream()
                .filter(cell -> "CCTV System".equals(
                        LayerBAmountSupport.resolveRowLabel(prompt.packet(), cell)))
                .findFirst()
                .map(cell -> List.of(new LayerBLineJudgment(
                        cell.coord(), "CCTV System",
                        "Project Cost > Plant & Machinery",
                        AmountRole.ADD, List.of(), 0.9)))
                .orElse(List.of());

        ClassifySummary summary = new ClassifyService(llm).classify(db, ingest.parseRunId());

        assertThat(summary.layerBStats().rejectReasons()).doesNotContainKey("path_not_leaf");
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            assertThat(repo.selectNomenclatureBindingsForParseRun(ingest.parseRunId()))
                    .anyMatch(b -> "Project Cost > Plant & Machinery > CCTV System"
                                    .equals(b.path())
                            && AmountRole.ADD.equals(b.amountRole())
                            && b.softLeaf());
            assertThat(repo.sumAddAmountsForPath(
                    ingest.parseRunId(), "Project Cost > Plant & Machinery > CCTV System"))
                    .isEqualTo(100.0);
        }
    }

    @Test
    void midLevelLineBindingIsRejectedWhenTheRowLabelCannotNameALeaf() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            for (int r = 0; r < 3; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue("L" + r);
                row.createCell(1).setCellValue(10.0 + r);
                row.createCell(4).setCellValue("R" + r);
                row.createCell(5).setCellValue(20.0 + r);
            }
            xlsx = writeWorkbook(workbook, "mid-level-coord.xlsx");
        }
        Path db = tempDir.resolve("mid-level-coord.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.layerBFactory = prompt -> LayerBAmountSupport.amountCells(prompt.packet()).stream()
                .findFirst()
                .map(cell -> List.of(new LayerBLineJudgment(
                        cell.coord(), cell.coord(),
                        "Project Cost > Plant & Machinery",
                        AmountRole.ADD, List.of(), 0.9)))
                .orElse(List.of());

        ClassifySummary summary = new ClassifyService(llm).classify(db, ingest.parseRunId());

        assertThat(summary.bindingCount()).isZero();
        assertThat(summary.layerBStats().rejectReasons()).containsKey("path_not_leaf");
    }

    @Test
    void softGenericPathPrefersUnambiguousHardCatalogLeafFromRowEvidence() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("PnL");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");
            header.createCell(4).setCellValue("Other");
            header.createCell(5).setCellValue("Amt");
            Row add = sheet.createRow(1);
            add.createCell(0).setCellValue("F & B Sales");
            add.createCell(1).setCellValue(150.0);
            add.createCell(4).setCellValue("Glass");
            add.createCell(5).setCellValue(20.0);
            Row more = sheet.createRow(2);
            more.createCell(0).setCellValue("Steel");
            more.createCell(1).setCellValue(40.0);
            more.createCell(4).setCellValue("Paint");
            more.createCell(5).setCellValue(30.0);
            xlsx = writeWorkbook(workbook, "prefer-catalog.xlsx");
        }
        Path db = tempDir.resolve("prefer-catalog.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            new NomenclatureCatalog(new WorkspaceRepository(workspace.connection()))
                    .confirmIndustry(1L, "hotel");
        }

        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.layerBFactory = prompt -> LayerBAmountSupport.amountCells(prompt.packet()).stream()
                .filter(cell -> "F & B Sales".equals(
                        LayerBAmountSupport.resolveRowLabel(prompt.packet(), cell)))
                .findFirst()
                .map(cell -> List.of(new LayerBLineJudgment(
                        cell.coord(), "Projected Profitability",
                        "Profit & Loss > Projected Profitability",
                        AmountRole.ADD, List.of(), 0.9)))
                .orElse(List.of());

        ClassifySummary summary = new ClassifyService(llm).classify(db, ingest.parseRunId());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            assertThat(repo.selectNomenclatureBindingsForParseRun(ingest.parseRunId()))
                    .anyMatch(b -> "Profit & Loss > F & B Sales".equals(b.path())
                            && AmountRole.ADD.equals(b.amountRole())
                            && !b.softLeaf());
            assertThat(repo.selectNomenclatureBindingsForParseRun(ingest.parseRunId()))
                    .noneMatch(b -> b.path().contains("Projected Profitability"));
        }
    }

    @Test
    void midLevelSoftDerivationPrefersHardCatalogLeafWhenRowEvidenceMatches() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");
            header.createCell(4).setCellValue("Other");
            header.createCell(5).setCellValue("Amt");
            Row add = sheet.createRow(1);
            add.createCell(0).setCellValue("Air Conditioning");
            add.createCell(1).setCellValue(100.0);
            add.createCell(4).setCellValue("Glass");
            add.createCell(5).setCellValue(20.0);
            Row more = sheet.createRow(2);
            more.createCell(0).setCellValue("Steel");
            more.createCell(1).setCellValue(40.0);
            more.createCell(4).setCellValue("Paint");
            more.createCell(5).setCellValue(30.0);
            xlsx = writeWorkbook(workbook, "prefer-mid.xlsx");
        }
        Path db = tempDir.resolve("prefer-mid.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            new NomenclatureCatalog(new WorkspaceRepository(workspace.connection()))
                    .confirmIndustry(1L, "hotel");
        }

        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.layerBFactory = prompt -> LayerBAmountSupport.amountCells(prompt.packet()).stream()
                .filter(cell -> "Air Conditioning".equals(
                        LayerBAmountSupport.resolveRowLabel(prompt.packet(), cell)))
                .findFirst()
                .map(cell -> List.of(new LayerBLineJudgment(
                        cell.coord(), "Projected Profitability",
                        "Project Cost > Plant & Machinery",
                        AmountRole.ADD, List.of(), 0.9)))
                .orElse(List.of());

        ClassifySummary summary = new ClassifyService(llm).classify(db, ingest.parseRunId());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            assertThat(repo.selectNomenclatureBindingsForParseRun(ingest.parseRunId()))
                    .anyMatch(b -> "Project Cost > Plant & Machinery > Air Conditioning"
                                    .equals(b.path())
                            && !b.softLeaf());
            assertThat(repo.selectNomenclatureBindingsForParseRun(ingest.parseRunId()))
                    .noneMatch(b -> b.path().endsWith("Projected Profitability"));
        }
    }

    @Test
    void softGenericWithoutHardCatalogMatchIsKeptAndMeasured() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");
            header.createCell(4).setCellValue("Other");
            header.createCell(5).setCellValue("Amt");
            Row add = sheet.createRow(1);
            add.createCell(0).setCellValue("CCTV System");
            add.createCell(1).setCellValue(100.0);
            add.createCell(4).setCellValue("Glass");
            add.createCell(5).setCellValue(20.0);
            Row more = sheet.createRow(2);
            more.createCell(0).setCellValue("Steel");
            more.createCell(1).setCellValue(40.0);
            more.createCell(4).setCellValue("Paint");
            more.createCell(5).setCellValue(30.0);
            xlsx = writeWorkbook(workbook, "soft-kept.xlsx");
        }
        Path db = tempDir.resolve("soft-kept.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.layerBFactory = prompt -> LayerBAmountSupport.amountCells(prompt.packet()).stream()
                .filter(cell -> "CCTV System".equals(
                        LayerBAmountSupport.resolveRowLabel(prompt.packet(), cell)))
                .findFirst()
                .map(cell -> List.of(new LayerBLineJudgment(
                        cell.coord(), "CCTV System",
                        "Project Cost > Plant & Machinery",
                        AmountRole.ADD, List.of(), 0.9)))
                .orElse(List.of());

        ClassifySummary summary = new ClassifyService(llm).classify(db, ingest.parseRunId());

        assertThat(summary.layerBStats().softGenericKept()).isEqualTo(1);
        assertThat(summary.layerBStats().catalogPreferred()).isZero();
        assertThat(summary.layerBStats().leafAmbiguous()).isZero();
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            assertThat(repo.selectNomenclatureBindingsForParseRun(ingest.parseRunId()))
                    .anyMatch(b -> "Project Cost > Plant & Machinery > CCTV System"
                                    .equals(b.path())
                            && b.softLeaf());
        }
    }

    @Test
    void conflictingHardCatalogEvidenceKeepsSoftLeafAndMeasuresAmbiguity() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");
            header.createCell(4).setCellValue("Other");
            header.createCell(5).setCellValue("Amt");
            Row add = sheet.createRow(1);
            add.createCell(0).setCellValue("Elevator");
            add.createCell(1).setCellValue(100.0);
            add.createCell(4).setCellValue("Glass");
            add.createCell(5).setCellValue(20.0);
            Row more = sheet.createRow(2);
            more.createCell(0).setCellValue("Steel");
            more.createCell(1).setCellValue(40.0);
            more.createCell(4).setCellValue("Paint");
            more.createCell(5).setCellValue(30.0);
            xlsx = writeWorkbook(workbook, "leaf-ambiguous.xlsx");
        }
        Path db = tempDir.resolve("leaf-ambiguous.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            new NomenclatureCatalog(new WorkspaceRepository(workspace.connection()))
                    .confirmIndustry(1L, "hotel");
        }

        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.layerBFactory = prompt -> LayerBAmountSupport.amountCells(prompt.packet()).stream()
                .filter(cell -> "Elevator".equals(
                        LayerBAmountSupport.resolveRowLabel(prompt.packet(), cell)))
                .findFirst()
                .map(cell -> List.of(new LayerBLineJudgment(
                        cell.coord(), "Air Conditioning",
                        "Project Cost > Plant & Machinery > Projected Profitability",
                        AmountRole.ADD, List.of(), 0.9)))
                .orElse(List.of());

        ClassifySummary summary = new ClassifyService(llm).classify(db, ingest.parseRunId());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            assertThat(repo.selectNomenclatureBindingsForParseRun(ingest.parseRunId()))
                    .anyMatch(b -> b.path() != null
                            && b.path().toLowerCase().contains("elevator")
                            && !b.softLeaf());
        }
    }

    @Test
    void layerBCallFailureCostsOnlyThatPacketsBindings() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            for (String sheetName : List.of("Left", "Right")) {
                Sheet sheet = workbook.createSheet(sheetName);
                for (int r = 0; r < 3; r++) {
                    Row row = sheet.createRow(r);
                    row.createCell(0).setCellValue(sheetName + "L" + r);
                    row.createCell(1).setCellValue(10.0 + r);
                }
            }
            xlsx = writeWorkbook(workbook, "layer-b-failure.xlsx");
        }
        Path db = tempDir.resolve("layer-b-failure.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        AtomicInteger layerBCalls = new AtomicInteger();
        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.layerBFactory = prompt -> {
            if (layerBCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("OpenRouter Layer B invalid after retry");
            }
            return prompt.packet().cells().stream()
                    .filter(cell -> "number".equals(cell.valueType()))
                    .findFirst()
                    .map(cell -> List.of(new LayerBLineJudgment(
                            cell.coord(), "Civil Works",
                            "Project Cost > Civil Works > Structure",
                            AmountRole.ADD, List.of(), 0.9)))
                    .orElse(List.of());
        };

        ClassifySummary summary = new ClassifyService(llm).classify(db, ingest.parseRunId());

        assertThat(summary.layerBStats().failedCalls()).isEqualTo(1);
        assertThat(summary.layerBStats().failedCallSamples().get(0))
                .contains("Layer B invalid after retry");
        assertThat(summary.dispositionCount()).isEqualTo(llm.prompts.size());
        assertThat(summary.bindingCount())
                .as("the call that failed costs its own bindings, not the run's")
                .isGreaterThanOrEqualTo(1);
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            assertThat(repo.selectNomenclatureBindingsForParseRun(ingest.parseRunId()))
                    .anyMatch(binding -> BindingSource.LLM_LABEL.equals(binding.source()));
        }
    }

    @Test
    void skipsLayerBWhenPacketHasNoAmountCells() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Labels");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("Only labels");
            row.createCell(1).setCellValue("No amounts");
            xlsx = writeWorkbook(workbook, "no-amounts.xlsx");
        }
        Path db = tempDir.resolve("no-amounts.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.bindFirstAmountAsCivilAdd = true;
        ClassifySummary summary = new ClassifyService(llm).classify(db, ingest.parseRunId());

        assertThat(summary.bindingCount()).isZero();
        assertThat(llm.layerBPrompts).isEmpty();
    }

    @Test
    void aFormulaCanTakeAddAndTheAggregationHeadIsWhatStopsDoubleCounting()
            throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Parallel");
            for (int r = 0; r < 3; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue("L" + r);
                row.createCell(1).setCellValue(10.0 + r);
                row.createCell(4).setCellValue("R" + r);
                row.createCell(5).setCellValue(20.0 + r);
            }
            Row total = sheet.createRow(3);
            total.createCell(0).setCellValue("Total");
            total.createCell(1).setCellFormula("B1+B2+B3");
            total.createCell(4).setCellValue("TotalR");
            total.createCell(5).setCellFormula("F1+F2+F3");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            xlsx = writeWorkbook(workbook, "formula-roles.xlsx");
        }
        Path db = tempDir.resolve("formula-roles.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.layerBFactory = prompt -> {
            PacketCell formula = prompt.packet().cells().stream()
                    .filter(cell -> cell.formulaText() != null && !cell.formulaText().isBlank())
                    .findFirst()
                    .orElse(null);
            if (formula == null) {
                return List.of();
            }
            return List.of(
                    new LayerBLineJudgment(
                            formula.coord(), "Total",
                            "Project Cost > Civil Works > Structure",
                            AmountRole.ADD, List.of(), 0.5),
                    new LayerBLineJudgment(
                            formula.coord(), "Total",
                            "Project Cost > Civil Works > Structure",
                            AmountRole.HELPER, List.of(), 0.9));
        };

        ClassifySummary summary = new ClassifyService(llm).classify(db, ingest.parseRunId());
        assertThat(llm.layerBPrompts).isNotEmpty();
        assertThat(summary.layerBStats().proposed()).isGreaterThanOrEqualTo(1);
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            var bindings = repo.selectNomenclatureBindingsForParseRun(ingest.parseRunId());
            assertThat(bindings)
                    .as("being a formula is no longer a reason to refuse a role")
                    .isNotEmpty();
            var totals = repo.selectAggregationsForParseRun(ingest.parseRunId());
            assertThat(totals)
                    .as("B1+B2+B3 and F1+F2+F3 are the rollups, and the graph knows it")
                    .hasSize(2);
            for (var binding : bindings) {
                boolean isHead = totals.stream()
                        .anyMatch(total -> total.headCellId() == binding.cellId());
                if (AmountRole.TOTAL.equals(binding.amountRole())) {
                    assertThat(isHead)
                            .as("only an aggregation head is a total")
                            .isTrue();
                }
            }
        }
    }

    @Test
    void antiDoubleCountPeersPersistOnBothSidesWhenPathsMatch() throws Exception {
        Path xlsx = rolesPeerWorkbook("f31-peers.xlsx");
        Path db = tempDir.resolve("f31-peers.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        String acPath = "Project Cost > Plant & Machinery > Air Conditioning";
        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.layerBFactory = prompt -> {
            List<LayerBLineJudgment> lines = new ArrayList<>();
            for (PacketCell cell : amountLiterals(prompt)) {
                String label = LayerBAmountSupport.resolveRowLabel(prompt.packet(), cell);
                if (label != null && label.toLowerCase().contains("civil")) {
                    lines.add(new LayerBLineJudgment(
                            cell.coord(), "Air Conditioning", acPath, AmountRole.ADD,
                            List.of(), null, List.of()));
                } else if (label != null && label.toLowerCase().contains("ac")) {
                    lines.add(new LayerBLineJudgment(
                            cell.coord(), "Less: AC", acPath, AmountRole.DEDUCT,
                            List.of(), null,
                            List.of(new LinePeerRef(
                                    "Costs!B2", PeerReason.ANTI_DOUBLE_COUNT))));
                }
            }
            return lines;
        };

        new ClassifyService(llm).classify(db, ingest.parseRunId());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<NomenclatureBinding> bindings = repo.selectNomenclatureBindingsForParseRun(
                    ingest.parseRunId());
            assertThat(bindings).hasSizeGreaterThanOrEqualTo(2);
            NomenclatureBinding deduct = bindings.stream()
                    .filter(b -> b.verbatim() != null && b.verbatim().toLowerCase().contains("ac"))
                    .findFirst()
                    .orElseThrow();
            NomenclatureBinding add = bindings.stream()
                    .filter(b -> b.verbatim() != null && b.verbatim().toLowerCase().contains("civil"))
                    .findFirst()
                    .orElseThrow();
            assertThat(repo.selectBindingPeersForCell(ingest.parseRunId(), deduct.cellId()))
                    .anyMatch(p -> p.peerCellId() == add.cellId() && p.pathResolved());
            assertThat(repo.selectBindingPeersForCell(ingest.parseRunId(), add.cellId()))
                    .anyMatch(p -> p.peerCellId() == deduct.cellId() && p.pathResolved());
            assertThat(repo.sumAddAmountsForPath(ingest.parseRunId(), acPath)).isGreaterThan(0.0);
        }
    }

    @Test
    void pathMismatchedPeersAreStoredButNotResolved() throws Exception {
        Path xlsx = rolesPeerWorkbook("mismatch-peers.xlsx");
        Path db = tempDir.resolve("mismatch-peers.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.layerBFactory = prompt -> {
            List<LayerBLineJudgment> lines = new ArrayList<>();
            for (PacketCell cell : amountLiterals(prompt)) {
                String label = LayerBAmountSupport.resolveRowLabel(prompt.packet(), cell);
                if (label != null && label.toLowerCase().contains("civil")) {
                    lines.add(new LayerBLineJudgment(
                            cell.coord(), "Air Conditioning",
                            "Project Cost > Plant & Machinery > Air Conditioning",
                            AmountRole.ADD, List.of(), null, List.of()));
                } else if (label != null && label.toLowerCase().contains("ac")) {
                    lines.add(new LayerBLineJudgment(
                            cell.coord(), "Less: AC",
                            "Project Cost > Civil Works > Structure",
                            AmountRole.DEDUCT, List.of(), null,
                            List.of(new LinePeerRef("Costs!B2", PeerReason.ANTI_DOUBLE_COUNT))));
                }
            }
            return lines;
        };

        new ClassifyService(llm).classify(db, ingest.parseRunId());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            NomenclatureBinding deduct = repo.selectNomenclatureBindingsForParseRun(ingest.parseRunId())
                    .stream()
                    .filter(b -> b.verbatim() != null && b.verbatim().toLowerCase().contains("ac"))
                    .findFirst()
                    .orElseThrow();
            assertThat(repo.selectBindingPeersForCell(ingest.parseRunId(), deduct.cellId()))
                    .anyMatch(p -> !p.pathResolved());
        }
    }

    @Test
    void projectFactsPersistOutsideProjectCost() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("AT GLANCE");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("Legal Name");
            row.createCell(1).setCellValue("Demo Hotel LLP");
            xlsx = writeWorkbook(workbook, "facts.xlsx");
        }
        Path db = tempDir.resolve("facts.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.judgment = new LayerAJudgment(
                ScheduleFamily.PROJECT_SUMMARY,
                Triage.MAIN,
                Relevance.PRIMARY,
                List.of(),
                List.of(),
                null,
                List.of(
                        new ProjectFactJudgment("B1", "Demo Hotel LLP", "Project Identity > Legal Name"),
                        new ProjectFactJudgment("B1", "Sneaky", "Project Cost > Civil Works")));

        new ClassifyService(llm).classify(db, ingest.parseRunId());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<ProjectFactBinding> facts = repo.selectProjectFactBindingsForParseRun(ingest.parseRunId());
            assertThat(facts).hasSize(1);
            assertThat(facts.get(0).factPath()).isEqualTo("Project Identity > Legal Name");
            assertThat(facts.get(0).factPath()).doesNotStartWith("Project Cost");
        }
    }

    @Test
    void inventingMidLevelPathIsRejected() throws Exception {
        Path xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Parallel");
            for (int r = 0; r < 3; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue("L" + r);
                row.createCell(1).setCellValue(10.0 + r);
                row.createCell(4).setCellValue("R" + r);
                row.createCell(5).setCellValue(20.0 + r);
            }
            xlsx = writeWorkbook(workbook, "mid-level.xlsx");
        }
        Path db = tempDir.resolve("mid-level.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.layerBFactory = prompt -> prompt.packet().cells().stream()
                .filter(cell -> "number".equals(cell.valueType()))
                .findFirst()
                .map(cell -> List.of(new LayerBLineJudgment(
                        cell.coord(),
                        "Invented",
                        "Brand New Mid Level > Leaf",
                        AmountRole.ADD,
                        List.of(),
                        null)))
                .orElse(List.of());

        ClassifySummary summary = new ClassifyService(llm).classify(db, ingest.parseRunId());
        assertThat(summary.bindingCount()).isZero();
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            assertThat(repo.selectNomenclatureBindingsForParseRun(ingest.parseRunId())).isEmpty();
        }
    }

    @Test
    void persistentLayerATruncationDegradesToOrphanNoiseInsteadOfAborting() throws Exception {
        Path xlsx = costScheduleWorkbook();
        Path db = tempDir.resolve("truncation.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());

        FakeClassifierLlm llm = new FakeClassifierLlm();
        llm.classifyLayerAFailure = prompt -> new OpenRouterClassifierLlm.TruncatedCompletionException(
                "OpenRouter Layer A truncated/invalid after retry: stub");

        ClassifySummary summary = new ClassifyService(llm).classify(db, ingest.parseRunId());
        assertThat(summary.dispositionCount()).isGreaterThanOrEqualTo(1);

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<PacketDisposition> rows = repo.selectPacketDispositionsForParseRun(
                    ingest.parseRunId());
            assertThat(rows).isNotEmpty();
            assertThat(rows).allMatch(row -> Triage.ORPHAN.equals(row.triage()));
            assertThat(rows).allMatch(row -> Relevance.NOISE.equals(row.relevance()));
        }
    }

    /** Scripted LLM for tests: records prompts and returns fixed Layer A / Layer B judgments. */
    static class FakeClassifierLlm implements ClassifierLlm {
        final List<LayerAPrompt> prompts = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<LayerBPrompt> layerBPrompts = new java.util.concurrent.CopyOnWriteArrayList<>();
        LayerAJudgment judgment = new LayerAJudgment(
                ScheduleFamily.CAPEX_DETAIL, Triage.MAIN, Relevance.PRIMARY,
                List.of(), List.of(), null);
        List<LayerBLineJudgment> layerBLines = List.of();
        java.util.function.Function<LayerBPrompt, List<LayerBLineJudgment>> layerBFactory = null;
        java.util.function.Function<LayerAPrompt, RuntimeException> classifyLayerAFailure = null;
        boolean bindFirstAmountAsCivilAdd;

        @Override
        public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
            prompts.add(prompt);
            if (classifyLayerAFailure != null) {
                throw classifyLayerAFailure.apply(prompt);
            }
            return judgment;
        }

        @Override
        public List<LayerBLineJudgment> classifyLayerB(LayerBPrompt prompt) {
            layerBPrompts.add(prompt);
            if (layerBFactory != null) {
                return layerBFactory.apply(prompt);
            }
            if (!layerBLines.isEmpty()) {
                return layerBLines;
            }
            if (!bindFirstAmountAsCivilAdd) {
                return List.of();
            }
            return prompt.packet().cells().stream()
                    .filter(cell -> "number".equals(cell.valueType())
                            && (cell.formulaText() == null || cell.formulaText().isBlank()))
                    .findFirst()
                    .map(cell -> List.of(new LayerBLineJudgment(
                            cell.coord(),
                            "Civil Works",
                            "Project Cost > Civil Works > Structure",
                            AmountRole.ADD,
                            List.of(),
                            0.9)))
                    .orElse(List.of());
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private Path rolesPeerWorkbook(String name) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");
            header.createCell(4).setCellValue("Other");
            header.createCell(5).setCellValue("Amt");
            Row add = sheet.createRow(1);
            add.createCell(0).setCellValue("Civil Works");
            add.createCell(1).setCellValue(100.0);
            add.createCell(4).setCellValue("Less: AC");
            add.createCell(5).setCellValue(500.0);
            Row more = sheet.createRow(2);
            more.createCell(0).setCellValue("Steel");
            more.createCell(1).setCellValue(40.0);
            more.createCell(4).setCellValue("Glass");
            more.createCell(5).setCellValue(20.0);
            return writeWorkbook(workbook, name);
        }
    }

    private static List<PacketCell> amountLiterals(LayerBPrompt prompt) {
        return prompt.packet().cells().stream()
                .filter(cell -> "number".equals(cell.valueType())
                        && (cell.formulaText() == null || cell.formulaText().isBlank()))
                .toList();
    }
}
