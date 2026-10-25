package com.resurgent.tev.doc.db;

import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.doc.query.ReportAnswer;
import com.resurgent.tev.doc.query.ReportQuestion;
import com.resurgent.tev.doc.residency.DataResidency;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers a {@link ReportQuestion} from the stores already written for a mandate.
 * Ordinary words do not have to repeat the stored wording or name a field. The
 * read uses those stores only. {@code local_only} and {@code cloud_allowed} take the
 * same path; this read has no cloud client.
 *
 * <p>The connection is owned by the caller. This class never commits or rolls back.
 */
public final class ReportQuestionQueryRunner {

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+(?:\\.[\\p{N}]+)?");
    private static final Pattern AMOUNT = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "do", "does", "did",
            "we", "you", "our", "have", "has", "had", "what", "which", "who",
            "where", "when", "why", "how", "of", "for", "to", "in", "on", "at",
            "by", "with", "from", "and", "or", "not", "this", "that", "it", "its",
            "be", "been", "there", "any", "about");

    private final Connection connection;
    private Map<String, DocTypePack> packsByCode;

    public ReportQuestionQueryRunner(Connection connection) {
        this.connection = connection;
    }

    /**
     * Answers one question from a fact, a document's type, a passage, a clause, or the
     * words on the page.
     *
     * @param question ordinary words, scoped to one mandate
     * @return one answer per matching store, each naming that store and its file page
     * @throws SQLException if a read fails
     */
    public List<ReportAnswer> answer(ReportQuestion question) throws SQLException {
        String residency = DataResidency.effective(
                System.getenv(DataResidency.ENV),
                new MandatePolicyRepository(connection).dataResidency(question.mandateId()));
        if (!DataResidency.LOCAL_ONLY.equals(residency)
                && !DataResidency.CLOUD_ALLOWED.equals(residency)) {
            throw new IllegalArgumentException("unsupported data residency: " + residency);
        }
        List<String> wanted = contentTokens(question.words());
        if (wanted.isEmpty()) {
            return List.of();
        }
        List<ReportAnswer> answers = new ArrayList<>();
        addFacts(question.mandateId(), wanted, answers);
        addDocumentTypes(question.mandateId(), wanted, answers);
        addPassages(question.mandateId(), wanted, answers);
        addClauses(question.mandateId(), wanted, answers);
        addLayout(question.mandateId(), wanted, answers);
        return List.copyOf(markDisagreements(answers));
    }

    /**
     * A current fact is the record of its amount. A different amount in another
     * store is kept beside it; neither is dropped and neither is settled here.
     */
    private static List<ReportAnswer> markDisagreements(List<ReportAnswer> answers) {
        List<Set<String>> perAnswer = new ArrayList<>();
        Set<String> distinct = new HashSet<>();
        for (ReportAnswer answer : answers) {
            Set<String> found = amountsIn(answer.text());
            perAnswer.add(found);
            distinct.addAll(found);
        }
        if (distinct.size() < 2) {
            return answers;
        }
        List<ReportAnswer> marked = new ArrayList<>();
        for (int i = 0; i < answers.size(); i++) {
            ReportAnswer answer = answers.get(i);
            boolean disagrees = false;
            if (!perAnswer.get(i).isEmpty()) {
                for (int j = 0; j < perAnswer.size(); j++) {
                    if (j != i && !perAnswer.get(j).isEmpty()
                            && !perAnswer.get(j).equals(perAnswer.get(i))) {
                        disagrees = true;
                        break;
                    }
                }
            }
            marked.add(new ReportAnswer(answer.store(), answer.text(), answer.filePage(),
                    disagrees, answer.filePages()));
        }
        return marked;
    }

    private static Set<String> amountsIn(String text) {
        Set<String> found = new HashSet<>();
        if (text == null) {
            return found;
        }
        Matcher matcher = AMOUNT.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group().replace(",", "");
            found.add(new BigDecimal(raw).stripTrailingZeros().toPlainString());
        }
        return found;
    }

    private void addFacts(long mandateId, List<String> wanted, List<ReportAnswer> answers)
            throws SQLException {
        for (FactRow fact : new FactRepository(connection).query(FactQuery.forMandate(mandateId))) {
            List<Integer> pages = new ArrayList<>();
            String covering = null;
            Integer coveringPage = null;
            for (FactRow.EvidenceRow evidence : fact.evidence()) {
                if (evidence.pageIndex() != null && !pages.contains(evidence.pageIndex())) {
                    pages.add(evidence.pageIndex());
                }
                if (covering == null && evidence.verbatim() != null
                        && covers(wanted, evidence.verbatim()) && evidence.pageIndex() != null) {
                    covering = evidence.verbatim();
                    coveringPage = evidence.pageIndex();
                }
            }
            boolean valueCovers = fact.valueText() != null && covers(wanted, fact.valueText());
            if (covering == null && !valueCovers) {
                continue;
            }
            if (pages.size() > 1 && fact.valueText() != null && !fact.valueText().isBlank()) {
                answers.add(new ReportAnswer(ReportAnswer.FACT, fact.valueText(), pages.get(0),
                        false, pages));
            } else if (covering != null && coveringPage != null) {
                answers.add(new ReportAnswer(ReportAnswer.FACT, covering, coveringPage));
            } else if (valueCovers && !pages.isEmpty()) {
                answers.add(new ReportAnswer(ReportAnswer.FACT, fact.valueText(), pages.get(0)));
            }
        }
    }

    private void addDocumentTypes(long mandateId, List<String> wanted, List<ReportAnswer> answers)
            throws SQLException {
        String sql = "SELECT d.doc_type_code, COALESCE("
                + " (SELECT MIN(p.page_index) FROM document_page dp"
                + "   JOIN page p ON p.page_id = dp.page_id"
                + "   WHERE dp.document_id = d.document_id),"
                + " (SELECT MIN(p.page_index) FROM page p"
                + "   JOIN document_version dv"
                + "     ON dv.document_version_id = p.document_version_id"
                + "   WHERE dv.document_id = d.document_id)"
                + " ) AS page_index"
                + " FROM document d"
                + " WHERE d.mandate_id = ? AND d.status != 'deleted_by_analyst'"
                + " AND d.doc_type_code IS NOT NULL";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DocTypePack pack = pack(rs.getString("doc_type_code"));
                    Integer page = asInteger(rs.getObject("page_index"));
                    if (pack == null || page == null) {
                        continue;
                    }
                    String label = matchingTypeLabel(wanted, pack);
                    if (label != null) {
                        answers.add(new ReportAnswer(ReportAnswer.DOCUMENT_TYPE, label, page));
                    }
                }
            }
        }
    }

    private String matchingTypeLabel(List<String> wanted, DocTypePack pack) {
        String best = null;
        for (String signal : pack.identitySignals()) {
            if (signal != null && covers(wanted, signal)
                    && (best == null || signal.length() > best.length())) {
                best = signal;
            }
        }
        return best;
    }

    private DocTypePack pack(String code) {
        if (packsByCode == null) {
            packsByCode = new HashMap<>();
            try {
                for (DocTypePack pack : PackLoader.loadSeedPacks()) {
                    packsByCode.put(pack.code(), pack);
                }
            } catch (PackLoader.PackValidationException e) {
                throw new IllegalStateException("seed document types could not be read", e);
            }
        }
        return code == null ? null : packsByCode.get(code);
    }

    private void addPassages(long mandateId, List<String> wanted, List<ReportAnswer> answers)
            throws SQLException {
        String sql = "SELECT p.heading, p.summary, p.page_from, pd.verbatim, pg.page_index"
                + " FROM passage p"
                + " JOIN document_version dv ON dv.document_version_id = p.document_version_id"
                + " JOIN document d ON d.document_id = dv.document_id"
                + " LEFT JOIN provenance_doc pd ON pd.provenance_id = p.provenance_id"
                + " LEFT JOIN page pg ON pg.page_id = pd.page_id"
                + " WHERE d.mandate_id = ? AND d.status != 'deleted_by_analyst'";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    addCascade(ReportAnswer.PASSAGE, rs.getString("verbatim"),
                            rs.getString("summary"), rs.getString("heading"),
                            rs.getInt("page_from"), asInteger(rs.getObject("page_index")),
                            wanted, answers);
                }
            }
        }
    }

    private void addClauses(long mandateId, List<String> wanted, List<ReportAnswer> answers)
            throws SQLException {
        String sql = "SELECT c.heading, c.verbatim_text, c.summary, c.page_start"
                + " FROM clause c"
                + " JOIN document_version dv ON dv.document_version_id = c.document_version_id"
                + " JOIN document d ON d.document_id = dv.document_id"
                + " WHERE d.mandate_id = ? AND d.status != 'deleted_by_analyst'";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Integer page = asInteger(rs.getObject("page_start"));
                    if (page == null) {
                        continue;
                    }
                    addCascade(ReportAnswer.CLAUSE, rs.getString("verbatim_text"),
                            rs.getString("summary"), rs.getString("heading"),
                            page, page, wanted, answers);
                }
            }
        }
    }

    private void addLayout(long mandateId, List<String> wanted, List<ReportAnswer> answers)
            throws SQLException {
        String sql = "SELECT b.text_raw, b.text_norm, pg.page_index"
                + " FROM block b"
                + " JOIN page pg ON pg.page_id = b.page_id"
                + " JOIN document_version dv ON dv.document_version_id = pg.document_version_id"
                + " JOIN document d ON d.document_id = dv.document_id"
                + " WHERE d.mandate_id = ? AND d.status != 'deleted_by_analyst'";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String raw = rs.getString("text_raw");
                    String norm = rs.getString("text_norm");
                    int page = rs.getInt("page_index");
                    if (raw != null && covers(wanted, raw)) {
                        answers.add(new ReportAnswer(ReportAnswer.LAYOUT_TEXT, raw, page));
                    } else if (norm != null && covers(wanted, norm)) {
                        answers.add(new ReportAnswer(ReportAnswer.LAYOUT_TEXT, norm, page));
                    }
                }
            }
        }
    }

    /**
     * Verbatim wins, then the summary, then the heading. The verbatim cites its
     * own page when that page is known.
     */
    private static void addCascade(String store, String verbatim, String summary, String heading,
            int page, Integer verbatimPage, List<String> wanted, List<ReportAnswer> answers) {
        if (verbatim != null && covers(wanted, verbatim)) {
            answers.add(new ReportAnswer(store, verbatim,
                    verbatimPage != null ? verbatimPage : page));
        } else if (summary != null && covers(wanted, summary)) {
            answers.add(new ReportAnswer(store, summary, page));
        } else if (heading != null && covers(wanted, heading)) {
            answers.add(new ReportAnswer(store, heading, page));
        }
    }

    private static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(value.toString());
    }

    private static boolean covers(List<String> wanted, String stored) {
        Set<String> have = tokens(stored);
        for (String token : wanted) {
            if (!have.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> contentTokens(String words) {
        List<String> tokens = new ArrayList<>();
        for (String token : tokens(words)) {
            if (!STOPWORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        Matcher matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            out.add(matcher.group());
        }
        return out;
    }
}
