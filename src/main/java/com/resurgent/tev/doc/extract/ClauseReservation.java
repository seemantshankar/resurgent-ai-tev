package com.resurgent.tev.doc.extract;

import com.resurgent.tev.doc.assess.AssessClausesRunner;
import com.resurgent.tev.doc.assess.ClauseSegmenter;
import com.resurgent.tev.doc.db.AssessmentRepository.PageText;
import com.resurgent.tev.doc.db.ClauseRepository;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackClauseType;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Holds clause sentences so stated-claim reading does not split them (ADR 0036).
 */
final class ClauseReservation {

    record Reserved(
            String heading,
            String verbatim,
            int pageStart,
            int pageEnd,
            PackClauseType type) {}

    record Plan(List<Reserved> reserved) {
        Plan {
            reserved = reserved == null ? List.of() : List.copyOf(reserved);
        }

        Set<String> coveredTexts() {
            Set<String> texts = new LinkedHashSet<>();
            for (Reserved item : reserved) {
                if (item.verbatim() != null && !item.verbatim().isBlank()) {
                    texts.add(item.verbatim());
                    for (String line : item.verbatim().split("\\R")) {
                        if (!line.isBlank()) {
                            texts.add(line.trim());
                        }
                    }
                }
            }
            return texts;
        }
    }

    private ClauseReservation() {}

    static Plan plan(Connection connection, long documentVersionId, DocTypePack pack)
            throws SQLException {
        if (pack == null || !pack.declaresClauses()) {
            return new Plan(List.of());
        }
        List<PageText> pages = new ClauseRepository(connection)
                .pageTextsForClauses(documentVersionId);
        List<Reserved> reserved = new ArrayList<>();
        for (ClauseSegmenter.Segment segment : ClauseSegmenter.segment(pages)) {
            Optional<PackClauseType> matched = AssessClausesRunner.aliasMatch(
                    pack.clauseTypes(), segment);
            if (matched.isEmpty()) {
                continue;
            }
            reserved.add(new Reserved(segment.heading(), segment.text(),
                    segment.pageStart(), segment.pageEnd(), matched.get()));
        }
        return new Plan(reserved);
    }

    static void persist(Connection connection, long documentVersionId, long parseRunId,
            DocTypePack pack, Plan plan) throws SQLException {
        ClauseRepository clauses = new ClauseRepository(connection);
        clauses.deleteClausesForVersion(documentVersionId);
        if (plan == null || plan.reserved().isEmpty() || pack == null) {
            return;
        }
        for (Reserved item : plan.reserved()) {
            long typeId = clauses.upsertPackType(pack.code(), pack.version(), item.type());
            clauses.insertClause(new ClauseRepository.ClauseWrite(
                    documentVersionId, parseRunId, typeId, item.heading(), item.verbatim(),
                    item.pageStart(), item.pageEnd(), "bound", "known", null, null, "alias"));
        }
    }
}
