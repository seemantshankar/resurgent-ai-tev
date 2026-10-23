package com.resurgent.tev.doc.bind;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.extract.ExtractService;
import com.resurgent.tev.doc.extract.FixtureExtractClient;
import com.resurgent.tev.doc.llm.FakeDocLlmClient;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Schedule rows bind once per distinct field and line under the table's parent
 * path. Two periods of one line share that path. A total is not rolled up onto
 * the parent or onto a detail line (ADR 0027).
 */
class ScheduleLineBindTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MANDATE = 1L;

    private static final String PACK_YAML = """
            code: demo.schedule
            version: 1.0.0
            family: technical
            file_role: technical_report
            fields:
              - key: project_name
                path: identity > Project > Name
                path_root: identity
                value_kind: text
                required: false
            tables:
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
    private DocTypePack pack;
    private long parseRunId;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("workspace.db");
        long versionId;
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            DocumentRepository docs = new DocumentRepository(db.connection());
            long sourceFileId = repo.insertSourceFile(MANDATE, "pv.pdf", "hash-pv", "pdf",
                    "2026-01-01T00:00:00Z", "0.1.0", null);
            long documentId = docs.insertDocument(MANDATE, "PVsyst", "technical_report", null);
            versionId = docs.insertDocumentVersion(documentId, "v1", "pv.pdf",
                    "hash-pv", ".pdf", "technical_report", 1, false, sourceFileId, null);
        }
        pack = PackLoader.load(
                new ByteArrayInputStream(PACK_YAML.getBytes(StandardCharsets.UTF_8)),
                "schedule-pack");
        FixtureExtractClient client = new FixtureExtractClient();
        client.putFixture("demo.schedule", MAPPER.readTree("""
                {
                  "doc_type_code": "demo.schedule",
                  "tables": {
                    "monthly_generation": [
                      {"loss": "soiling", "month": "Jan",
                       "glob_hor": {"value": "4.1", "quote": "soiling Jan 4.1", "page": 2}},
                      {"loss": "soiling", "month": "Feb",
                       "glob_hor": {"value": "4.4", "quote": "soiling Feb 4.4", "page": 2}},
                      {"loss": "Total", "month": "Jan",
                       "glob_hor": {"value": "9.9", "quote": "Total Jan 9.9", "page": 2}}
                    ]
                  }
                }
                """));
        parseRunId = new ExtractService()
                .extract(dbPath, versionId, MANDATE, pack, client, "test")
                .parseRunId();
    }

    @Test
    void eachLineBindsOnceUnderTheTableParentAcrossPeriods() throws Exception {
        FakeDocLlmClient llm = new FakeDocLlmClient("""
                {"assignments": [
                  {"key": "monthly_generation.glob_hor|soiling",
                   "path": "Generation > Month > Soiling Loss",
                   "reasoning": "soiling is its own loss line"},
                  {"key": "monthly_generation.glob_hor|total",
                   "path": "Generation > Month > Total Loss",
                   "reasoning": "the total stays distinct from the lines it adds"}]}
                """);
        new PackPathBinder().bind(dbPath, parseRunId, MANDATE, pack, llm);

        assertThat(llm.calls()).isEqualTo(1);
        assertThat(countOf(llm.lastUser(), "monthly_generation.glob_hor|soiling")).isEqualTo(1);
        assertThat(llm.lastUser()).contains("monthly_generation.glob_hor|total");

        List<FactRow> facts = facts();
        FactRow jan = cell(facts, "soiling", "Jan");
        FactRow feb = cell(facts, "soiling", "Feb");
        FactRow total = cell(facts, "Total", "Jan");
        assertThat(jan.ontologyPath()).isEqualTo("technical > Generation > Month > Soiling Loss");
        assertThat(feb.ontologyPath()).isEqualTo(jan.ontologyPath());
        assertThat(jan.nomenclatureStatus()).isEqualTo("bound");
        assertThat(total.ontologyPath()).isEqualTo("technical > Generation > Month > Total Loss");
        assertThat(total.ontologyPath()).isNotEqualTo("technical > Generation > Month");
        assertThat(jan.ontologyPath()).isNotEqualTo(total.ontologyPath());
    }

    @Test
    void aPathThatIsOnlyTheParentIsRefused() throws Exception {
        FakeDocLlmClient llm = new FakeDocLlmClient("""
                {"assignments": [
                  {"key": "monthly_generation.glob_hor|soiling",
                   "path": "Generation > Month",
                   "reasoning": "roll the line up"},
                  {"key": "monthly_generation.glob_hor|total",
                   "path": "Generation > Month",
                   "reasoning": "roll the total up"}]}
                """);
        new PackPathBinder().bind(dbPath, parseRunId, MANDATE, pack, llm);

        FactRow jan = cell(facts(), "soiling", "Jan");
        assertThat(jan.nomenclatureStatus()).isEqualTo("unbound");
        assertThat(jan.unboundReason()).isEqualTo("model_path_refused");
        assertThat(jan.ontologyPath()).isNotEqualTo("technical > Generation > Month > Soiling Loss");
    }

    private List<FactRow> facts() throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            return new FactRepository(db.connection()).query(FactQuery.forMandate(MANDATE));
        }
    }

    private static FactRow cell(List<FactRow> facts, String lineKey, String period) {
        return facts.stream()
                .filter(f -> "monthly_generation.glob_hor".equals(f.fieldKey())
                        && lineKey.equals(f.lineKey())
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

    private static int countOf(String text, String needle) {
        int count = 0;
        int from = 0;
        while (from >= 0) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
        return count;
    }
}
