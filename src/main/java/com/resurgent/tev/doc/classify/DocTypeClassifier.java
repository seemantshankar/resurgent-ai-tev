package com.resurgent.tev.doc.classify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.db.CloudParsePageRepository;
import com.resurgent.tev.doc.ingest.DocumentFileKind;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.parser.db.Timestamps;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Document-side classification between layout and extract (S2.12).
 *
 * <p>Deterministic first: filter candidate packs by {@code file_role}/family,
 * score pack {@code identity_signals} against page-1 text, title blocks, and the
 * file name, and accept a winner only when it clears a score floor and a margin.
 * Ties and low scores go to {@link ClassifyTieBreak}; the offline default declines
 * and the document stays {@code unknown}. A matching pack is never invented.
 */
public final class DocTypeClassifier {

    /** Minimum pack score (one full page/title phrase hit) to classify without a tie-break. */
    static final double MIN_SCORE = 1.0;

    /** Required gap between the winner and the runner-up for a deterministic win. */
    static final double WIN_MARGIN = 1.0;

    /** Weight of a signal hit in page-1 / heading text. */
    private static final double PAGE_HIT = 1.0;

    /** Weight of a signal hit only in the file name. */
    private static final double FILE_NAME_HIT = 0.5;

    /** Score that maps to confidence 1.0. */
    private static final double CONFIDENCE_SATURATION = 2.0;

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

    private ClassifyResult score(ClassifyRequest request) {
        List<String> reasons = new ArrayList<>();
        List<PackScore> scored = new ArrayList<>();

        String fileRole = request.fileRole() == null ? "" : request.fileRole().trim();
        reasons.add("file_role=" + (fileRole.isEmpty() ? "(none)" : fileRole)
                + (request.industryTag() != null && !request.industryTag().isBlank()
                        ? ", industry=" + request.industryTag() : ""));

        for (DocTypePack pack : request.candidatePacks()) {
            if (!roleMatches(pack, fileRole)) {
                continue;
            }
            scored.add(scorePack(pack, request, reasons));
        }

        if (scored.isEmpty()) {
            reasons.add("no candidate packs after file_role/family filter");
            return ClassifyResult.unknown(reasons, "deterministic");
        }

        scored.sort(Comparator.comparingDouble(PackScore::score).reversed()
                .thenComparing(PackScore::packCode));
        PackScore top = scored.get(0);
        PackScore runnerUp = scored.size() > 1 ? scored.get(1) : null;
        double secondScore = runnerUp == null ? 0.0 : runnerUp.score();
        double margin = top.score() - secondScore;
        reasons.add("scores: " + scored.stream()
                .map(s -> s.packCode() + "=" + s.score())
                .reduce((a, b) -> a + ", " + b)
                .orElse("(none)"));
        reasons.add("winner=" + top.packCode() + " margin=" + margin);

        boolean clearWin = top.score() >= MIN_SCORE && margin >= WIN_MARGIN;
        if (clearWin) {
            double confidence = confidence(top.score());
            reasons.add("deterministic win (score>=MIN, margin>=WIN)");
            return new ClassifyResult(top.packCode(), top.packCode(), top.packVersion(),
                    confidence, reasons, ClassifyResult.Status.CLASSIFIED, "deterministic");
        }

        reasons.add(top.score() < MIN_SCORE
                ? "low score (below MIN_SCORE) — requesting tie-break"
                : "tie/low margin — requesting tie-break");
        ClassifyTieBreak.TieBreakContext context = new ClassifyTieBreak.TieBreakContext(
                fileRole,
                request.fileName(),
                request.page1Text(),
                request.industryTag(),
                scored.stream()
                        .map(s -> new ClassifyTieBreak.ScoredPack(
                                s.packCode(), s.packVersion(), s.score(), s.matchedSignals(),
                                s.identitySignals()))
                        .toList());
        Optional<ClassifyTieBreak.TieBreakChoice> choice = tieBreak.resolve(context);
        if (choice.isEmpty()) {
            reasons.add("tie-break declined — staying unknown (never invent a pack)");
            return ClassifyResult.unknown(reasons, "tie-break");
        }
        ClassifyTieBreak.TieBreakChoice won = choice.get();
        DocTypePack pack = request.candidatePacks().stream()
                .filter(p -> p.code().equals(won.packCode()))
                .findFirst()
                .orElse(null);
        if (pack == null) {
            reasons.add("tie-break chose unknown pack '" + won.packCode() + "' — staying unknown");
            return ClassifyResult.unknown(reasons, "tie-break");
        }
        reasons.add("tie-break chose " + pack.code() + ": " + won.reason());
        return new ClassifyResult(pack.code(), pack.code(), pack.version(),
                won.confidence(), reasons, ClassifyResult.Status.CLASSIFIED, "tie-break");
    }

    private static PackScore scorePack(DocTypePack pack, ClassifyRequest request,
            List<String> reasons) {
        String page = normalize(request.page1Text());
        String fileName = normalize(request.fileName());
        List<String> matched = new ArrayList<>();
        double score = 0.0;
        for (String signal : pack.identitySignals()) {
            String phrase = normalize(signal);
            if (phrase.isEmpty()) {
                continue;
            }
            if (page.contains(phrase)) {
                score += PAGE_HIT;
                matched.add(signal + " (page)");
            } else if (fileName.contains(phrase)) {
                score += FILE_NAME_HIT;
                matched.add(signal + " (file_name)");
            }
        }
        if (!matched.isEmpty()) {
            reasons.add(pack.code() + " signals: " + String.join("; ", matched));
        }
        return new PackScore(pack.code(), pack.version(), score, matched, pack.identitySignals());
    }

    /**
     * A pack is a candidate when its {@code file_role} or {@code family} matches the
     * document role, or when the role's default document family matches the pack
     * family (e.g. {@code identity} → {@code certificate}). A blank role skips the
     * filter so an open corpus can still score.
     */
    private static boolean roleMatches(DocTypePack pack, String fileRole) {
        if (fileRole.isEmpty()) {
            return true;
        }
        String role = fileRole.toLowerCase(Locale.ROOT);
        if (pack.fileRole() != null && pack.fileRole().toLowerCase(Locale.ROOT).equals(role)) {
            return true;
        }
        if (pack.family() != null && pack.family().toLowerCase(Locale.ROOT).equals(role)) {
            return true;
        }
        String family = DocumentFileKind.defaultDocFamily(
                DocumentFileKind.normalizeFileRole(role));
        return pack.family() != null && pack.family().toLowerCase(Locale.ROOT).equals(family);
    }

    private static double confidence(double packScore) {
        if (packScore <= 0) {
            return 0.0;
        }
        return Math.min(1.0, packScore / CONFIDENCE_SATURATION);
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
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
    }

    private record PackScore(String packCode, String packVersion, double score,
            List<String> matchedSignals, List<String> identitySignals) {
        PackScore {
            matchedSignals = matchedSignals == null ? List.of() : List.copyOf(matchedSignals);
            identitySignals = identitySignals == null ? List.of() : List.copyOf(identitySignals);
        }
    }
}
