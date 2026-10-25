package com.resurgent.tev.doc;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.db.ClauseRepository;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.db.PassageRepository;
import com.resurgent.tev.doc.db.ReportQuestionQueryRunner;
import com.resurgent.tev.doc.extract.Citation;
import com.resurgent.tev.doc.query.ReportAnswer;
import com.resurgent.tev.doc.query.ReportQuestion;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A report question is asked in ordinary words and answered from the stores
 * that already hold the information (issue #181). The caller does not pass a
 * field name or a pack code. Every case runs with the mandate locked to local
 * processing.
 */
class ReportQuestionTest {

    private static final long MANDATE_ID = 1L;
    private static final String PROJECT_COST = "Total Project Cost ₹259.00 Crores";

    @TempDir
    Path tempDir;

    private WorkspaceDatabase db;
    private Connection connection;
    private DocumentRepository docs;
    private FactRepository facts;
    private ReportQuestionQueryRunner questions;
    private long documentVersionId;
    private long parseRunId;

    @BeforeEach
    void setUp() throws Exception {
        db = WorkspaceDatabase.open(tempDir.resolve("workspace.db"));
        connection = db.connection();
        docs = new DocumentRepository(connection);
        facts = new FactRepository(connection);
        questions = new ReportQuestionQueryRunner(connection);
        WorkspaceRepository repo = new WorkspaceRepository(connection);
        long sourceFileId = repo.insertSourceFile(MANDATE_ID, "report.pdf", "hash-report", "pdf",
                "2026-01-01T00:00:00Z", "0.1.0", null);
        parseRunId = repo.insertParseRun(sourceFileId, MANDATE_ID, "0.1.0-doc-extract",
                "extract:test@1:1", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z",
                "success", null);
        long documentId = docs.insertDocument(MANDATE_ID, "Project report", "other", null);
        documentVersionId = docs.insertDocumentVersion(documentId, "v1", "report.pdf",
                "hash-report", ".pdf", "other", 1, false, sourceFileId, null);
        try (PreparedStatement policy = connection.prepareStatement(
                "INSERT INTO mandate_policy (mandate_id, data_residency) VALUES (?, 'local_only')")) {
            policy.setLong(1, MANDATE_ID);
            policy.executeUpdate();
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void projectCostQuestionReturnsTheStoredFigureAndItsPage() throws Exception {
        long pageId = docs.insertPage(documentVersionId, 4, 600, 800, "hash-report/pages/p004.png");
        docs.insertBlock(pageId, 0, "paragraph", 72.0, 600.0, 500.0, 640.0,
                PROJECT_COST, PROJECT_COST, 0, "test");
        facts.replaceFacts(parseRunId, documentVersionId, List.of(new FactRepository.FactWrite(
                "figure_a", "economic > Test > figure_a", "economic", null,
                "money", PROJECT_COST, 259.0, null, null, "INR", "crore", "2590000000",
                null, "llm_field", "test-extract", 0.9, "stated",
                null, null, List.of(Citation.of(4, PROJECT_COST)), List.of())));

        List<ReportAnswer> answers = questions.answer(
                new ReportQuestion(MANDATE_ID, "What is the project cost?"));

        assertThat(answers).anySatisfy(answer -> {
            assertThat(answer.store()).isEqualTo("fact");
            assertThat(answer.text()).isEqualTo(PROJECT_COST);
            assertThat(answer.filePage()).isEqualTo(4);
        });
    }

    @Test
    void projectCostQuestionIsAnsweredFromAPassage() throws Exception {
        long pageId = docs.insertPage(documentVersionId, 7, 600, 800, "hash-report/pages/p007.png");
        PassageRepository passages = new PassageRepository(connection);
        long passageId = passages.insertPassage(documentVersionId, 1, "Chapter 4", null, 7, 7, null);
        long provenanceId = docs.insertProvenance("passage", passageId, documentVersionId,
                pageId, null, null, null, null, null, null, null, null, null, PROJECT_COST, null);
        passages.linkProvenance(passageId, provenanceId);

        List<ReportAnswer> answers = questions.answer(
                new ReportQuestion(MANDATE_ID, "What is the project cost?"));

        assertThat(answers).anySatisfy(answer -> {
            assertThat(answer.store()).isEqualTo("passage");
            assertThat(answer.text()).isEqualTo(PROJECT_COST);
            assertThat(answer.filePage()).isEqualTo(7);
        });
    }

    @Test
    void projectCostQuestionIsAnsweredFromAClause() throws Exception {
        docs.insertPage(documentVersionId, 9, 600, 800, "hash-report/pages/p009.png");
        new ClauseRepository(connection).insertClause(new ClauseRepository.ClauseWrite(
                documentVersionId, null, null, "12. Payment", PROJECT_COST,
                9, 9, "open", "new", null, null, null));

        List<ReportAnswer> answers = questions.answer(
                new ReportQuestion(MANDATE_ID, "What is the project cost?"));

        assertThat(answers).anySatisfy(answer -> {
            assertThat(answer.store()).isEqualTo("clause");
            assertThat(answer.text()).isEqualTo(PROJECT_COST);
            assertThat(answer.filePage()).isEqualTo(9);
        });
    }

    @Test
    void projectCostQuestionIsAnsweredFromTheWordsOnThePage() throws Exception {
        long pageId = docs.insertPage(documentVersionId, 11, 600, 800, "hash-report/pages/p011.png");
        docs.insertBlock(pageId, 0, "paragraph", 72.0, 600.0, 500.0, 640.0,
                PROJECT_COST, PROJECT_COST, 0, "test");

        List<ReportAnswer> answers = questions.answer(
                new ReportQuestion(MANDATE_ID, "What is the project cost?"));

        assertThat(answers).anySatisfy(answer -> {
            assertThat(answer.store()).isEqualTo("layout_text");
            assertThat(answer.text()).isEqualTo(PROJECT_COST);
            assertThat(answer.filePage()).isEqualTo(11);
        });
    }

    @Test
    void powerPurchaseAgreementQuestionIsAnsweredFromTheDocumentType() throws Exception {
        long documentId = docs.insertDocument(MANDATE_ID, "Offtake", "agreement", "agreement.ppa");
        long versionId = docs.insertDocumentVersion(documentId, "v1", "offtake.pdf",
                "hash-offtake", ".pdf", "agreement", 1, false, null, null);
        long pageId = docs.insertPage(versionId, 2, 600, 800, "hash-offtake/pages/p002.png");
        docs.insertBlock(pageId, 0, "paragraph", 72.0, 600.0, 500.0, 640.0,
                "Seller Acme Energy Private Limited", "Seller Acme Energy Private Limited",
                0, "test");

        List<ReportAnswer> answers = questions.answer(
                new ReportQuestion(MANDATE_ID, "Do we have the power purchase agreement?"));

        assertThat(answers).anySatisfy(answer -> {
            assertThat(answer.store()).isEqualTo("document_type");
            assertThat(answer.text()).isEqualTo("Power Purchase Agreement");
            assertThat(answer.filePage()).isEqualTo(2);
        });
        assertThat(answers).noneMatch(answer -> answer.text().contains("agreement.ppa"));
    }

    @Test
    void aCurrentFactAndADifferentFigureBothStayVisible() throws Exception {
        String other = "Total Project Cost ₹310.50 Crores";
        String superseded = "Total Project Cost ₹100.00 Crores";
        long factPage = docs.insertPage(documentVersionId, 3, 600, 800, "hash-report/pages/p003.png");
        docs.insertBlock(factPage, 0, "paragraph", 72.0, 600.0, 500.0, 640.0,
                PROJECT_COST, PROJECT_COST, 0, "test");
        long passagePage = docs.insertPage(documentVersionId, 8, 600, 800, "hash-report/pages/p008.png");
        PassageRepository passages = new PassageRepository(connection);
        long passageId = passages.insertPassage(documentVersionId, 1, "Chapter 8", null, 8, 8, null);
        long provenanceId = docs.insertProvenance("passage", passageId, documentVersionId,
                passagePage, null, null, null, null, null, null, null, null, null, other, null);
        passages.linkProvenance(passageId, provenanceId);
        facts.replaceFacts(parseRunId, documentVersionId, List.of(
                statedMoney("figure_a", PROJECT_COST, 3),
                statedMoney("figure_old", superseded, 3)));
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE fact SET is_current = 0 WHERE value_text = ?")) {
            statement.setString(1, superseded);
            statement.executeUpdate();
        }

        List<ReportAnswer> answers = questions.answer(
                new ReportQuestion(MANDATE_ID, "What is the project cost?"));

        assertThat(answers).anySatisfy(answer -> {
            assertThat(answer.store()).isEqualTo("fact");
            assertThat(answer.text()).isEqualTo(PROJECT_COST);
            assertThat(answer.filePage()).isEqualTo(3);
            assertThat(answer.disagrees()).isTrue();
        });
        assertThat(answers).anySatisfy(answer -> {
            assertThat(answer.store()).isEqualTo("passage");
            assertThat(answer.text()).isEqualTo(other);
            assertThat(answer.filePage()).isEqualTo(8);
            assertThat(answer.disagrees()).isTrue();
        });
        assertThat(answers).noneMatch(answer -> answer.text().contains("100.00"));
    }

    @Test
    void anUnnamedNumberComesBackAsThatNumberAndItsPage() throws Exception {
        long before = docs.insertPage(documentVersionId, 1, 600, 800, "hash-report/pages/p001.png");
        docs.insertBlock(before, 0, "paragraph", 72.0, 700.0, 500.0, 740.0,
                "The weather was fine.", "The weather was fine.", 0, "test");
        long numberPage = docs.insertPage(documentVersionId, 2, 600, 800, "hash-report/pages/p002.png");
        docs.insertBlock(numberPage, 0, "paragraph", 72.0, 400.0, 200.0, 440.0,
                "873.40", "873.40", 0, "test");
        long after = docs.insertPage(documentVersionId, 3, 600, 800, "hash-report/pages/p003.png");
        docs.insertBlock(after, 0, "paragraph", 72.0, 100.0, 400.0, 140.0,
                "End of file", "End of file", 0, "test");

        List<ReportAnswer> answers = questions.answer(new ReportQuestion(MANDATE_ID, "What is 873.40?"));

        assertThat(answers).singleElement().satisfies(answer -> {
            assertThat(answer.store()).isEqualTo("layout_text");
            assertThat(answer.text()).isEqualTo("873.40");
            assertThat(answer.filePage()).isEqualTo(2);
        });
        assertThat(questions.answer(new ReportQuestion(MANDATE_ID, "What is the sanctioned limit?")))
                .isEmpty();
    }

    @Test
    void wordsOnAPageThatBelongsToNoDocumentStillAnswer() throws Exception {
        long pageId = docs.insertPage(documentVersionId, 5, 600, 800, "hash-report/pages/p005.png");
        String index = "List of annexures and land documents";
        docs.insertBlock(pageId, 0, "paragraph", 72.0, 700.0, 500.0, 740.0,
                index, index, 0, "test");

        List<ReportAnswer> answers = questions.answer(
                new ReportQuestion(MANDATE_ID, "Where are the land documents?"));

        assertThat(answers).singleElement().satisfies(answer -> {
            assertThat(answer.store()).isEqualTo("layout_text");
            assertThat(answer.text()).isEqualTo(index);
            assertThat(answer.filePage()).isEqualTo(5);
        });
    }

    @Test
    void localOnlyResidencyStillAnswersTheQuestion() throws Exception {
        long pageId = docs.insertPage(documentVersionId, 4, 600, 800, "hash-report/pages/p004.png");
        docs.insertBlock(pageId, 0, "paragraph", 72.0, 600.0, 500.0, 640.0,
                PROJECT_COST, PROJECT_COST, 0, "test");
        facts.replaceFacts(parseRunId, documentVersionId, List.of(
                statedMoney("figure_a", PROJECT_COST, 4)));

        List<ReportAnswer> answers = questions.answer(
                new ReportQuestion(MANDATE_ID, "What is the project cost?"));

        assertThat(answers).anySatisfy(answer -> {
            assertThat(answer.store()).isEqualTo("fact");
            assertThat(answer.text()).isEqualTo(PROJECT_COST);
            assertThat(answer.filePage()).isEqualTo(4);
        });
    }

    private static FactRepository.FactWrite statedMoney(String key, String text, int page) {
        return new FactRepository.FactWrite(
                key, "economic > Test > " + key, "economic", null,
                "money", text, null, null, null, "INR", "crore", null,
                null, "llm_field", "test-extract", 0.9, "stated",
                null, null, List.of(Citation.of(page, text)), List.of());
    }
}
