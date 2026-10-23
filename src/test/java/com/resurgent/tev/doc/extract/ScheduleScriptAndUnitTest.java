package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Indian land and technical units share one canonical key, and Indic text keeps
 * the source script in provenance while the stored value, line and entity are
 * transliterated for matching.
 */
class ScheduleScriptAndUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void landAndTechnicalUnitsUseCanonicalKeys() {
        assertQuantity("4.5 acres", 4.5, "acre");
        assertQuantity("2 hectares", 2.0, "hectare");
        assertQuantity("10 sq m", 10.0, "sq_m");
        assertQuantity("100 sq.ft", 100.0, "sq_ft");
        assertQuantity("50 sq yd", 50.0, "sq_yd");
        assertQuantity("3 guntha", 3.0, "guntha");
        assertQuantity("1.5 bigha", 1.5, "bigha");
        assertQuantity("8 kanal", 8.0, "kanal");
        assertQuantity("4 marla", 4.0, "marla");
        assertQuantity("6 cent", 6.0, "cent");
        assertQuantity("2 are", 2.0, "are");
        assertQuantity("1200 kWp", 1200.0, "kwp");
        assertQuantity("1.2 MWp", 1.2, "mwp");
        assertQuantity("4.8 kWh/m2", 4.8, "kwh_m2");
        assertQuantity("1450 kWh/kWp", 1450.0, "kwh_kwp");
        assertQuantity("12 MWh/yr", 12.0, "mwh_yr");
        assertQuantity("50 TPD", 50.0, "tpd");
        assertQuantity("2 KLPD", 2.0, "klpd");
        assertQuantity("5 MVA", 5.0, "mva");
        assertQuantity("33 kV", 33.0, "kv");

        RateNormalizer.Result yield = RateNormalizer.parse("1450 kWh/kWp");
        assertThat(yield.valueNumber()).isEqualTo(1450.0);
        assertThat(yield.unit()).isEqualTo("kwh_kwp");
    }

    @Test
    void indicScriptsTransliterateForMatchingAndKeepTheirLanguage() {
        assertThat(ScriptNormalizer.matchKey("\u0930\u093e\u092e \u0936\u0930\u094d\u092e\u093e"))
                .isEqualTo("ram sharma")
                .isEqualTo(ScriptNormalizer.matchKey("Ram Sharma"));
        assertThat(ScriptNormalizer.lang("\u0930\u093e\u092e")).isEqualTo("hi");
        assertThat(ScriptNormalizer.lang("\u0924\u093e\u0902\u092c\u0947\u0933")).isEqualTo("mr");
        assertThat(ScriptNormalizer.lang("\u0ab0\u0abe\u0aae")).isEqualTo("gu");
        assertThat(ScriptNormalizer.matchKey("\u0ab0\u0abe\u0aae")).isEqualTo("ram");
        assertThat(ScriptNormalizer.lang("\u0bb0\u0bbe\u0bae\u0bcd")).isEqualTo("ta");
        assertThat(ScriptNormalizer.matchKey("\u0bb0\u0bbe\u0bae\u0bcd")).isEqualTo("ram");
        assertThat(ScriptNormalizer.lang("Ram Sharma")).isEqualTo("en");
        assertThat(ScriptNormalizer.matchKey("\u0967\u0968")).isEqualTo("12");
    }

    @Test
    void hindiScheduleCellKeepsSourceScriptInProvenance(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("workspace.db");
        long versionId;
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            long sourceFileId = repo.insertSourceFile(1L, "deed.pdf", "hash-hi", "pdf",
                    "2026-01-01T00:00:00Z", "0.1.0", null);
            long documentId = docs.insertDocument(1L, "Deed", "land_document", null);
            versionId = docs.insertDocumentVersion(documentId, "v1", "deed.pdf",
                    "hash-hi", ".pdf", "land_document", 1, false, sourceFileId, null);
        }
        DocTypePack pack = PackLoader.load(new ByteArrayInputStream("""
                code: demo.schedule
                version: 1.0.0
                family: legal
                file_role: land_document
                fields:
                  - key: grantor
                    path: identity > Legal Name
                    path_root: identity
                    value_kind: text
                    required: false
                tables:
                  - name: parcels
                    path: technical > Site > Parcel
                    path_root: technical
                    key_column: survey_no
                    entity_key: owner
                    site_key: village
                    fields:
                      - key: survey_no
                        value_kind: text
                      - key: owner
                        value_kind: text
                      - key: village
                        value_kind: text
                      - key: area
                        value_kind: quantity
                """.getBytes(StandardCharsets.UTF_8)), "hi-pack");
        FixtureExtractClient client = new FixtureExtractClient();
        client.putFixture("demo.schedule", MAPPER.readTree("""
                {
                  "doc_type_code": "demo.schedule",
                  "tables": {
                    "parcels": [
                      {"survey_no": "सर्वे १२", "owner": "राम शर्मा", "village": "जैसलमेर",
                       "area": {"value": "४.५ एकड़", "quote": "४.५ एकड़", "page": 1}}
                    ]
                  }
                }
                """));
        new ExtractService().extract(dbPath, versionId, 1L, pack, client, "test");

        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath);
                Statement st = db.connection().createStatement();
                ResultSet rs = st.executeQuery("""
                        SELECT f.line_key, f.subject_entity, f.value_text, f.value_number,
                               f.unit, f.lang_source, p.verbatim
                        FROM fact f
                        JOIN fact_evidence e ON e.fact_id = f.fact_id AND e.role = 'verbatim_quote'
                        JOIN provenance_doc p ON p.provenance_id = e.provenance_id
                        WHERE f.field_key = 'parcels.area'
                        """)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("line_key")).isEqualTo("sarve 12");
            assertThat(rs.getString("subject_entity")).isEqualTo("ram sharma");
            assertThat(rs.getString("value_text")).doesNotContain("४").doesNotContain("एक");
            assertThat(rs.getDouble("value_number")).isEqualTo(4.5);
            assertThat(rs.getString("unit")).isEqualTo("acre");
            assertThat(rs.getString("lang_source")).isEqualTo("hi");
            assertThat(rs.getString("verbatim")).isEqualTo("४.५ एकड़");
            assertThat(rs.getString("subject_entity"))
                    .isEqualTo(ScriptNormalizer.matchKey("Ram Sharma"));
        }
    }

    private static void assertQuantity(String raw, double value, String unit) {
        QuantityNormalizer.Result parsed = QuantityNormalizer.parse(raw);
        assertThat(parsed.valueNumber()).as(raw).isEqualTo(value);
        assertThat(parsed.unit()).as(raw).isEqualTo(unit);
    }
}
