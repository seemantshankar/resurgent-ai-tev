package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.db.CloudParsePageRepository;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A record schedule and a period matrix become one fact per value cell, with the
 * row key on {@code line_key} and {@code field_key = table.column}. Detail lines
 * stay distinct from the total row.
 */
class ScheduleLineExtractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MANDATE = 1L;

    private static final String PACK_YAML = """
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
                  - key: consideration
                    value_kind: money
              - name: monthly_generation
                path: technical > Generation > Month
                path_root: technical
                key_column: loss
                period_key: month
                fields:
                  - key: loss
                    value_kind: text
                  - key: month
                    value_kind: text
                  - key: glob_hor
                    value_kind: quantity
            """;

    @TempDir
    Path tempDir;

    private Path dbPath;
    private long documentVersionId;
    private DocTypePack pack;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            long sourceFileId = repo.insertSourceFile(MANDATE, "deed.pdf", "hash-deed", "pdf",
                    "2026-01-01T00:00:00Z", "0.1.0", null);
            long documentId = docs.insertDocument(MANDATE, "Deed", "land_document", null);
            documentVersionId = docs.insertDocumentVersion(documentId, "v1", "deed.pdf",
                    "hash-deed", ".pdf", "land_document", 4, false, sourceFileId, null);
        }
        pack = PackLoader.load(
                new ByteArrayInputStream(PACK_YAML.getBytes(StandardCharsets.UTF_8)),
                "schedule-pack");
        insertLayout("12/1 area 4.5 acre 12/1 price 10 lakh 13/2 area 2 guntha 13/2 price 3 lakh "
                + "Total area 99 acre Total price 99 lakh soiling Jan 4.1 soiling Feb 4.4 "
                + "Total Jan 9.9 active Rs acre guntha lakh");
    }

    private void insertLayout(String text) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            DocumentRepository docs = new DocumentRepository(db.connection());
            List<String> pages = List.of(text, text, text, text);
            for (int i = 1; i <= 4; i++) {
                long pageId = docs.insertPage(documentVersionId, i, 600, 800,
                        "hash-deed/pages/p00" + i + ".png");
                long blockId = docs.insertBlock(pageId, 0, "paragraph",
                        72.0, 600.0, 500.0, 640.0, text, text, 0, "test");
                docs.insertSpan(blockId, 0, text, 72.0, 600.0, 500.0, 620.0, 0.99);
            }
            new CloudParsePageRepository(db.connection())
                    .replacePages(documentVersionId, "pjb-layout", "agentic", pages, pages);
        }
    }

    @Test
    void recordScheduleAndPeriodMatrixEmitOneFactPerValueCell() throws Exception {
        FixtureExtractClient client = new FixtureExtractClient();
        client.putFixture("demo.schedule", MAPPER.readTree("""
                {
                  "doc_type_code": "demo.schedule",
                  "tables": {
                    "parcels": [
                      {"survey_no": "12/1", "owner": "Ram Sharma", "village": "Jaisalmer",
                       "area": {"value": "4.5 acre", "quote": "12/1 area 4.5 acre", "page": 2},
                       "consideration": {"value": "Rs 10 lakh", "quote": "12/1 price 10 lakh", "page": 2}},
                      {"survey_no": "13/2", "owner": "Sita Devi", "village": "Barmer",
                       "area": {"value": "2 guntha", "quote": "13/2 area 2 guntha", "page": 2},
                       "consideration": {"value": "Rs 3 lakh", "quote": "13/2 price 3 lakh", "page": 2}},
                      {"survey_no": "Total", "owner": "Ram Sharma", "village": "Jaisalmer",
                       "area": {"value": "99 acre", "quote": "Total area 99 acre", "page": 3},
                       "consideration": {"value": "Rs 99 lakh", "quote": "Total price 99 lakh", "page": 3}}
                    ],
                    "monthly_generation": [
                      {"loss": "soiling", "month": "Jan",
                       "glob_hor": {"value": "4.1", "quote": "soiling Jan 4.1", "page": 4}},
                      {"loss": "soiling", "month": "Feb",
                       "glob_hor": {"value": "4.4", "quote": "soiling Feb 4.4", "page": 4}},
                      {"loss": "Total", "month": "Jan",
                       "glob_hor": {"value": "9.9", "quote": "Total Jan 9.9", "page": 4}}
                    ]
                  }
                }
                """));

        ExtractService.ExtractOutcome outcome = new ExtractService().extract(
                dbPath, documentVersionId, MANDATE, pack, client, "test");
        assertThat(outcome.status()).isEqualTo("success");

        List<FactRow> facts = facts();
        assertThat(facts).extracting(FactRow::fieldKey)
                .contains("parcels.area", "parcels.consideration", "monthly_generation.glob_hor")
                .doesNotContain("area", "survey_no", "owner", "village", "glob_hor", "loss");

        FactRow parcel = cell(facts, "parcels.area", "12/1");
        assertThat(parcel.ontologyPath()).isEqualTo("technical > Site > Parcel");
        assertThat(parcel.valueText()).isEqualTo("4.5 acre");
        assertThat(quoteOf(parcel)).isEqualTo("12/1 area 4.5 acre");

        FactRow otherParcel = cell(facts, "parcels.area", "13/2");
        assertThat(otherParcel.valueText()).isEqualTo("2 guntha");

        FactRow totalArea = cell(facts, "parcels.area", "Total");
        assertThat(totalArea.valueText()).isEqualTo("99 acre");
        assertThat(totalArea.ontologyPath()).isEqualTo("technical > Site > Parcel");

        FactRow jan = cell(facts, "monthly_generation.glob_hor", "soiling", "Jan");
        FactRow feb = cell(facts, "monthly_generation.glob_hor", "soiling", "Feb");
        FactRow totalMonth = cell(facts, "monthly_generation.glob_hor", "Total", "Jan");
        assertThat(jan.valueText()).isEqualTo("4.1");
        assertThat(feb.valueText()).isEqualTo("4.4");
        assertThat(totalMonth.valueText()).isEqualTo("9.9");
        assertThat(jan.ontologyPath()).isEqualTo("technical > Generation > Month");
        assertThat(facts.stream().filter(f -> "monthly_generation.glob_hor".equals(f.fieldKey())
                && "soiling".equals(f.lineKey())).count()).isEqualTo(2);
    }

    @Test
    void omittedColumnKindsPersistAsInferredValueKindsWithNormalizedSlots() throws Exception {
        DocTypePack inferred = new DocTypePack(
                "demo.inferred", "1.0.0", "legal", "land_document",
                List.of(), List.of(),
                List.of(PackField.optional("grantor", "identity > Legal Name",
                        "identity", ValueKind.TEXT)),
                List.of(Map.of(
                        "name", "parcels",
                        "path", "technical > Site > Parcel",
                        "path_root", "technical",
                        "key_column", "survey_no",
                        "fields", List.of(
                                Map.of("key", "survey_no", "value_kind", "text"),
                                Map.of("key", "area", "type", "number"),
                                Map.of("key", "consideration", "type", "number", "currency", true),
                                Map.of("key", "active", "type", "boolean")))),
                List.of(), List.of());
        FixtureExtractClient client = new FixtureExtractClient();
        client.putFixture("demo.inferred", MAPPER.readTree("""
                {
                  "doc_type_code": "demo.inferred",
                  "tables": {
                    "parcels": [
                      {"survey_no": "12/1",
                       "area": {"value": "4.5 acre", "quote": "12/1 area 4.5 acre", "page": 2},
                       "consideration": {"value": "Rs 10 lakh", "quote": "12/1 price 10 lakh", "page": 2},
                       "active": {"value": "true", "quote": "active", "page": 2}}
                    ]
                  }
                }
                """));

        ExtractService.ExtractOutcome outcome = new ExtractService().extract(
                dbPath, documentVersionId, MANDATE, inferred, client, "test");
        assertThat(outcome.status()).isEqualTo("success");

        List<FactRow> facts = facts();
        FactRow area = cell(facts, "parcels.area", "12/1");
        assertThat(area.valueKind()).isEqualTo("quantity");
        assertThat(area.valueNumber()).isEqualTo(4.5);
        assertThat(area.unit()).isEqualTo("acre");
        assertThat(area.scale()).isEqualTo("unit");

        FactRow price = cell(facts, "parcels.consideration", "12/1");
        assertThat(price.valueKind()).isEqualTo("money");
        assertThat(price.currency()).isEqualTo("INR");
        assertThat(price.scale()).isEqualTo("lakh");

        FactRow active = cell(facts, "parcels.active", "12/1");
        assertThat(active.valueKind()).isEqualTo("boolean");
        assertThat(active.valueText()).isEqualTo("true");
    }

    @Test
    void eachRowKeepsItsOwnEntityAndSite() throws Exception {
        FixtureExtractClient client = new FixtureExtractClient();
        client.putFixture("demo.schedule", MAPPER.readTree("""
                {
                  "doc_type_code": "demo.schedule",
                  "tables": {
                    "parcels": [
                      {"survey_no": "12/1", "owner": "Ram Sharma", "village": "Jaisalmer",
                       "area": {"value": "4.5 acre", "quote": "12/1 area 4.5 acre", "page": 2},
                       "consideration": {"value": "Rs 10 lakh", "quote": "12/1 price 10 lakh", "page": 2}},
                      {"survey_no": "13/2", "owner": "Sita Devi", "village": "Barmer",
                       "area": {"value": "2 guntha", "quote": "13/2 area 2 guntha", "page": 2},
                       "consideration": {"value": "Rs 3 lakh", "quote": "13/2 price 3 lakh", "page": 2}}
                    ]
                  }
                }
                """));

        new ExtractService().extract(dbPath, documentVersionId, MANDATE, pack, client, "test");
        List<FactRow> facts = facts();

        FactRow ramArea = cell(facts, "parcels.area", "12/1");
        FactRow ramPrice = cell(facts, "parcels.consideration", "12/1");
        FactRow sitaArea = cell(facts, "parcels.area", "13/2");
        assertThat(ramArea.subjectEntity()).isEqualTo("Ram Sharma");
        assertThat(ramPrice.subjectEntity()).isEqualTo("Ram Sharma");
        assertThat(sitaArea.subjectEntity()).isEqualTo("Sita Devi");
        assertThat(ramArea.siteName()).isEqualTo("Jaisalmer");
        assertThat(ramPrice.siteId()).isEqualTo(ramArea.siteId()).isNotNull();
        assertThat(sitaArea.siteName()).isEqualTo("Barmer");
        assertThat(sitaArea.siteId()).isNotEqualTo(ramArea.siteId());
    }

    private List<FactRow> facts() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            return new FactRepository(db.connection()).query(FactQuery.forMandate(MANDATE));
        }
    }

    private static FactRow cell(List<FactRow> facts, String fieldKey, String lineKey) {
        return facts.stream()
                .filter(f -> fieldKey.equals(f.fieldKey()) && lineKey.equals(f.lineKey()))
                .findFirst()
                .orElseThrow();
    }

    private static FactRow cell(List<FactRow> facts, String fieldKey, String lineKey, String period) {
        return facts.stream()
                .filter(f -> fieldKey.equals(f.fieldKey()) && lineKey.equals(f.lineKey())
                        && period.equals(periodOf(f)))
                .findFirst()
                .orElseThrow();
    }

    private static String periodOf(FactRow fact) {
        return fact.evidence().stream()
                .filter(e -> "period".equals(e.role()))
                .map(FactRow.EvidenceRow::verbatim)
                .findFirst()
                .orElse(null);
    }

    private static String quoteOf(FactRow fact) {
        return fact.evidence().stream()
                .filter(e -> "verbatim_quote".equals(e.role()))
                .map(FactRow.EvidenceRow::verbatim)
                .findFirst()
                .orElse(null);
    }
}
