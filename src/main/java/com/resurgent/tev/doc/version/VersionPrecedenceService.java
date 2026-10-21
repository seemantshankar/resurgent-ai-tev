package com.resurgent.tev.doc.version;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resurgent.tev.doc.db.DocumentRepository;
import com.resurgent.tev.parser.db.Timestamps;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M4-04: Resolves document version order via the 4-tier precedence ladder and
 * populates directed relations in {@code document_relation} (ADR 0028 §1).
 */
public final class VersionPrecedenceService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern AMENDMENT_NUM_PATTERN = Pattern.compile(
            "(?i)(?:amendment|addendum)\\s*(?:no\\.?|num\\.?|#)?\\s*(\\d+)");
    private static final Pattern AMENDMENT_GENERIC_PATTERN = Pattern.compile(
            "(?i)\\b(?:amendment|addendum|amends)\\b");
    private static final Pattern BASE_PATTERN = Pattern.compile(
            "(?i)\\b(?:base|original|unamended)\\b");
    private static final Pattern REVISION_NUM_PATTERN = Pattern.compile(
            "(?i)\\b(?:revision|rev\\.?)\\s*(\\d+)");
    private static final Pattern REVISION_GENERIC_PATTERN = Pattern.compile(
            "(?i)\\b(?:revised|revision|supersedes)\\b");

    private VersionPrecedenceService() {}

    public record DocumentVersionInfo(
            long documentId,
            String logicalName,
            String docFamily,
            String docTypeCode,
            long documentVersionId,
            String versionLabel,
            String fileName,
            String fileHash,
            String ingestedAt,
            String rawMetadata,
            String documentDate,
            String datePrecision,
            String dateSource,
            String textSnippet
    ) {}

    /**
     * Resolves version precedence and records duplicate, amendment, and supersession
     * relations across all active documents in the given mandate.
     */
    public static void resolvePrecedenceAndRelations(Path dbPath, long mandateId) throws Exception {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            resolvePrecedenceAndRelations(db.connection(), mandateId);
        }
    }

    /**
     * Resolves version precedence and populates {@code document_relation} using
     * an open connection.
     */
    public static void resolvePrecedenceAndRelations(Connection connection, long mandateId) throws SQLException {
        DocumentRepository repo = new DocumentRepository(connection);
        List<DocumentVersionInfo> allVersions = loadVersions(connection, mandateId);
        if (allVersions.isEmpty()) {
            return;
        }

        // 1. Identical SHA-256 duplicate detection -> duplicate_of
        resolveDuplicates(connection, allVersions);

        // 2. Intra-document precedence: versions of the same logical document -> current_version_id and supersedes
        Map<Long, List<DocumentVersionInfo>> byDoc = new LinkedHashMap<>();
        for (DocumentVersionInfo v : allVersions) {
            byDoc.computeIfAbsent(v.documentId(), k -> new ArrayList<>()).add(v);
        }

        Map<Long, DocumentVersionInfo> currentVersions = new LinkedHashMap<>();
        for (Map.Entry<Long, List<DocumentVersionInfo>> entry : byDoc.entrySet()) {
            long docId = entry.getKey();
            List<DocumentVersionInfo> versions = entry.getValue();
            versions.sort(VersionPrecedenceService::comparePrecedence);

            DocumentVersionInfo winner = versions.get(versions.size() - 1);
            repo.setCurrentVersion(docId, winner.documentVersionId());
            currentVersions.put(docId, winner);

            // Winner supersedes all earlier versions in the same document
            for (int i = 0; i < versions.size() - 1; i++) {
                DocumentVersionInfo older = versions.get(i);
                insertRelationIfAbsent(connection, winner.documentVersionId(), older.documentVersionId(),
                        "supersedes", "Superseded by newer version of " + winner.logicalName());
            }
        }

        // 3. Inter-document relations: amendments targeting base agreements -> amends
        resolveAmendments(connection, allVersions, currentVersions);

        // 4. Inter-document relations: revised documents of same type/entity -> supersedes
        resolveInterDocumentReplacements(connection, byDoc, currentVersions);
    }

    /**
     * Compares two document versions according to the 4-tier precedence ladder:
     * 1. Self-declarations stated within document text or metadata (amendments, revisions)
     * 2. Stated document date (ISO-8601 string)
     * 3. Upload date (ingested_at) as tie-breaker
     * 4. File metadata / version ID fallback
     *
     * @return positive if {@code a} is newer/higher precedence than {@code b},
     *         negative if {@code b} is newer, 0 if equal
     */
    public static int comparePrecedence(DocumentVersionInfo a, DocumentVersionInfo b) {
        if (a.documentVersionId() == b.documentVersionId()) {
            return 0;
        }

        // Tier 1: Document Self-Declarations
        int aAmend = getAmendmentLevel(a);
        int bAmend = getAmendmentLevel(b);
        if (aAmend >= 0 && bAmend >= 0 && aAmend != bAmend) {
            return Integer.compare(aAmend, bAmend);
        }

        int aRev = getRevisionLevel(a);
        int bRev = getRevisionLevel(b);
        if (aRev >= 0 && bRev >= 0 && aRev != bRev) {
            return Integer.compare(aRev, bRev);
        }

        // Tier 2: Stated Document Date
        String dateA = a.documentDate();
        String dateB = b.documentDate();
        boolean hasDateA = dateA != null && !dateA.isBlank();
        boolean hasDateB = dateB != null && !dateB.isBlank();
        if (hasDateA && hasDateB) {
            int dateCmp = dateA.compareTo(dateB);
            if (dateCmp != 0) {
                return dateCmp;
            }
        } else if (hasDateA) {
            return 1;
        } else if (hasDateB) {
            return -1;
        }

        // Tier 3: Upload Date (ingested_at)
        String ingestA = a.ingestedAt();
        String ingestB = b.ingestedAt();
        if (ingestA != null && !ingestA.isBlank() && ingestB != null && !ingestB.isBlank()) {
            int ingestCmp = ingestA.compareTo(ingestB);
            if (ingestCmp != 0) {
                return ingestCmp;
            }
        }

        // Tier 4: File Metadata & fallback
        String modA = extractModifiedAt(a.rawMetadata());
        String modB = extractModifiedAt(b.rawMetadata());
        if (modA != null && modB != null) {
            int modCmp = modA.compareTo(modB);
            if (modCmp != 0) {
                return modCmp;
            }
        }

        return Long.compare(a.documentVersionId(), b.documentVersionId());
    }

    private static void resolveDuplicates(Connection connection, List<DocumentVersionInfo> allVersions)
            throws SQLException {
        Map<String, List<DocumentVersionInfo>> byHash = new LinkedHashMap<>();
        for (DocumentVersionInfo v : allVersions) {
            if (v.fileHash() != null && !v.fileHash().isBlank()) {
                byHash.computeIfAbsent(v.fileHash(), k -> new ArrayList<>()).add(v);
            }
        }

        for (Map.Entry<String, List<DocumentVersionInfo>> entry : byHash.entrySet()) {
            List<DocumentVersionInfo> duplicates = entry.getValue();
            if (duplicates.size() < 2) {
                continue;
            }
            // Sort by version id or ingested_at ascending
            duplicates.sort(Comparator.comparing(DocumentVersionInfo::documentVersionId));
            DocumentVersionInfo original = duplicates.get(0);
            for (int i = 1; i < duplicates.size(); i++) {
                DocumentVersionInfo duplicate = duplicates.get(i);
                insertRelationIfAbsent(connection, duplicate.documentVersionId(), original.documentVersionId(),
                        "duplicate_of", "duplicate file hash: " + entry.getKey().substring(0, Math.min(12, entry.getKey().length())));
            }
        }
    }

    private static void resolveAmendments(Connection connection, List<DocumentVersionInfo> allVersions,
            Map<Long, DocumentVersionInfo> currentVersions) throws SQLException {
        for (DocumentVersionInfo v : allVersions) {
            if (!isAmendment(v)) {
                continue;
            }
            // Try to find the matching base document
            String baseLogicalName = stripAmendmentSuffix(v.logicalName());
            DocumentVersionInfo targetBase = null;

            for (DocumentVersionInfo candidate : currentVersions.values()) {
                if (candidate.documentId() == v.documentId()) {
                    continue;
                }
                if (candidate.logicalName().equalsIgnoreCase(baseLogicalName)) {
                    targetBase = candidate;
                    break;
                }
            }

            // Fallback: prefix match
            if (targetBase == null) {
                for (DocumentVersionInfo candidate : currentVersions.values()) {
                    if (candidate.documentId() == v.documentId()) {
                        continue;
                    }
                    if (v.logicalName().toLowerCase(Locale.ROOT).startsWith(
                            candidate.logicalName().toLowerCase(Locale.ROOT))) {
                        targetBase = candidate;
                        break;
                    }
                }
            }

            if (targetBase != null) {
                insertRelationIfAbsent(connection, v.documentVersionId(), targetBase.documentVersionId(),
                        "amends", "Amendment to " + targetBase.logicalName());
            }
        }
    }

    private static void resolveInterDocumentReplacements(Connection connection,
            Map<Long, List<DocumentVersionInfo>> byDoc,
            Map<Long, DocumentVersionInfo> currentVersions) throws SQLException {
        List<DocumentVersionInfo> activeList = new ArrayList<>(currentVersions.values());
        for (int i = 0; i < activeList.size(); i++) {
            DocumentVersionInfo docA = activeList.get(i);
            for (int j = i + 1; j < activeList.size(); j++) {
                DocumentVersionInfo docB = activeList.get(j);
                if (docA.documentId() == docB.documentId()) {
                    continue;
                }
                // Check if one is a revised replacement of the other
                if (isRevisedReplacementPair(docA, docB)) {
                    int cmp = comparePrecedence(docA, docB);
                    if (cmp > 0) {
                        insertRelationIfAbsent(connection, docA.documentVersionId(), docB.documentVersionId(),
                                "supersedes", docA.logicalName() + " supersedes " + docB.logicalName());
                    } else if (cmp < 0) {
                        insertRelationIfAbsent(connection, docB.documentVersionId(), docA.documentVersionId(),
                                "supersedes", docB.logicalName() + " supersedes " + docA.logicalName());
                    }
                }
            }
        }
    }

    private static boolean isRevisedReplacementPair(DocumentVersionInfo a, DocumentVersionInfo b) {
        // Must share doc family or doc type code
        boolean sameFamily = a.docFamily() != null && a.docFamily().equalsIgnoreCase(b.docFamily());
        boolean sameType = a.docTypeCode() != null && a.docTypeCode().equalsIgnoreCase(b.docTypeCode());
        if (!sameFamily && !sameType) {
            return false;
        }

        // Neither is an amendment
        if (isAmendment(a) || isAmendment(b)) {
            return false;
        }

        String nameA = a.logicalName().toLowerCase(Locale.ROOT);
        String nameB = b.logicalName().toLowerCase(Locale.ROOT);
        boolean aRevised = nameA.contains("revised") || nameA.contains("revision");
        boolean bRevised = nameB.contains("revised") || nameB.contains("revision");

        if (aRevised || bRevised) {
            String strippedA = stripRevisionWord(nameA);
            String strippedB = stripRevisionWord(nameB);
            if (strippedA.contains(strippedB) || strippedB.contains(strippedA)) {
                return true;
            }
        }
        return false;
    }

    private static String stripRevisionWord(String name) {
        return name.replaceAll("(?i)\\b(?:revised|revision|main|rev)\\b", "")
                .replaceAll("[-–—_\\s]+", " ")
                .trim();
    }

    private static boolean isAmendment(DocumentVersionInfo v) {
        String label = v.versionLabel() == null ? "" : v.versionLabel();
        String name = v.logicalName() == null ? "" : v.logicalName();
        String snippet = v.textSnippet() == null ? "" : v.textSnippet();
        return AMENDMENT_GENERIC_PATTERN.matcher(label).find()
                || AMENDMENT_GENERIC_PATTERN.matcher(name).find()
                || AMENDMENT_GENERIC_PATTERN.matcher(snippet).find();
    }

    private static String stripAmendmentSuffix(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("(?i)\\s*[-–—:]*\\s*(?:amendment|addendum)\\b.*$", "").trim();
    }

    private static int getAmendmentLevel(DocumentVersionInfo info) {
        String text = (info.versionLabel() + " " + info.logicalName() + " " + (info.textSnippet() == null ? "" : info.textSnippet()));
        Matcher numMatcher = AMENDMENT_NUM_PATTERN.matcher(text);
        if (numMatcher.find()) {
            try {
                return Integer.parseInt(numMatcher.group(1));
            } catch (NumberFormatException ignored) {}
        }
        if (AMENDMENT_GENERIC_PATTERN.matcher(text).find()) {
            return 1;
        }
        if (BASE_PATTERN.matcher(text).find()) {
            return 0;
        }
        return -1;
    }

    private static int getRevisionLevel(DocumentVersionInfo info) {
        String text = (info.versionLabel() + " " + info.logicalName() + " " + (info.textSnippet() == null ? "" : info.textSnippet()));
        Matcher numMatcher = REVISION_NUM_PATTERN.matcher(text);
        if (numMatcher.find()) {
            try {
                return Integer.parseInt(numMatcher.group(1));
            } catch (NumberFormatException ignored) {}
        }
        if (REVISION_GENERIC_PATTERN.matcher(text).find()) {
            return 1;
        }
        if (BASE_PATTERN.matcher(text).find()) {
            return 0;
        }
        return -1;
    }

    private static String extractModifiedAt(String rawMetadata) {
        if (rawMetadata == null || rawMetadata.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(rawMetadata);
            JsonNode mod = node.get("file_modified_at");
            return mod != null && !mod.isNull() ? mod.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void insertRelationIfAbsent(Connection connection, long fromId, long toId,
            String relationKind, String note) throws SQLException {
        if (fromId == toId) {
            return;
        }
        String sql = "INSERT OR IGNORE INTO document_relation "
                + "(from_document_version_id, to_document_version_id, relation_kind, note, created_at) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, fromId);
            ps.setLong(2, toId);
            ps.setString(3, relationKind);
            ps.setString(4, note);
            ps.setString(5, Timestamps.now());
            ps.executeUpdate();
        }
    }

    private static List<DocumentVersionInfo> loadVersions(Connection connection, long mandateId)
            throws SQLException {
        String sql = "SELECT d.document_id, d.logical_name, d.doc_family, d.doc_type_code,"
                + " v.document_version_id, v.version_label, v.file_name, v.file_hash,"
                + " v.ingested_at, v.raw_metadata, v.document_date, v.date_precision, v.date_source"
                + " FROM document d"
                + " JOIN document_version v ON d.document_id = v.document_id"
                + " WHERE d.mandate_id = ? AND d.status != 'deleted_by_analyst'"
                + " ORDER BY d.document_id, v.document_version_id";

        List<DocumentVersionInfo> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long versionId = rs.getLong("document_version_id");
                    String textSnippet = loadTextSnippet(connection, versionId);
                    list.add(new DocumentVersionInfo(
                            rs.getLong("document_id"),
                            rs.getString("logical_name"),
                            rs.getString("doc_family"),
                            rs.getString("doc_type_code"),
                            versionId,
                            rs.getString("version_label"),
                            rs.getString("file_name"),
                            rs.getString("file_hash"),
                            rs.getString("ingested_at"),
                            rs.getString("raw_metadata"),
                            rs.getString("document_date"),
                            rs.getString("date_precision"),
                            rs.getString("date_source"),
                            textSnippet
                    ));
                }
            }
        }
        return list;
    }

    private static String loadTextSnippet(Connection connection, long documentVersionId) {
        String sql = "SELECT b.text_norm FROM block b"
                + " JOIN page p ON p.page_id = b.page_id"
                + " WHERE p.document_version_id = ?"
                + " ORDER BY p.page_index, b.block_index LIMIT 20";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                StringBuilder sb = new StringBuilder();
                while (rs.next()) {
                    String norm = rs.getString(1);
                    if (norm != null && !norm.isBlank()) {
                        sb.append(norm).append(" ");
                    }
                }
                return sb.toString().trim();
            }
        } catch (SQLException ignored) {
            return "";
        }
    }
}
