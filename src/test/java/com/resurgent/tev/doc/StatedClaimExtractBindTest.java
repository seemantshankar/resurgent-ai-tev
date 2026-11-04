package com.resurgent.tev.doc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.doc.bind.PackPathBinder;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.db.MandatePolicyRepository;
import com.resurgent.tev.doc.extract.ExtractClient;
import com.resurgent.tev.doc.extract.ExtractRequest;
import com.resurgent.tev.doc.extract.ExtractResult;
import com.resurgent.tev.doc.extract.ExtractService;
import com.resurgent.tev.doc.extract.ExtractStats;
import com.resurgent.tev.doc.extract.ValueKind;
import com.resurgent.tev.doc.induce.PackStore;
import com.resurgent.tev.doc.pack.ClauseSection;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackCodes;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.doc.residency.DataResidency;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Stated claims from unread sentences (#208, ADR 0036). */
class StatedClaimExtractBindTest {

    static final String LAND_S1 = "The project of Arham Ventures, a large company, sits on a"
            + " land parcel of 150 acres north of Bhiwadi, Rajasthan.";
    static final String LAND_S2 = "The land is marked agricultural and conversion to industrial"
            + " has been initiated.";
    static final String LAND_S3 = "Conversion sanction should be expected on 5 October 2026.";
    static final String LAND_S4 = "The pollution category is green and byproducts are"
            + " biodegradable.";
    static final String LAND_PARAGRAPH = LAND_S1 + " " + LAND_S2 + " " + LAND_S3 + " " + LAND_S4;

    static final Set<String> EIGHT_KEYS = Set.of(
            "land_area", "place", "present_marking", "conversion_initiated",
            "target_use", "conversion_sanction_date", "pollution_category", "byproducts");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MANDATE = 1L;

    @TempDir
    Path tempDir;

    private Path dbPath;
    private long documentId;
    private DocTypePack thinReport;

    static final class SilentCloud implements ExtractClient {
        @Override
        public String id() {
            return "cloud";
        }

        @Override
        public ExtractResult extractFieldSchema(ExtractRequest req, JsonNode schema) {
            throw new AssertionError("local-only must not call cloud");
        }

        @Override
        public ExtractResult extractStatedClaims(ExtractRequest req) {
            throw new AssertionError("local-only must not call cloud for stated claims");
        }
    }

    static class RecordingExtractClient implements ExtractClient {
        int fieldCalls;
        int claimCalls;

        @Override
        public String id() {
            return "cloud-extract";
        }

        @Override
        public ExtractResult extractFieldSchema(ExtractRequest req, JsonNode schema) {
            fieldCalls++;
            return new ExtractResult(id(), MAPPER.createObjectNode(), List.of(), List.of(),
                    ExtractStats.empty());
        }

        @Override
        public ExtractResult extractStatedClaims(ExtractRequest req) {
            claimCalls++;
            String lines = req.options().getOrDefault(ExtractRequest.STATED_CLAIM_SENTENCES, "");
            if (!lines.contains("Bhiwadi") && !lines.contains("biodegradable")) {
                return new ExtractResult(id(), MAPPER.createObjectNode(), List.of(), List.of(),
                        ExtractStats.empty());
            }
            ObjectNode payload = MAPPER.createObjectNode();
            ArrayNode claims = payload.putArray("claims");
            claim(claims, "land_area", "150 acres", LAND_S1, "quantity");
            claim(claims, "place", "north of Bhiwadi, Rajasthan", LAND_S1, "text");
            claim(claims, "large", "large", LAND_S1, "text");
            claim(claims, "company", "Arham Ventures", LAND_S1, "text");
            claim(claims, "present_marking", "agricultural", LAND_S2, "text");
            claim(claims, "conversion_initiated", "initiated", LAND_S2, "text");
            claim(claims, "target_use", "industrial", LAND_S2, "text");
            claim(claims, "conversion_sanction_date", "5 October 2026", LAND_S3, "date");
            claim(claims, "pollution_category", "green", LAND_S4, "text");
            claim(claims, "byproducts", "biodegradable", LAND_S4, "text");
            return new ExtractResult(id(), payload, List.of(), List.of(), ExtractStats.local(1));
        }

        private static void claim(ArrayNode claims, String key, String value, String quote,
                String kind) {
            ObjectNode node = claims.addObject();
            node.put("key", key);
            node.put("value", value);
            node.put("quote", quote);
            node.put("kind", kind);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        thinReport = new DocTypePack(
                "report.dpr", "1.0.0", "dpr", "dpr", List.of("general"), List.of(),
                List.of(PackField.required("project_name", "identity > Project > Name",
                        "identity", ValueKind.TEXT)),
                List.of(), List.of(), List.of(), null, ClauseSection.NONE, List.of());
        documentId = stageOn(dbPath, "Project report", "dpr", thinReport.code(),
                List.of("Project name : Test Project", LAND_PARAGRAPH),
                DataResidency.CLOUD_ALLOWED);
    }

    @Test
    void landParagraphYieldsEightFactsWithWholeSentenceQuotes() throws Exception {
        RecordingExtractClient cloud = new RecordingExtractClient();
        ExtractService.ExtractOutcome extract = new ExtractService().read(
                dbPath, documentId, MANDATE, thinReport, cloud, "test");
        assertThat(extract.status()).isEqualTo("success");
        assertThat(cloud.claimCalls).isEqualTo(1);

        List<FactRow> facts = factsOf(documentId);
        assertThat(facts).extracting(FactRow::fieldKey).containsAll(EIGHT_KEYS);
        assertThat(facts).noneMatch(f -> "large".equalsIgnoreCase(f.fieldKey())
                || "large".equalsIgnoreCase(f.valueText()));
        assertThat(facts).noneMatch(f -> "company".equals(f.fieldKey())
                || "Arham Ventures".equals(f.valueText()));

        for (FactRow fact : facts) {
            if (!EIGHT_KEYS.contains(fact.fieldKey())) {
                continue;
            }
            String quote = fact.evidence().get(0).verbatim();
            assertThat(quote).isIn(LAND_S1, LAND_S2, LAND_S3, LAND_S4);
            assertThat(quote).doesNotContain("identity >");
        }
        assertThat(quoteOf(facts, "land_area")).isEqualTo(LAND_S1);
        assertThat(quoteOf(facts, "present_marking")).isEqualTo(LAND_S2);
        assertThat(quoteOf(facts, "conversion_initiated")).isEqualTo(LAND_S2);
        assertThat(quoteOf(facts, "target_use")).isEqualTo(LAND_S2);

        PackPathBinder.BindOutcome bind = new PackPathBinder().bind(
                dbPath, extract.parseRunId(), MANDATE, thinReport, null);
        assertThat(bind.bound()).isGreaterThanOrEqualTo(8);
        DocTypePack published = new PackStore(tempDir.resolve("packs"))
                .latest("general", thinReport.code()).orElseThrow();
        assertThat(published.fields()).extracting(PackField::key)
                .contains("place", "pollution_category");
        assertThat(published.fields().stream().filter(f -> "place".equals(f.key()))
                .findFirst().orElseThrow().required()).isFalse();
    }

    @Test
    void placePhraseIsOneFact() throws Exception {
        new ExtractService().read(dbPath, documentId, MANDATE, thinReport,
                new RecordingExtractClient(), "test");
        List<FactRow> facts = factsOf(documentId);
        assertThat(facts.stream().filter(f -> "place".equals(f.fieldKey())).count()).isEqualTo(1);
        FactRow place = byKey(facts, "place");
        assertThat(place.valueText()).isEqualTo("north of Bhiwadi, Rajasthan");
        assertThat(place.evidence().get(0).verbatim()).isEqualTo(LAND_S1);
    }

    @Test
    void hedgeShouldStaysInQuoteDateIsValue() throws Exception {
        new ExtractService().read(dbPath, documentId, MANDATE, thinReport,
                new RecordingExtractClient(), "test");
        FactRow date = byKey(factsOf(documentId), "conversion_sanction_date");
        assertThat(date.valueText()).isEqualTo("5 October 2026");
        assertThat(date.evidence().get(0).verbatim()).contains("should");
        assertThat(date.evidence().get(0).verbatim()).isEqualTo(LAND_S3);
    }

    @Test
    void repeatsLabeledLineIsSameFact() throws Exception {
        Path tanDb = tempDir.resolve("tan-repeat.db");
        DocTypePack certificate = new DocTypePack(
                "corporate_governance.certificate_of_incorporation", "1.0.0",
                "certificate", "certificate", List.of("general"), List.of(),
                List.of(PackField.required("cin", "identity > CIN", "identity", ValueKind.TEXT)),
                List.of(), List.of(), List.of(), null, ClauseSection.NONE, List.of());
        long docId;
        try (WorkspaceDatabase db = WorkspaceDatabase.open(tanDb)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            long sourceId = repo.insertSourceFile(MANDATE, "coi.pdf", "hash-tan", "pdf",
                    "2026-01-01T00:00:00Z", "0.1.0", null);
            docId = docs.insertDocument(MANDATE, "COI", "certificate", certificate.code());
            long versionId = docs.insertDocumentVersion(docId, "v1", "coi.pdf", "hash-tan",
                    ".pdf", "certificate", 1, false, sourceId, null);
            long pageId = docs.insertPage(versionId, 1, 600, 800, null);
            docs.insertBlock(pageId, 0, "paragraph", null, null, null, null,
                    "TAN : HYDB16570B", "TAN : HYDB16570B", 0, "test");
            docs.insertBlock(pageId, 1, "paragraph", null, null, null, null,
                    "The tax account number TAN is HYDB16570B as well.",
                    "The tax account number TAN is HYDB16570B as well.", 1, "test");
            member(db, docId, pageId);
            new MandatePolicyRepository(db.connection()).upsert(MANDATE, DataResidency.CLOUD_ALLOWED);
        }
        RecordingExtractClient cloud = new RecordingExtractClient() {
            @Override
            public ExtractResult extractStatedClaims(ExtractRequest req) {
                claimCalls++;
                ObjectNode payload = MAPPER.createObjectNode();
                ArrayNode claims = payload.putArray("claims");
                ObjectNode node = claims.addObject();
                node.put("key", "tan");
                node.put("value", "HYDB16570B");
                node.put("quote", "The tax account number TAN is HYDB16570B as well.");
                node.put("kind", "text");
                return new ExtractResult(id(), payload, List.of(), List.of(), ExtractStats.local(1));
            }
        };
        new ExtractService().read(tanDb, docId, MANDATE, certificate, cloud, "test");
        List<FactRow> facts = factsOn(tanDb, docId);
        assertThat(facts.stream().filter(f -> "tan".equals(f.fieldKey())).count()).isEqualTo(1);
        assertThat(byKey(facts, "tan").valueText()).isEqualTo("HYDB16570B");
    }

    @Test
    void localOnlyLeavesProseUnread() throws Exception {
        residency(DataResidency.LOCAL_ONLY);
        SilentCloud cloud = new SilentCloud();
        ExtractService.ExtractOutcome extract = new ExtractService().read(
                dbPath, documentId, MANDATE, thinReport, cloud, "test");
        assertThat(extract.status()).isIn("success", "partial");
        List<FactRow> facts = factsOf(documentId);
        assertThat(facts).noneMatch(f -> EIGHT_KEYS.contains(f.fieldKey()));
    }

    @Test
    void forceMajeureStaysClauseNotClaims() throws Exception {
        Path ppaDb = tempDir.resolve("ppa.db");
        DocTypePack ppa = PackLoader.loadClasspath("docpacks/agreement.ppa.yaml");
        long docId = stageOn(ppaDb, "PPA", "agreement", ppa.code(), List.of(
                "13. FORCE MAJEURE",
                "Neither party is liable for failures caused by force majeure."),
                DataResidency.CLOUD_ALLOWED);
        RecordingExtractClient cloud = new RecordingExtractClient();
        new ExtractService().read(ppaDb, docId, MANDATE, ppa, cloud, "test");
        assertThat(clauseCount(ppaDb)).isEqualTo(1);
        List<FactRow> facts = factsOn(ppaDb, docId);
        assertThat(facts).noneMatch(f -> f.evidence().stream()
                .anyMatch(e -> e.verbatim() != null
                        && e.verbatim().toLowerCase().contains("force majeure")));
    }

    @Test
    void dprLandParagraphIsStatedClaimsNotClauses() throws Exception {
        DocTypePack dpr = PackLoader.findSeed(PackCodes.PROJECT_REPORT);
        assertThat(dpr.declaresClauses()).isFalse();
        Path dprDb = tempDir.resolve("dpr.db");
        long docId = stageOn(dprDb, "DPR", "dpr", dpr.code(), List.of(LAND_PARAGRAPH),
                DataResidency.CLOUD_ALLOWED);
        new ExtractService().read(dprDb, docId, MANDATE, dpr, new RecordingExtractClient(), "test");
        assertThat(clauseCount(dprDb)).isEqualTo(0);
        List<FactRow> facts = factsOn(dprDb, docId);
        assertThat(facts).extracting(FactRow::fieldKey).contains(
                "land_area", "place", "present_marking", "byproducts");
    }

    private long stageDocument(String name, String family, String packCode, String text,
            String residency) throws Exception {
        return stageOn(dbPath, name, family, packCode, List.of(text), residency);
    }

    private long stageOn(Path dbFile, String name, String family, String packCode,
            List<String> lines, String residency) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbFile)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            long sourceId = repo.insertSourceFile(MANDATE, name + ".pdf", "hash-" + name, "pdf",
                    "2026-01-01T00:00:00Z", "0.1.0", null);
            long docId = docs.insertDocument(MANDATE, name, family, packCode);
            long versionId = docs.insertDocumentVersion(docId, "v1", name + ".pdf", "hash-" + name,
                    ".pdf", family, 1, false, sourceId, null);
            long pageId = docs.insertPage(versionId, 1, 600, 800, null);
            for (int i = 0; i < lines.size(); i++) {
                String text = lines.get(i);
                docs.insertBlock(pageId, i, "paragraph", null, null, null, null,
                        text, text, i, "test");
            }
            member(db, docId, pageId);
            new MandatePolicyRepository(db.connection()).upsert(MANDATE, residency);
            return docId;
        }
    }

    private void residency(String mode) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            new MandatePolicyRepository(db.connection()).upsert(MANDATE, mode);
        }
    }

    private static void member(WorkspaceDatabase db, long documentId, long pageId)
            throws Exception {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT INTO document_page (document_id, page_id) VALUES (?, ?)")) {
            ps.setLong(1, documentId);
            ps.setLong(2, pageId);
            ps.executeUpdate();
        }
    }

    private List<FactRow> factsOf(long docId) throws Exception {
        return factsOn(dbPath, docId);
    }

    private static List<FactRow> factsOn(Path dbFile, long docId) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbFile)) {
            return new FactRepository(db.connection())
                    .query(FactQuery.forMandate(MANDATE).withDocument(docId));
        }
    }

    private static long clauseCount(Path dbFile) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbFile);
                PreparedStatement ps = db.connection().prepareStatement(
                        "SELECT COUNT(*) FROM clause");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static FactRow byKey(List<FactRow> facts, String key) {
        return facts.stream().filter(f -> key.equals(f.fieldKey())).findFirst().orElseThrow();
    }

    private static String quoteOf(List<FactRow> facts, String key) {
        return byKey(facts, key).evidence().get(0).verbatim();
    }
}
