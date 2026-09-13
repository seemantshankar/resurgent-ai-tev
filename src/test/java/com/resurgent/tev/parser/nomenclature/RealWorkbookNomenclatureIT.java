package com.resurgent.tev.parser.nomenclature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.ingest.IngestService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the nomenclature catalog against ingested Om Arham cell text under
 * {@code Project Docs/}. Skips when that file is absent. Asserts labels only —
 * never financial amounts or gold-filed coordinates.
 */
class RealWorkbookNomenclatureIT {

    private static final Path WORKBOOK =
            Path.of("Project Docs", "OM Arham Ventures.xlsx");

    private static final List<Grounding> GROUNDINGS = List.of(
            new Grounding("PROJECT COST", "Project Cost"),
            new Grounding("MEANS OF FINANCE", "Means of Finance"),
            new Grounding("Partners' Capital",
                    "Means of Finance > Promoter / Partners' Capital"),
            new Grounding("Unsecured Loans", "Means of Finance > Unsecured Loans"),
            new Grounding("Term Loan From Financial Institutions",
                    "Means of Finance > Term Loan"),
            new Grounding("Land & Site Development",
                    "Project Cost > Land & Site Development"),
            new Grounding("CIVIL WORKS (INCLUDING SANITARY INSTALLATIONS)",
                    "Project Cost > Civil Works"),
            new Grounding("DETAILS OF PLANT & MACHINERIES",
                    "Project Cost > Plant & Machinery"),
            new Grounding("CONTINGENCY", "Project Cost > Contingency"),
            new Grounding("PRELIMINARY & PREOPERATIVE EXPENSES",
                    "Project Cost > Preliminary & Pre-operative Expenses"),
            new Grounding("MARGIN MONEY / INVSTT. FOR WORKING CAPITAL",
                    "Project Cost > Margin Money / Working Capital Margin"),
            new Grounding("DETAILS OF MISCELLANEOUS FIXED ASSETS / FURNITURES & FIXTURES",
                    "Project Cost > Misc. Fixed Assets / Furniture & Fixtures"),
            new Grounding("ELECTRIFICATIONS & ELECTRICAL INSTALLATIONS",
                    "Project Cost > Electrical Installations"),
            new Grounding("Elevator (Supplier - Kone Elevator India Pvt. Ltd.)",
                    "Project Cost > Plant & Machinery > Elevator / Lift"),
            new Grounding("Lift (Taken as per Quotation Above)",
                    "Project Cost > Plant & Machinery > Elevator / Lift"),
            new Grounding("Kitchen Equipments (Supplier- Vsg Equipment)",
                    "Project Cost > Plant & Machinery > Kitchen Equipments"),
            new Grounding("Wastewater Engineering Services (Supplier - Aquasolution)",
                    "Project Cost > Plant & Machinery > Wastewater / ETP"),
            new Grounding("Air Conditioning (Supplier-Ashi Associates)",
                    "Project Cost > Plant & Machinery > Air Conditioning"),
            new Grounding("Less : AC as per Quotation included Below",
                    "Project Cost > Plant & Machinery > Air Conditioning"));

    @TempDir
    static Path tempDir;

    private static Path db;
    private static Set<String> ingestedTexts;
    private static OntologySlice slice;

    @BeforeAll
    static void ingestAndSliceOnce() throws Exception {
        assumeTrue(Files.exists(WORKBOOK),
                "Working workbook not found at " + WORKBOOK.toAbsolutePath()
                        + " -- place the client FM at Project Docs/OM Arham Ventures.xlsx"
                        + " to run this integration test; skipping.");
        db = tempDir.resolve("real-workbook-nomenclature.db");
        new IngestService().ingest(WORKBOOK, 1L, db);
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            WorkspaceRepository repo = new WorkspaceRepository(workspace.connection());
            NomenclatureCatalog catalog = new NomenclatureCatalog(repo);
            catalog.confirmIndustry(1L, "hotel");
            slice = catalog.sliceForMandate(1L);
            ingestedTexts = new HashSet<>();
            try (ResultSet rs = workspace.connection().createStatement().executeQuery(
                    "SELECT text_value FROM cell WHERE text_value IS NOT NULL")) {
                while (rs.next()) {
                    ingestedTexts.add(rs.getString(1));
                }
            }
        }
    }

    @Test
    void omArhamBankHeadsExistInTheWorkbookAndResolveOnTheHotelSlice() {
        for (Grounding grounding : GROUNDINGS) {
            assertThat(ingestedTexts)
                    .as("Om Arham cell graph must contain verbatim '%s'", grounding.verbatim())
                    .contains(grounding.verbatim());
            assertThat(slice.resolve(grounding.verbatim()))
                    .as("catalog must hang Om Arham verbatim '%s' on %s",
                            grounding.verbatim(), grounding.path())
                    .contains(grounding.path());
        }
    }

    @Test
    void candidateGeometryTablesStayUntouchedByCatalogSeed() throws Exception {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db);
                ResultSet rs = workspace.connection().createStatement().executeQuery(
                        "SELECT COUNT(*) FROM candidate")) {
            rs.next();
            assertThat(rs.getLong(1)).isZero();
        }
    }

    private record Grounding(String verbatim, String path) {}
}
