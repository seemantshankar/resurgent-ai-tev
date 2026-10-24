package com.resurgent.tev.doc.embed;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.db.ClauseRepository;
import com.resurgent.tev.doc.db.ClauseRepository.ClauseWrite;
import com.resurgent.tev.doc.db.InductionDecisionRepository;
import com.resurgent.tev.doc.db.InductionDecisionRepository.Decision;
import com.resurgent.tev.doc.db.PassageRepository;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V73 stores little-endian float32 embeddings and induction decisions. Upserts
 * replace the vector for the same clause or passage.
 */
class EmbeddingPersistenceTest {

    private Path tempDir;
    private WorkspaceDatabase db;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("embedding-persistence");
        db = WorkspaceDatabase.open(tempDir.resolve("workspace.db"));
        seed(db.connection());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void littleEndianFloat32RoundTripsKnownValues() {
        byte[] bytes = EmbeddingVectors.toLittleEndian(new float[] {1.0f, -0.5f});
        assertThat(bytes).containsExactly(
                0x00, 0x00, (byte) 0x80, 0x3f,
                0x00, 0x00, 0x00, (byte) 0xbf);
        assertThat(EmbeddingVectors.fromLittleEndian(bytes)).containsExactly(1.0f, -0.5f);
    }

    @Test
    void clauseAndPassageEmbeddingsUpsertInPlace() throws Exception {
        Connection connection = db.connection();
        long clauseId = new ClauseRepository(connection).insertClause(new ClauseWrite(
                1L, null, null, "MINIMUM DSCR", "maintain the debt service coverage ratio",
                1, 1, "open", "new", null, null, null));
        long passageId = new PassageRepository(connection).insertPassage(
                1L, 1, "Rating rationale", "rating_rationale", 2, 3, "Weak liquidity.");
        EmbeddingRepository embeddings = new EmbeddingRepository(connection);

        embeddings.upsertClause(clauseId, "local-ngram-hash-v1", new float[] {1f, 0f});
        embeddings.upsertClause(clauseId, "local-ngram-hash-v1", new float[] {0f, 1f});
        embeddings.upsertPassage(passageId, "local-ngram-hash-v1", new float[] {0f, 1f, 0f});
        embeddings.upsertPassage(passageId, "replaced-model", new float[] {0.6f, 0.8f});

        assertThat(embeddings.clauseCount()).isEqualTo(1);
        assertThat(embeddings.passageCount()).isEqualTo(1);
        EmbeddingRepository.Stored clause = embeddings.findClause(clauseId).orElseThrow();
        assertThat(clause.model()).isEqualTo("local-ngram-hash-v1");
        assertThat(clause.dims()).isEqualTo(2);
        assertThat(clause.vector()).containsExactly(0f, 1f);
        EmbeddingRepository.Stored passage = embeddings.findPassage(passageId).orElseThrow();
        assertThat(passage.model()).isEqualTo("replaced-model");
        assertThat(passage.dims()).isEqualTo(2);
        assertThat(passage.vector()).containsExactly(0.6f, 0.8f);

        db.close();
        db = WorkspaceDatabase.open(tempDir.resolve("workspace.db"));
        EmbeddingRepository reopened = new EmbeddingRepository(db.connection());
        assertThat(reopened.findClause(clauseId).orElseThrow().vector()).containsExactly(0f, 1f);
        assertThat(reopened.findPassage(passageId).orElseThrow().model()).isEqualTo("replaced-model");
    }

    @Test
    void deletingAClauseRemovesItsEmbedding() throws Exception {
        Connection connection = db.connection();
        long clauseId = new ClauseRepository(connection).insertClause(new ClauseWrite(
                1L, null, null, "HEADING", "verbatim", 1, 1, "open", "new", null, null, null));
        EmbeddingRepository embeddings = new EmbeddingRepository(connection);
        embeddings.upsertClause(clauseId, "local-ngram-hash-v1", new float[] {1f, 0f});

        new ClauseRepository(connection).deleteClausesForVersion(1L);

        assertThat(embeddings.clauseCount()).isZero();
        assertThat(embeddings.findClause(clauseId)).isEmpty();
    }

    @Test
    void inductionDecisionRoundTripsForALaterPackOrChecklistRow() throws Exception {
        InductionDecisionRepository decisions = new InductionDecisionRepository(db.connection());
        String detail = "{\"rationale\":\"domain standard\",\"sanitized_query\":\"credit rating report fields\","
                + "\"documents\":[\"dv:1\"]}";
        long id = decisions.insert(new Decision(
                null, 10L, 1L, "induce_pack", "report.credit_rating", "rating_outlook",
                "promoted", "web_domain_standard", detail));

        List<Decision> rows = decisions.forMandate(10L, "induce_pack");
        assertThat(rows).hasSize(1);
        Decision row = rows.get(0);
        assertThat(row.decisionId()).isEqualTo(id);
        assertThat(row.mandateId()).isEqualTo(10L);
        assertThat(row.parseRunId()).isEqualTo(1L);
        assertThat(row.stage()).isEqualTo("induce_pack");
        assertThat(row.targetCode()).isEqualTo("report.credit_rating");
        assertThat(row.candidateKey()).isEqualTo("rating_outlook");
        assertThat(row.outcome()).isEqualTo("promoted");
        assertThat(row.reasonCode()).isEqualTo("web_domain_standard");
        assertThat(row.detailJson()).contains("sanitized_query").contains("credit rating report fields");

        decisions.deleteForRun(1L, "induce_pack");
        assertThat(decisions.forMandate(10L, "induce_pack")).isEmpty();
    }

    private static void seed(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO source_file (source_file_id, mandate_id, file_name,"
                    + " file_hash, file_type, ingested_at, parser_version)"
                    + " VALUES (1, 10, 'loan.pdf', 'hash1', 'pdf', '2026-10-23T00:00:00Z', 'v1')");
            statement.execute("INSERT INTO parse_run (parse_run_id, source_file_id, mandate_id,"
                    + " parser_version, config_hash, started_at, status)"
                    + " VALUES (1, 1, 10, 'v1', 'cfg', '2026-10-23T00:00:00Z', 'success')");
            statement.execute("INSERT INTO document (document_id, mandate_id, logical_name,"
                    + " doc_family, status, created_at, updated_at)"
                    + " VALUES (1, 10, 'Facility Agreement', 'agreement', 'active',"
                    + " '2026-10-23T00:00:00Z', '2026-10-23T00:00:00Z')");
            statement.execute("INSERT INTO document_version (document_version_id, document_id,"
                    + " version_label, file_name, file_hash, file_ext, file_role, ingested_at)"
                    + " VALUES (1, 1, 'v1', 'loan.pdf', 'hash1', 'pdf', 'agreement',"
                    + " '2026-10-23T00:00:00Z')");
        }
    }
}
