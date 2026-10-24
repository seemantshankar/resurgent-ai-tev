package com.resurgent.tev.doc.extract;

import com.resurgent.tev.doc.assess.ClauseSegmenter;
import com.resurgent.tev.doc.assess.PassageSplitter;
import com.resurgent.tev.doc.db.AssessmentRepository.PageText;
import com.resurgent.tev.doc.db.ClauseRepository;
import com.resurgent.tev.doc.db.ClauseRepository.ClauseWrite;
import com.resurgent.tev.doc.db.PassageRepository;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackClauseType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/**
 * Binds clauses and passages from the assembled pages. Field and table facts
 * stay in {@link LayoutExtractBridge}; this only closes the gap the bridge
 * left for a page-backed extract.
 */
final class PageAssembly {

    private PageAssembly() {}

    static void bind(Connection connection, long documentVersionId, long parseRunId,
            DocTypePack pack) throws SQLException {
        PassageRepository passages = new PassageRepository(connection);
        passages.clearForDocumentVersion(documentVersionId);
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM clause WHERE document_version_id = ?")) {
            ps.setLong(1, documentVersionId);
            ps.executeUpdate();
        }
        bindClauses(connection, documentVersionId, parseRunId, pack);
        bindPassages(passages, documentVersionId, pack);
    }

    private static void bindClauses(Connection connection, long documentVersionId,
            long parseRunId, DocTypePack pack) throws SQLException {
        if (pack.clauseTypes().isEmpty()) {
            return;
        }
        ClauseRepository clauses = new ClauseRepository(connection);
        List<PageText> pages = clauses.pageTextsForClauses(documentVersionId);
        for (ClauseSegmenter.Segment segment : ClauseSegmenter.segment(pages)) {
            PackClauseType matched = aliasMatch(pack.clauseTypes(), segment);
            if (matched == null) {
                continue;
            }
            long typeId = clauses.upsertPackType(pack.code(), pack.version(), matched);
            clauses.insertClause(new ClauseWrite(
                    documentVersionId, parseRunId, typeId,
                    segment.heading(), segment.text(), segment.pageStart(), segment.pageEnd(),
                    "bound", "known", null, null, "alias"));
        }
    }

    private static void bindPassages(PassageRepository passages, long documentVersionId,
            DocTypePack pack) throws SQLException {
        if (pack.passages().isEmpty()) {
            return;
        }
        List<PassageSplitter.Passage> chapters = PassageSplitter.split(
                passages.parsePages(documentVersionId));
        int index = 0;
        for (PassageSplitter.Passage chapter : chapters) {
            index++;
            var spec = PassageSplitter.specFor(pack, chapter.heading());
            if (spec == null) {
                continue;
            }
            String summary = chapter.text().isBlank() ? null : firstLine(chapter.text());
            passages.insertPassage(documentVersionId, index, chapter.heading(), spec.topic(),
                    chapter.pageFrom(), chapter.pageTo(), summary);
        }
    }

    private static PackClauseType aliasMatch(List<PackClauseType> types,
            ClauseSegmenter.Segment segment) {
        PackClauseType found = null;
        for (PackClauseType type : types) {
            if (aliasIn(type, segment.heading())) {
                if (found != null) {
                    return null;
                }
                found = type;
            }
        }
        return found;
    }

    private static boolean aliasIn(PackClauseType type, String text) {
        if (text == null || text.isBlank() || type.aliases().isEmpty()) {
            return false;
        }
        String haystack = " " + normalize(text) + " ";
        for (String alias : type.aliases()) {
            String needle = normalize(alias);
            if (!needle.isEmpty() && haystack.contains(" " + needle + " ")) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        String line = (newline < 0 ? text : text.substring(0, newline)).strip();
        return line.length() > 240 ? line.substring(0, 240) : line;
    }
}
