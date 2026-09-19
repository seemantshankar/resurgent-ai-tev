package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.ingest.IngestService;
import com.resurgent.tev.parser.ingest.IngestSummary;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seam: the cell graph survives a real ingest → discover → classify round trip, so
 * the evidence behind a binding can be read back after the run.
 */
class CellGraphPersistenceTest {

    @TempDir
    Path tempDir;

    /**
     * A cost block whose total is a SUM and whose net line subtracts that total, plus
     * a driver pair that must never become a summand.
     */
    private Path costBlockWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Costs");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount (Rs)");
            Row civil = sheet.createRow(1);
            civil.createCell(0).setCellValue("Civil Works");
            civil.createCell(1).setCellValue(100.0);
            Row plant = sheet.createRow(2);
            plant.createCell(0).setCellValue("Plant & Machinery");
            plant.createCell(1).setCellValue(200.0);
            Row total = sheet.createRow(3);
            total.createCell(0).setCellValue("Total Project Cost");
            total.createCell(1).setCellFormula("SUM(B2:B3)");
            Row grant = sheet.createRow(4);
            grant.createCell(0).setCellValue("Less: Subsidy");
            grant.createCell(1).setCellValue(50.0);
            Row net = sheet.createRow(5);
            net.createCell(0).setCellValue("Net Project Cost");
            net.createCell(1).setCellFormula("B4-B5");
            Row rooms = sheet.createRow(6);
            rooms.createCell(0).setCellValue("No. of Rooms");
            rooms.createCell(1).setCellValue(40.0);
            Row tariff = sheet.createRow(7);
            tariff.createCell(0).setCellValue("Tariff per Room");
            tariff.createCell(1).setCellValue(5000.0);
            Row revenue = sheet.createRow(8);
            revenue.createCell(0).setCellValue("Room Revenue");
            revenue.createCell(1).setCellFormula("B7*B8");

            Path file = tempDir.resolve("cost-block.xlsx");
            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                workbook.write(out);
            }
            return file;
        }
    }

    @Test
    void classifyPersistsAggregationsWithMembershipSignAndHeadLabel() throws Exception {
        Path xlsx = costBlockWorkbook();
        Path db = tempDir.resolve("graph.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());
        new ClassifyService(new ClassifyServiceTest.FakeClassifierLlm())
                .classify(db, ingest.parseRunId());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            List<AggregationRow> aggregations =
                    repo.selectAggregationsForParseRun(ingest.parseRunId());

            assertThat(aggregations)
                    .as("the SUM total and the net line are both heads; the product is not")
                    .extracting(AggregationRow::headLabel)
                    .containsExactlyInAnyOrder("Total Project Cost", "Net Project Cost");

            AggregationRow total = aggregations.stream()
                    .filter(row -> "Total Project Cost".equals(row.headLabel()))
                    .findFirst()
                    .orElseThrow();
            assertThat(repo.selectAggregationMembers(total.aggregationId()))
                    .extracting(AggregationMemberRow::memberLabel,
                            AggregationMemberRow::amountRole)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple("Civil Works", AmountRole.ADD),
                            org.assertj.core.api.Assertions.tuple(
                                    "Plant & Machinery", AmountRole.ADD));

            AggregationRow net = aggregations.stream()
                    .filter(row -> "Net Project Cost".equals(row.headLabel()))
                    .findFirst()
                    .orElseThrow();
            assertThat(repo.selectAggregationMembers(net.aggregationId()))
                    .as("the sign comes from the operator, not from the 'Less:' prefix")
                    .extracting(AggregationMemberRow::memberLabel, AggregationMemberRow::sign)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple("Total Project Cost", "plus"),
                            org.assertj.core.api.Assertions.tuple("Less: Subsidy", "minus"));
        }
    }

    @Test
    void reclassifyReplacesTheGraphRatherThanAppendingToIt() throws Exception {
        Path xlsx = costBlockWorkbook();
        Path db = tempDir.resolve("graph-replace.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());
        new ClassifyService(new ClassifyServiceTest.FakeClassifierLlm())
                .classify(db, ingest.parseRunId());
        new ClassifyService(new ClassifyServiceTest.FakeClassifierLlm())
                .classify(db, ingest.parseRunId());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            assertThat(repo.selectAggregationsForParseRun(ingest.parseRunId())).hasSize(2);
        }
    }

    @Test
    void rediscoveryInvalidatesTheGraph() throws Exception {
        Path xlsx = costBlockWorkbook();
        Path db = tempDir.resolve("graph-invalidate.db");
        IngestSummary ingest = new IngestService().ingest(xlsx, 1L, db);
        new DiscoverService().discover(db, ingest.parseRunId());
        new ClassifyService(new ClassifyServiceTest.FakeClassifierLlm())
                .classify(db, ingest.parseRunId());
        new DiscoverService().discover(db, ingest.parseRunId());

        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            assertThat(repo.selectAggregationsForParseRun(ingest.parseRunId())).isEmpty();
        }
    }
}
