package com.resurgent.tev.doc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.bind.PackPathBinder;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.extract.ExtractService;
import com.resurgent.tev.doc.extract.FixtureExtractClient;
import com.resurgent.tev.doc.ingest.BlobStore;
import com.resurgent.tev.doc.ingest.DocumentIngestService;
import com.resurgent.tev.doc.layout.DocumentLayoutService;
import com.resurgent.tev.doc.llm.FakeDocLlmClient;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seed packs for PVsyst, land deeds, and promoter net-worth schedules extract one
 * fact per cell and bind each line under its table parent. Audited headline
 * figures and the DPR stay on their existing field keys and still bind.
 */
class ScheduleSeedExtractBindTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MANDATE = 1L;

    @TempDir
    Path tempDir;

    private Path dbPath;
    private BlobStore blobs;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase ignored = WorkspaceDatabase.open(dbPath)) {
            // open applies migrations
        }
        blobs = BlobStore.besideDatabase(dbPath);
    }

    @Test
    void seedPacksCompileTheScheduleTables() throws Exception {
        DocTypePack pvsyst = pack("technical.pvsyst_report");
        JsonNode month = columns(pvsyst, "monthly_generation");
        assertThat(month.path("month").path("line_key").asBoolean()).isTrue();
        assertThat(month.path("month").path("period").asBoolean()).isTrue();
        assertThat(columns(pvsyst, "losses").path("loss").path("line_key").asBoolean()).isTrue();

        JsonNode parcel = columns(pack("legal.land_deed"), "parcels");
        assertThat(parcel.path("survey_no").path("line_key").asBoolean()).isTrue();
        assertThat(parcel.path("owner").path("entity").asBoolean()).isTrue();
        assertThat(parcel.path("village").path("site").asBoolean()).isTrue();

        JsonNode asset = columns(pack("identity.promoter_net_worth"), "assets");
        assertThat(asset.path("asset").path("line_key").asBoolean()).isTrue();
        assertThat(asset.path("holder").path("entity").asBoolean()).isTrue();
        assertThat(columns(pack("identity.promoter_net_worth"), "liabilities")
                .path("liability").path("line_key").asBoolean()).isTrue();

        assertThat(columns(pack("report.dpr"), "plant_and_machinery")
                .path("description").path("line_key").asBoolean()).isTrue();

        JsonNode headline = columns(pack("financials.audited_summary"), "headline_figures");
        assertThat(headline.path("financial_year").path("period").asBoolean()).isTrue();
        assertThat(headline.path("revenue").path("line_key").asBoolean(false)).isFalse();
    }

    @Test
    void schedulePacksExtractAndBindEachLine() throws Exception {
        long pv = ingest("pvsyst.pdf", "PVsyst - Site A", "technical_report",
                "PVsyst Grid-Connected System", "1450 kWh/kWp",
                "Jan 4.1", "4.1 kWh/m2", "Jan 120 MWh",
                "Feb 4.4", "4.4 kWh/m2", "Feb 130 MWh",
                "soiling 120 kWh", "Total 400 kWh");
        bind(extract(pv, "technical.pvsyst_report", """
                {
                  "doc_type_code": "technical.pvsyst_report",
                  "fields": {
                    "specific_yield": {"value": "1450 kWh/kWp", "quote": "1450 kWh/kWp", "page": 1}
                  },
                  "tables": {
                    "monthly_generation": [
                      {"month": "Jan", "glob_hor": {"value": "4.1 kWh/m2", "quote": "Jan 4.1", "page": 1},
                       "e_grid": {"value": "120 MWh", "quote": "Jan 120 MWh", "page": 1}},
                      {"month": "Feb", "glob_hor": {"value": "4.4 kWh/m2", "quote": "Feb 4.4", "page": 1},
                       "e_grid": {"value": "130 MWh", "quote": "Feb 130 MWh", "page": 1}}
                    ],
                    "losses": [
                      {"loss": "soiling", "kwh": {"value": "120 kWh", "quote": "soiling 120 kWh", "page": 1}},
                      {"loss": "Total", "kwh": {"value": "400 kWh", "quote": "Total 400 kWh", "page": 1}}
                    ]
                  }
                }
                """), pack("technical.pvsyst_report"), """
                {"assignments": [
                  {"key": "monthly_generation.e_grid|jan", "path": "Generation > Month > Grid Energy",
                   "reasoning": "January is its own month"},
                  {"key": "monthly_generation.e_grid|feb", "path": "Generation > Month > Grid Energy",
                   "reasoning": "February is its own month"},
                  {"key": "monthly_generation.glob_hor|jan", "path": "Generation > Month > Irradiation",
                   "reasoning": "January irradiation"},
                  {"key": "monthly_generation.glob_hor|feb", "path": "Generation > Month > Irradiation",
                   "reasoning": "February irradiation"},
                  {"key": "losses.kwh|soiling", "path": "Generation > Loss > Soiling Loss",
                   "reasoning": "soiling stays a detail line"},
                  {"key": "losses.kwh|total", "path": "Generation > Loss > Total Loss",
                   "reasoning": "the total is not the parent"}]}
                """);

        List<FactRow> facts = facts();
        FactRow yield = only(facts, pv, "specific_yield");
        assertThat(yield.unit()).isEqualTo("kwh_kwp");
        FactRow jan = cell(facts, pv, "monthly_generation.e_grid", "Jan");
        FactRow soiling = cell(facts, pv, "losses.kwh", "soiling");
        FactRow totalLoss = cell(facts, pv, "losses.kwh", "Total");
        assertThat(jan.ontologyPath()).isEqualTo("technical > Generation > Month > Grid Energy");
        assertThat(jan.nomenclatureStatus()).isEqualTo("bound");
        assertThat(soiling.ontologyPath()).isEqualTo("technical > Generation > Loss > Soiling Loss");
        assertThat(totalLoss.ontologyPath()).isEqualTo("technical > Generation > Loss > Total Loss");
        assertThat(totalLoss.ontologyPath()).isNotEqualTo(soiling.ontologyPath());

        long deed = ingest("deed.pdf", "Sale Deed - Jaisalmer", "land_document",
                "Sale Deed", "Survey 12/1 owner Ram Sharma village Jaisalmer area 4.5 acre",
                "12/1 area 4.5 acre", "Total area 4.5 acre");
        bind(extract(deed, "legal.land_deed", """
                {
                  "doc_type_code": "legal.land_deed",
                  "tables": {
                    "parcels": [
                      {"survey_no": "12/1", "owner": "Ram Sharma", "village": "Jaisalmer",
                       "area": {"value": "4.5 acre", "quote": "12/1 area 4.5 acre", "page": 1}},
                      {"survey_no": "Total", "owner": "Ram Sharma", "village": "Jaisalmer",
                       "area": {"value": "4.5 acre", "quote": "Total area 4.5 acre", "page": 1}}
                    ]
                  }
                }
                """), pack("legal.land_deed"), """
                {"assignments": [
                  {"key": "parcels.area|12/1", "path": "Site > Parcel > Khasra Parcel",
                   "reasoning": "the survey row is its own parcel"},
                  {"key": "parcels.area|total", "path": "Site > Parcel > Total Area",
                   "reasoning": "the total stays off the parent"}]}
                """);
        facts = facts();
        FactRow parcel = cell(facts, deed, "parcels.area", "12/1");
        FactRow parcelTotal = cell(facts, deed, "parcels.area", "Total");
        assertThat(parcel.subjectEntity()).isEqualTo("Ram Sharma");
        assertThat(parcel.siteName()).isEqualTo("Jaisalmer");
        assertThat(parcel.unit()).isEqualTo("acre");
        assertThat(parcel.ontologyPath()).isEqualTo("technical > Site > Parcel > Khasra Parcel");
        assertThat(parcelTotal.ontologyPath()).isEqualTo("technical > Site > Parcel > Total Area");
        assertThat(parcel.nomenclatureStatus()).isEqualTo("bound");

        long nw = ingest("nw.pdf", "Net Worth - Ram Sharma", "certificate",
                "Certificate of Net Worth", "Ram Sharma", "as on 31 March 2025",
                "Land Rs 12 lakh", "Land 12 lakh", "Total assets Rs 20 lakh",
                "Total assets 20 lakh", "Total liabilities 5 lakh", "Net worth 15 lakh",
                "Term loan 5 lakh");
        bind(extract(nw, "identity.promoter_net_worth", """
                {
                  "doc_type_code": "identity.promoter_net_worth",
                  "fields": {
                    "subject_name": {"value": "Ram Sharma", "quote": "Ram Sharma", "page": 1},
                    "as_of_date": {"value": "31 March 2025", "quote": "as on 31 March 2025", "page": 1},
                    "assets_total": {"value": "Rs 20 lakh", "quote": "Total assets 20 lakh", "page": 1},
                    "liabilities_total": {"value": "Rs 5 lakh", "quote": "Total liabilities 5 lakh", "page": 1},
                    "net_worth": {"value": "Rs 15 lakh", "quote": "Net worth 15 lakh", "page": 1}
                  },
                  "tables": {
                    "assets": [
                      {"asset": "Land", "holder": "Ram Sharma",
                       "amount": {"value": "Rs 12 lakh", "quote": "Land 12 lakh", "page": 1}},
                      {"asset": "Total", "holder": "Ram Sharma",
                       "amount": {"value": "Rs 20 lakh", "quote": "Total assets 20 lakh", "page": 1}}
                    ],
                    "liabilities": [
                      {"liability": "Term loan", "holder": "Ram Sharma",
                       "amount": {"value": "Rs 5 lakh", "quote": "Term loan 5 lakh", "page": 1}}
                    ]
                  }
                }
                """), pack("identity.promoter_net_worth"), """
                {"assignments": [
                  {"key": "assets.amount|land", "path": "Promoter Net Worth > Asset > Land Holding",
                   "reasoning": "land is a detail asset"},
                  {"key": "assets.amount|total", "path": "Promoter Net Worth > Asset > Total Assets",
                   "reasoning": "the assets total is not the land line"},
                  {"key": "liabilities.amount|term loan",
                   "path": "Promoter Net Worth > Liability > Term Loan",
                   "reasoning": "the loan is its own liability"}]}
                """);
        facts = facts();
        FactRow land = cell(facts, nw, "assets.amount", "Land");
        FactRow assetsTotal = cell(facts, nw, "assets.amount", "Total");
        FactRow loan = cell(facts, nw, "liabilities.amount", "Term loan");
        assertThat(land.subjectEntity()).isEqualTo("Ram Sharma");
        assertThat(land.ontologyPath())
                .isEqualTo("economic > Promoter Net Worth > Asset > Land Holding");
        assertThat(assetsTotal.ontologyPath())
                .isEqualTo("economic > Promoter Net Worth > Asset > Total Assets");
        assertThat(loan.ontologyPath())
                .isEqualTo("economic > Promoter Net Worth > Liability > Term Loan");
        assertThat(land.nomenclatureStatus()).isEqualTo("bound");
        assertThat(assetsTotal.ontologyPath()).isNotEqualTo(land.ontologyPath());
    }

    @Test
    void auditedSummaryAndDprStillExtractAndBind() throws Exception {
        long audited = ingest("audited.pdf", "Audited Financials - ABC", "audited_financials",
                "Audited Financials", "ABC Pvt Ltd", "Revenue Rs 40.2 crore",
                "Revenue 40.2", "PAT 4.1");
        long parseRun = extract(audited, "financials.audited_summary", """
                {
                  "doc_type_code": "financials.audited_summary",
                  "fields": {
                    "subject_name": {"value": "ABC Pvt Ltd", "quote": "ABC Pvt Ltd", "page": 1}
                  },
                  "tables": {
                    "headline_figures": [
                      {"financial_year": "FY2024-25",
                       "revenue": {"value": "Rs 40.2 crore", "quote": "Revenue 40.2", "page": 1},
                       "pat": {"value": "Rs 4.1 crore", "quote": "PAT 4.1", "page": 1}}
                    ]
                  }
                }
                """);
        new PackPathBinder().bind(dbPath, parseRun, MANDATE, pack("financials.audited_summary"), null);

        FactRow revenue = only(facts(), audited, "revenue");
        assertThat(revenue.fieldKey()).isEqualTo("revenue");
        assertThat(revenue.lineKey()).isNull();
        assertThat(revenue.ontologyPath()).isEqualTo("economic > Profit & Loss > Revenue");
        assertThat(revenue.nomenclatureStatus()).isEqualTo("bound");

        long dpr = ingest("dpr.pdf", "DPR - Bhagyanagar", "dpr",
                "Detailed Project Report", "Bhagyanagar Solar", "Inverter 10 nos",
                "Total 11 nos", "Project cost Rs 48 crore", "Project cost 48 crore");
        bind(extract(dpr, "report.dpr", """
                {
                  "doc_type_code": "report.dpr",
                  "fields": {
                    "project_name": {"value": "Bhagyanagar Solar", "quote": "Bhagyanagar Solar", "page": 1}
                  },
                  "tables": {
                    "plant_and_machinery": [
                      {"description": "Inverter",
                       "qty": {"value": "10 nos", "quote": "Inverter 10 nos", "page": 1}},
                      {"description": "Total",
                       "qty": {"value": "11 nos", "quote": "Total 11 nos", "page": 1}}
                    ],
                    "cost_summary": [
                      {"project_cost": {"value": "Rs 48 crore", "quote": "Project cost 48 crore", "page": 1}}
                    ]
                  }
                }
                """), pack("report.dpr"), """
                {"assignments": [
                  {"key": "plant_and_machinery.qty|inverter",
                   "path": "Plant and machinery > Item > Inverter",
                   "reasoning": "the inverter is its own equipment line"},
                  {"key": "plant_and_machinery.qty|total",
                   "path": "Plant and machinery > Item > Total Plant",
                   "reasoning": "the total stays distinct from the inverter"}]}
                """);

        List<FactRow> facts = facts();
        FactRow inverter = cell(facts, dpr, "plant_and_machinery.qty", "Inverter");
        FactRow plantTotal = cell(facts, dpr, "plant_and_machinery.qty", "Total");
        FactRow cost = only(facts, dpr, "project_cost");
        assertThat(inverter.ontologyPath())
                .isEqualTo("technical > Plant and machinery > Item > Inverter");
        assertThat(plantTotal.ontologyPath())
                .isEqualTo("technical > Plant and machinery > Item > Total Plant");
        assertThat(inverter.nomenclatureStatus()).isEqualTo("bound");
        assertThat(cost.fieldKey()).isEqualTo("project_cost");
        assertThat(cost.lineKey()).isNull();
    }

    private static DocTypePack pack(String code) throws Exception {
        return PackLoader.loadSeedPacks().stream()
                .filter(p -> com.resurgent.tev.doc.pack.PackCodes.same(p.code(), code))
                .findFirst()
                .orElseThrow();
    }

    private static JsonNode columns(DocTypePack pack, String table) {
        return pack.toExtractSchema().path("properties").path(table).path("items").path("properties");
    }

    private long ingest(String fileName, String logicalName, String fileRole, String... lines)
            throws Exception {
        Path pdf = tempDir.resolve(fileName);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = 720;
                for (String line : lines) {
                    cs.beginText();
                    cs.setFont(font, 11);
                    cs.newLineAtOffset(72, y);
                    cs.showText(line);
                    cs.endText();
                    y -= 18;
                }
            }
            doc.save(pdf.toFile());
        }
        long versionId = new DocumentIngestService().ingest(
                new DocumentIngestService.IngestRequest(
                        pdf, MANDATE, logicalName, "v1", fileRole, null),
                dbPath, blobs).documentVersionId();
        new DocumentLayoutService().layout(versionId, dbPath, blobs);
        return versionId;
    }

    private long extract(long versionId, String code, String fixture) throws Exception {
        FixtureExtractClient client = new FixtureExtractClient();
        client.putFixture(code, MAPPER.readTree(fixture));
        ExtractService.ExtractOutcome outcome = new ExtractService().extract(
                dbPath, versionId, MANDATE, pack(code), client, "test");
        assertThat(outcome.status()).isEqualTo("success");
        return outcome.parseRunId();
    }

    private void bind(long parseRunId, DocTypePack pack, String reply) throws Exception {
        new PackPathBinder().bind(dbPath, parseRunId, MANDATE, pack, new FakeDocLlmClient(reply));
    }

    private List<FactRow> facts() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            return new FactRepository(db.connection()).query(FactQuery.forMandate(MANDATE));
        }
    }

    private static FactRow cell(List<FactRow> facts, long versionId, String fieldKey, String lineKey) {
        return facts.stream()
                .filter(f -> f.documentVersionId() == versionId
                        && fieldKey.equals(f.fieldKey())
                        && lineKey.equals(f.lineKey()))
                .findFirst()
                .orElseThrow();
    }

    private static FactRow only(List<FactRow> facts, long versionId, String fieldKey) {
        List<FactRow> matched = facts.stream()
                .filter(f -> f.documentVersionId() == versionId && fieldKey.equals(f.fieldKey()))
                .toList();
        assertThat(matched).hasSize(1);
        return matched.get(0);
    }
}
