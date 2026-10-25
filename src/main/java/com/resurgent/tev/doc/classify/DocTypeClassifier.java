package com.resurgent.tev.doc.classify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.db.CloudParsePageRepository;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.doc.pack.PackPassage;
import com.resurgent.tev.parser.db.Timestamps;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Document-side classification between layout and extract (S2.12, ADR 0031).
 *
 * <p>The type is read from the contents the pages state: pack field synonyms, passage
 * terms, and table names. A heading, a title, or a filename that only names a type
 * does not assign one. A phrase that more than one type states does not assign one
 * either. One stated content is not enough unless the pages also name that type.
 * Two candidate types that state the same amount of contents go to {@link ClassifyTieBreak};
 * the offline default declines and the document stays {@code unknown}. A pack is never
 * invented here.
 */
public final class DocTypeClassifier {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ClassifyTieBreak tieBreak;

    /** Offline classifier: deterministic scoring only; ties stay {@code unknown}. */
    public DocTypeClassifier() {
        this(ClassifyTieBreak.offline());
    }

    /**
     * @param tieBreak invoked on tie / low score; never required to succeed
     */
    public DocTypeClassifier(ClassifyTieBreak tieBreak) {
        this.tieBreak = tieBreak == null ? ClassifyTieBreak.offline() : tieBreak;
    }

    /**
     * Classifies the request without persistence (score-only requests).
     *
     * @param request classification inputs (required)
     * @return classification result (never null)
     * @throws SQLException when an internal persistence helper fails (not thrown for score-only)
     */
    public ClassifyResult classify(ClassifyRequest request) throws SQLException {
        return classify(request, null);
    }

