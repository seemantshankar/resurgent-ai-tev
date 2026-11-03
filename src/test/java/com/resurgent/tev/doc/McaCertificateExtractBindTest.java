package com.resurgent.tev.doc;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.bind.PackPathBinder;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.db.MandatePolicyRepository;
import com.resurgent.tev.doc.extract.ExtractService;
import com.resurgent.tev.doc.extract.ValueKind;
import com.resurgent.tev.doc.induce.PackStore;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.doc.residency.DataResidency;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MCA Certificate of Incorporation prose ({@code The … of the company is …})
 * becomes facts with page quotes (#204 / #205).
 */
class McaCertificateExtractBindTest {

    private static final long MANDATE = 1L;
    private static final String CIN_LINE =
            "The Corporate Identity Number of the company is U35106TS2026PTC209425";
    private static final String PAN_LINE =
            "The Permanent Account Number (PAN) of the company is AAOCB3072G*";
    private static final String TAN_LINE =
            "The Tax Deduction and Collection Account Number (TAN) of the company is HYDB16570B*";
    private static final String OFFICE_LINE =
            "2nd Floor, Surya Towers, S.P. Road, Secunderabad, Secunderabad, Hyderabad,"
                    + " Hyderabad- 500003, Telangana";

    @TempDir
    Path tempDir;

    private Path dbPath;
    private long documentId;
    private DocTypePack thinCertificate;

    static final class SilentCloud implements com.resurgent.tev.doc.extract.ExtractClient {
        @Override
        public String id() {
            return "cloud";
        }

        @Override
        public com.resurgent.tev.doc.extract.ExtractResult extractFieldSchema(
                com.resurgent.tev.doc.extract.ExtractRequest req,
                com.fasterxml.jackson.databind.JsonNode schema) {
            throw new AssertionError("MCA certificate reading must not need cloud");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        thinCertificate = new DocTypePack(
                "corporate_governance.certificate_of_incorporation", "1.0.0",
                "certificate", "certificate", List.of("general"), List.of(),
                List.of(PackField.required("cin", "identity > CIN", "identity", ValueKind.TEXT)),
                List.of(), List.of(), List.of(), null,
                com.resurgent.tev.doc.pack.ClauseSection.NONE, List.of());

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            long sourceId = repo.insertSourceFile(MANDATE, "coi.pdf", "hash", "pdf",
                    "2026-01-01T00:00:00Z", "0.1.0", null);
            documentId = docs.insertDocument(MANDATE, "COI", "certificate",
                    thinCertificate.code());
            long versionId = docs.insertDocumentVersion(documentId, "v1", "coi.pdf", "hash",
                    ".pdf", "certificate", 1, false, sourceId, null);
            long pageId = docs.insertPage(versionId, 1, 600, 800, null);
            int i = 0;
            for (String line : List.of(
                    "Certificate of Incorporation",
                    "I hereby certify that BHAGYANAGAR SOLAR PRIVATE LIMITED is incorporated"
                            + " on this EIGHTH day of JANUARY TWO THOUSAND TWENTY SIX",
                    CIN_LINE,
                    PAN_LINE,
                    TAN_LINE,
                    "Mailing Address as per record available in Registrar of Companies office:",
                    "BHAGYANAGAR SOLAR PRIVATE LIMITED",
                    OFFICE_LINE)) {
                docs.insertBlock(pageId, i, "paragraph", null, null, null, null,
                        line, line, i, "test");
                i++;
            }
            try (var ps = db.connection().prepareStatement(
                    "INSERT INTO document_page (document_id, page_id) VALUES (?, ?)")) {
                ps.setLong(1, documentId);
                ps.setLong(2, pageId);
                ps.executeUpdate();
            }
            new MandatePolicyRepository(db.connection()).upsert(MANDATE, DataResidency.LOCAL_ONLY);
        }
    }

    @Test
    void mcaProseYieldsCinTanPanAndOfficeBoundWithPageQuotes() throws Exception {
        ExtractService.ExtractOutcome extract = new ExtractService().read(
                dbPath, documentId, MANDATE, thinCertificate, new SilentCloud(), "test");
        assertThat(extract.status()).isEqualTo("success");

        List<FactRow> facts = factsOf(documentId);
        FactRow cin = byKey(facts, "cin");
        FactRow tan = byKey(facts, "tan");
        FactRow pan = byKey(facts, "pan");
        FactRow office = byKey(facts, "registered_office");

        assertThat(cin.valueText()).isEqualTo("U35106TS2026PTC209425");
        assertThat(cin.evidence().get(0).verbatim()).isEqualTo(CIN_LINE);
        assertThat(tan.valueText()).isEqualTo("HYDB16570B");
        assertThat(tan.evidence().get(0).verbatim()).isEqualTo(TAN_LINE);
        assertThat(pan.valueText()).isEqualTo("AAOCB3072G");
        assertThat(pan.evidence().get(0).verbatim()).isEqualTo(PAN_LINE);
        assertThat(office.valueText()).contains("Surya Towers");
        assertThat(office.evidence().get(0).verbatim()).contains("Surya Towers");

        PackPathBinder.BindOutcome bind = new PackPathBinder().bind(
                dbPath, extract.parseRunId(), MANDATE, thinCertificate, null);
        assertThat(bind.bound()).isGreaterThanOrEqualTo(3);

        facts = factsOf(documentId);
        tan = byKey(facts, "tan");
        assertThat(tan.nomenclatureStatus()).isEqualTo("bound");
        assertThat(tan.ontologyPath()).contains("TAN");
        assertThat(tan.ontologyPath()).doesNotContain("Project Cost");
        assertThat(byKey(facts, "cin").nomenclatureStatus()).isEqualTo("bound");
        assertThat(byKey(facts, "cin").ontologyPath()).contains("CIN");
        assertThat(byKey(facts, "pan").nomenclatureStatus()).isEqualTo("bound");
        assertThat(byKey(facts, "pan").ontologyPath()).contains("PAN");

        DocTypePack published = new PackStore(tempDir.resolve("packs"))
                .latest("general", thinCertificate.code()).orElseThrow();
        assertThat(published.fields()).extracting(PackField::key).contains("cin", "tan");
        assertThat(published.fields().stream().filter(f -> "tan".equals(f.key()))
                .findFirst().orElseThrow().required()).isFalse();
        assertThat(published.fields().stream().filter(f -> "cin".equals(f.key()))
                .findFirst().orElseThrow().required()).isTrue();
        assertThat(PackLoader.loadSeedPacks()).noneMatch(p -> p.fields().stream()
                .anyMatch(f -> "tan".equals(f.key())));
    }

    private List<FactRow> factsOf(long docId) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            return new FactRepository(db.connection())
                    .query(FactQuery.forMandate(MANDATE).withDocument(docId));
        }
    }

    private static FactRow byKey(List<FactRow> facts, String key) {
        return facts.stream().filter(f -> key.equals(f.fieldKey())).findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing fact " + key + " in "
                                + facts.stream().map(FactRow::fieldKey).toList()));
    }
}