    /**
     * Classifies and, when {@code connection} is non-null and the request carries a
     * document version, writes {@code document.doc_type_code} and
     * {@code doc_classification}. {@code force=false} keeps an existing row.
     *
     * @param request classification inputs (required)
     * @param connection workspace connection, or null for score-only
     * @return classification result (never null)
     * @throws SQLException when persistence fails
     */
    public ClassifyResult classify(ClassifyRequest request, Connection connection)
            throws SQLException {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        boolean persist = connection != null && request.documentVersionId() > 0;
        if (persist && !request.force()) {
            Optional<ClassifyResult> existing = readExisting(connection, request.documentVersionId());
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        ClassifyResult result = score(request);

        if (persist) {
            write(connection, request, result);
        }
        return result;
    }

    /**
     * Gathers whitespace-normalized page-1 block text in reading order, headings
     * first, for use as {@link ClassifyRequest#page1Text()}.
     *
     * @param connection workspace connection (required)
     * @param documentVersionId version whose page 1 is read
     * @return combined page-1 text, or empty string when there is no layout
     * @throws SQLException when the lookup fails
     */
    public static String loadPage1Text(Connection connection, long documentVersionId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(NULLIF(b.text_norm, ''), b.text_raw) AS page_text"
                        + " FROM block b"
                        + " JOIN page p ON p.page_id = b.page_id"
                        + " WHERE p.document_version_id = ? AND p.page_index = 1"
                        + " ORDER BY CASE WHEN b.block_kind = 'heading' THEN 0 ELSE 1 END,"
                        + " COALESCE(b.reading_order, b.block_index)")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                StringBuilder sb = new StringBuilder();
                while (rs.next()) {
                    String text = rs.getString("page_text");
                    if (text == null || text.isBlank()) {
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(text.trim());
                }
                if (!sb.isEmpty()) {
                    return sb.toString();
                }
            }
        }
        return new CloudParsePageRepository(connection)
                .loadPageText(documentVersionId, 1).trim();
    }

    /**
     * Layout text of every page of the version, in page order. Content typing reads
     * this, not the first page alone. Falls back to persisted cloud text when the
     * version has no blocks.
     *
     * @param connection workspace connection (required)
     * @param documentVersionId version whose pages are read
     * @return combined page text, or empty when the file has no layout
     * @throws SQLException when the lookup fails
     */
    public static String loadContents(Connection connection, long documentVersionId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(NULLIF(b.text_norm, ''), b.text_raw) AS page_text"
                        + " FROM block b"
                        + " JOIN page p ON p.page_id = b.page_id"
                        + " WHERE p.document_version_id = ?"
                        + " ORDER BY p.page_index, COALESCE(b.reading_order, b.block_index),"
                        + " b.block_index")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                StringBuilder sb = new StringBuilder();
                while (rs.next()) {
                    String text = rs.getString("page_text");
                    if (text == null || text.isBlank()) {
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(text.trim());
                }
                if (!sb.isEmpty()) {
                    return sb.toString();
                }
            }
        }
        List<String> pages = new CloudParsePageRepository(connection)
                .loadTextPages(documentVersionId);
        if (pages.isEmpty()) {
            return "";
        }
        return String.join("\n", pages).trim();
    }

    /**
     * Writes a classification that was already decided (an induced type kept as
     * itself) and records the version's pages as that document.
     *
     * @param connection workspace connection (required)
     * @param request version to record against (required)
     * @param result decision to store (required)
     * @throws SQLException when persistence fails
     */
    public void record(Connection connection, ClassifyRequest request, ClassifyResult result)
            throws SQLException {
        if (connection == null || request == null || result == null) {
            throw new IllegalArgumentException("connection, request, and result are required");
        }
        write(connection, request, result);
    }

    private ClassifyResult score(ClassifyRequest request) {
        List<String> reasons = new ArrayList<>();
        String fileRole = request.fileRole() == null ? "" : request.fileRole().trim();
        reasons.add("file_role=" + (fileRole.isEmpty() ? "(none)" : fileRole)
                + (request.industryTag() != null && !request.industryTag().isBlank()
                        ? ", industry=" + request.industryTag() : ""));

        if (request.candidatePacks().isEmpty()) {
            reasons.add("no candidate packs");
            return ClassifyResult.unknown(reasons, "deterministic");
        }

        List<String> pageWords = words(fold(request.page1Text()));
        List<ContentPhrase> phrases = new ArrayList<>();
        List<PackReading> readings = new ArrayList<>();
        for (DocTypePack pack : request.candidatePacks()) {
            phrases.addAll(contentPhrases(pack));
            readings.add(new PackReading(pack));
        }

        Set<String> sharedNoted = new HashSet<>();
        for (int i = 0; i < pageWords.size(); ) {
            PhraseHit hit = longest(phrases, pageWords, i);
            if (hit == null) {
                i++;
                continue;
            }
            if (hit.packCodes().size() == 1) {
                String code = hit.packCodes().iterator().next();
                for (PackReading reading : readings) {
                    if (reading.pack.code().equals(code)) {
                        reading.matched.add(hit.display());
                    }
                }
            } else if (sharedNoted.add(hit.folded())) {
                reasons.add("\"" + hit.folded() + "\" is stated by more than one document type"
                        + " and does not assign one");
            }
            i += hit.length();
        }

        for (PackReading reading : readings) {
            List<String> named = matchedIdentity(reading.pack, pageWords);
            reading.named.addAll(named);
            for (String signal : named) {
                reasons.add(reading.pack.code() + " named on the page: " + signal);
            }
            if (!reading.matched.isEmpty()) {
                reasons.add(reading.pack.code() + " contents: "
                        + String.join("; ", reading.matched));
            }
        }

        List<PackReading> eligible = new ArrayList<>();
        for (PackReading reading : readings) {
            if (reading.eligible()) {
                eligible.add(reading);
            }
        }
        if (eligible.isEmpty()) {
            reasons.add("no document type is stated by the page contents");
            return ClassifyResult.unknown(reasons, "deterministic");
        }
        eligible.sort(Comparator.comparingInt((PackReading reading) -> reading.matched.size())
                .reversed()
                .thenComparing(reading -> reading.pack.code()));
        PackReading top = eligible.get(0);
        List<PackReading> tied = new ArrayList<>();
        for (PackReading reading : eligible) {
            if (reading.matched.size() == top.matched.size()) {
                tied.add(reading);
            }
        }
        if (tied.size() == 1) {
            reasons.add("contents state " + top.pack.code());
            return classified(top, reasons, "deterministic", confidence(top.matched.size()));
        }

        reasons.add("equal contents — requesting tie-break");
        ClassifyTieBreak.TieBreakContext context = new ClassifyTieBreak.TieBreakContext(
                fileRole,
                request.fileName(),
                request.page1Text(),
                request.industryTag(),
                tied.stream()
                        .map(reading -> new ClassifyTieBreak.ScoredPack(
                                reading.pack.code(), reading.pack.version(),
                                reading.matched.size(), List.copyOf(reading.matched),
                                reading.pack.identitySignals()))
                        .toList());
        Optional<ClassifyTieBreak.TieBreakChoice> choice = tieBreak.resolve(context);
        if (choice.isEmpty()) {
            reasons.add("tie-break declined — staying unknown (never invent a pack)");
            return ClassifyResult.unknown(reasons, "tie-break");
        }
        ClassifyTieBreak.TieBreakChoice won = choice.get();
        PackReading chosen = null;
        for (PackReading reading : tied) {
            if (reading.pack.code().equals(won.packCode())) {
                chosen = reading;
                break;
            }
        }
        if (chosen == null) {
            reasons.add("tie-break chose '" + won.packCode()
                    + "' which these contents do not state — staying unknown");
            return ClassifyResult.unknown(reasons, "tie-break");
        }
        reasons.add("tie-break chose " + chosen.pack.code() + ": " + won.reason());
        return classified(chosen, reasons, "tie-break", won.confidence());
    }

    private static ClassifyResult classified(PackReading reading, List<String> reasons,
            String classifier, double confidence) {
        return new ClassifyResult(reading.pack.code(), reading.pack.code(), reading.pack.version(),
                confidence, reasons, ClassifyResult.Status.CLASSIFIED, classifier);
    }

    /** Two contents, or one content the pages also name, is enough to be that document. */
    private static boolean eligible(int contents, boolean named) {
        return contents >= 2 || (contents >= 1 && named);
    }

    private static double confidence(int contents) {
        if (contents <= 0) {
            return 0.0;
        }
        return Math.min(1.0, contents / 2.0);
    }

    private static List<ContentPhrase> contentPhrases(DocTypePack pack) {
        List<ContentPhrase> phrases = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PackField field : pack.fields()) {
            if (field.synonyms().isEmpty()) {
                addPhrase(phrases, seen, pack.code(), field.key().replace('_', ' '));
            } else {
                for (String synonym : field.synonyms()) {
                    addPhrase(phrases, seen, pack.code(), synonym);
                }
            }
        }
        for (PackPassage passage : pack.passages()) {
            for (String term : passage.headingTerms()) {
                addPhrase(phrases, seen, pack.code(), term);
            }
        }
        for (java.util.Map<String, Object> table : pack.tables()) {
            Object name = table.get("name");
            if (name != null) {
                addPhrase(phrases, seen, pack.code(), String.valueOf(name).replace('_', ' '));
            }
        }
        return phrases;
    }

    private static void addPhrase(List<ContentPhrase> phrases, Set<String> seen,
            String packCode, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        List<String> tokens = words(fold(raw));
        if (tokens.isEmpty() || (tokens.size() == 1 && tokens.get(0).length() < 4)) {
            return;
        }
        String folded = String.join(" ", tokens);
        if (!seen.add(folded)) {
            return;
        }
        phrases.add(new ContentPhrase(packCode, tokens, raw.trim(), folded));
    }

    private static List<String> matchedIdentity(DocTypePack pack, List<String> pageWords) {
        List<String> matched = new ArrayList<>();
        for (String signal : pack.identitySignals()) {
            List<String> tokens = words(fold(signal));
            if (tokens.isEmpty() || (tokens.size() == 1 && tokens.get(0).length() < 3)) {
                continue;
            }
            if (containsAt(pageWords, 0, tokens) || indexOf(pageWords, tokens) >= 0) {
                matched.add(signal);
            }
        }
        return matched;
    }

    private static PhraseHit longest(List<ContentPhrase> phrases, List<String> pageWords, int at) {
        int best = 0;
        List<ContentPhrase> found = new ArrayList<>();
        for (ContentPhrase phrase : phrases) {
            if (phrase.tokens().size() < best || phrase.tokens().size() > pageWords.size() - at) {
                continue;
            }
            if (!containsAt(pageWords, at, phrase.tokens())) {
                continue;
            }
            if (phrase.tokens().size() > best) {
                best = phrase.tokens().size();
                found.clear();
            }
            found.add(phrase);
        }
        if (found.isEmpty()) {
            return null;
        }
        Set<String> codes = new LinkedHashSet<>();
        for (ContentPhrase phrase : found) {
            codes.add(phrase.packCode());
        }
        ContentPhrase first = found.get(0);
        return new PhraseHit(codes, first.display(), first.folded(), first.tokens().size());
    }

    private static boolean containsAt(List<String> page, int at, List<String> needle) {
        if (at < 0 || needle.size() > page.size() - at) {
            return false;
        }
        for (int j = 0; j < needle.size(); j++) {
            if (!page.get(at + j).equals(needle.get(j))) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(List<String> page, List<String> needle) {
        for (int i = 0; i <= page.size() - needle.size(); i++) {
            if (containsAt(page, i, needle)) {
                return i;
            }
        }
        return -1;
    }

    private static String fold(String raw) {
        if (raw == null) {
            return "";
        }
        String folded = raw.toLowerCase(Locale.ROOT).replace('&', ' ');
        folded = folded.replaceAll("[^\\p{L}\\p{N}]+", " ");
        return folded.replaceAll("\\s+", " ").trim();
    }

    private static List<String> words(String folded) {
        if (folded == null || folded.isBlank()) {
            return List.of();
        }
        return List.of(folded.split(" "));
    }

    private static Optional<ClassifyResult> readExisting(Connection connection, long versionId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT doc_type_code, pack_code, pack_version, confidence, reasons_json, classifier"
                        + " FROM doc_classification WHERE document_version_id = ?")) {
            ps.setLong(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String docTypeCode = rs.getString("doc_type_code");
                String packCode = rs.getString("pack_code");
                String packVersion = rs.getString("pack_version");
                double confidence = rs.getDouble("confidence");
                List<String> reasons = parseReasons(rs.getString("reasons_json"));
                String classifier = rs.getString("classifier");
                boolean classified = docTypeCode != null && !"unknown".equals(docTypeCode);
                ClassifyResult.Status status = classified
                        ? ClassifyResult.Status.CLASSIFIED
                        : ClassifyResult.Status.UNKNOWN;
                return Optional.of(new ClassifyResult(
                        docTypeCode == null ? "unknown" : docTypeCode,
                        packCode,
                        packVersion,
                        confidence,
                        reasons,
                        status,
                        classifier == null ? "existing" : classifier));
            }
        }
    }

    private static List<String> parseReasons(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of(json);
        }
    }

    private static void write(Connection connection, ClassifyRequest request, ClassifyResult result)
            throws SQLException {
        String reasonsJson;
        try {
            reasonsJson = JSON.writeValueAsString(result.reasons());
        } catch (Exception e) {
            throw new SQLException("cannot serialize classification reasons: " + e.getMessage(), e);
        }
        String now = Timestamps.now();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO doc_classification (document_version_id, doc_type_code, pack_code,"
                        + " pack_version, confidence, reasons_json, industry_tag, classified_at,"
                        + " classifier)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT(document_version_id) DO UPDATE SET"
                        + " doc_type_code = excluded.doc_type_code,"
                        + " pack_code = excluded.pack_code,"
                        + " pack_version = excluded.pack_version,"
                        + " confidence = excluded.confidence,"
                        + " reasons_json = excluded.reasons_json,"
                        + " industry_tag = excluded.industry_tag,"
                        + " classified_at = excluded.classified_at,"
                        + " classifier = excluded.classifier")) {
            ps.setLong(1, request.documentVersionId());
            ps.setString(2, result.docTypeCode());
            ps.setString(3, result.packCode());
            ps.setString(4, result.packVersion());
            ps.setDouble(5, result.confidence());
            ps.setString(6, reasonsJson);
            ps.setString(7, request.industryTag());
            ps.setString(8, now);
            ps.setString(9, result.classifier());
            ps.executeUpdate();
        }
        // document.doc_type_code follows the classification (unknown clears the pointer).
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE document SET doc_type_code = ?, updated_at = ?"
                        + " WHERE document_id = (SELECT document_id FROM document_version"
                        + " WHERE document_version_id = ?)")) {
            ps.setString(1, result.docTypeCode());
            ps.setString(2, now);
            ps.setLong(3, request.documentVersionId());
            ps.executeUpdate();
        }
        syncPages(connection, request.documentVersionId(),
                result.status() == ClassifyResult.Status.CLASSIFIED);
    }

    /** Records the version's pages on a typed document, and clears them when it is not one. */
    private static void syncPages(Connection connection, long versionId, boolean classified)
            throws SQLException {
        long documentId;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT document_id FROM document_version WHERE document_version_id = ?")) {
            ps.setLong(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                documentId = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM document_page WHERE document_id = ?")) {
            ps.setLong(1, documentId);
            ps.executeUpdate();
        }
        if (!classified) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO document_page (document_id, page_id)"
                        + " SELECT ?, page_id FROM page WHERE document_version_id = ?")) {
            ps.setLong(1, documentId);
            ps.setLong(2, versionId);
            ps.executeUpdate();
        }
    }

    private static final class PackReading {
        private final DocTypePack pack;
        private final Set<String> matched = new LinkedHashSet<>();
        private final List<String> named = new ArrayList<>();

        private PackReading(DocTypePack pack) {
            this.pack = pack;
        }

        private boolean eligible() {
            return DocTypeClassifier.eligible(matched.size(), !named.isEmpty());
        }
    }

    private record ContentPhrase(String packCode, List<String> tokens, String display, String folded) {}

    private record PhraseHit(Set<String> packCodes, String display, String folded, int length) {}
}
