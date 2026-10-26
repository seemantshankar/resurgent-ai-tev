package com.resurgent.tev.doc.extract;

import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.db.UnstableReadingRepository;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackInstructions;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Two-run extraction stability (#157): diff facts per field_key (+ line_key), persist
 * unstable readings, and flag facts on the canonical extract run.
 */
public final class ExtractionStabilityService {

    public static final String STAGE = "doc-drift";
    public static final String REASON = "unstable_across_runs";
    public static final String PASS_A_SUFFIX = ":stability-pass-a";
    public static final String PASS_B_SUFFIX = ":stability-pass-b";

    public record StabilityOutcome(
            long parseRunIdA,
            long parseRunIdB,
            int comparedSlots,
            int unstableSlots,
            Long canonicalParseRunId) {}

    private ExtractionStabilityService() {}

    /**
     * Runs extract twice with stability-specific parse_run identities, diffs, persists.
     */
    public static StabilityOutcome runTwoPassExtract(
            Path dbPath,
            long mandateId,
            long documentVersionId,
            DocTypePack pack,
            ExtractClient client) throws Exception {
        ExtractService service = new ExtractService();
        String baseBundle = client.id();
        ExtractService.ExtractOutcome a = service.extract(
                dbPath, documentVersionId, mandateId, pack, client, baseBundle + PASS_A_SUFFIX);
        if ("failed".equals(a.status())) {
            throw new IllegalStateException("stability pass A failed: " + a.message());
        }
        ExtractService.ExtractOutcome b = service.extract(
                dbPath, documentVersionId, mandateId, pack, client, baseBundle + PASS_B_SUFFIX);
        if ("failed".equals(b.status())) {
            throw new IllegalStateException("stability pass B failed: " + b.message());
        }
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection connection = db.connection();
            connection.setAutoCommit(false);
            try {
                StabilityOutcome outcome = compareParseRuns(
                        connection, mandateId, documentVersionId, pack,
                        a.parseRunId(), b.parseRunId(), baseBundle);
                connection.commit();
                return outcome;
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    /**
     * Diffs two existing extract parse runs and persists unstable readings.
     */
    public static StabilityOutcome compareParseRuns(
            Connection connection,
            long mandateId,
            long documentVersionId,
            DocTypePack pack,
            long parseRunIdA,
            long parseRunIdB,
            String canonicalBundleVersion) throws SQLException {
        FactRepository facts = new FactRepository(connection);
        UnstableReadingRepository unstable = new UnstableReadingRepository(connection);

        unstable.clearForVersion(documentVersionId);
        clearUnstableFlags(connection, documentVersionId);

        Map<String, SlotFact> left = loadSlots(facts, parseRunIdA);
        Map<String, SlotFact> right = loadSlots(facts, parseRunIdB);
        int compared = 0;
        int drift = 0;
        for (Map.Entry<String, SlotFact> entry : left.entrySet()) {
            SlotFact a = entry.getValue();
            SlotFact b = right.get(entry.getKey());
            if (b == null) {
                continue;
            }
            compared++;
            if (sameValue(a, b)) {
                continue;
            }
            drift++;
            unstable.insert(new UnstableReadingRepository.Write(
                    mandateId,
                    documentVersionId,
                    a.fieldKey(),
                    a.lineKey(),
                    parseRunIdA,
                    parseRunIdB,
                    a.factId(),
                    b.factId(),
                    a.displayValue(),
                    b.displayValue(),
                    a.pageIndex(),
                    b.pageIndex(),
                    PackInstructions.forFieldKey(pack, a.fieldKey()),
                    REASON));
        }

        Long canonicalRun = canonicalBundleVersion == null
                ? null
                : findCanonicalParseRun(connection, documentVersionId, pack, canonicalBundleVersion);
        if (canonicalRun != null) {
            applyUnstableFlags(connection, documentVersionId, canonicalRun, unstable);
        } else {
            applyUnstableFlags(connection, documentVersionId, parseRunIdB, unstable);
        }
        recordRunStat(connection, parseRunIdB, compared, drift);
        return new StabilityOutcome(parseRunIdA, parseRunIdB, compared, drift, canonicalRun);
    }

    private static void applyUnstableFlags(
            Connection connection,
            long documentVersionId,
            long parseRunId,
            UnstableReadingRepository unstable) throws SQLException {
        List<UnstableReadingRepository.UnstableReadingRow> rows = unstable.listForVersion(documentVersionId);
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE fact SET unstable_across_runs = 1"
                        + " WHERE parse_run_id = ? AND field_key = ?"
                        + " AND (line_key IS ? OR line_key = ?)")) {
            for (UnstableReadingRepository.UnstableReadingRow row : rows) {
                ps.setLong(1, parseRunId);
                ps.setString(2, row.fieldKey());
                if (row.lineKey() == null) {
                    ps.setNull(3, java.sql.Types.VARCHAR);
                    ps.setNull(4, java.sql.Types.VARCHAR);
                } else {
                    ps.setString(3, row.lineKey());
                    ps.setString(4, row.lineKey());
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void clearUnstableFlags(Connection connection, long documentVersionId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE fact SET unstable_across_runs = 0 WHERE document_version_id = ?")) {
            ps.setLong(1, documentVersionId);
            ps.executeUpdate();
        }
    }

    private static Map<String, SlotFact> loadSlots(FactRepository facts, long parseRunId)
            throws SQLException {
        Map<String, SlotFact> byKey = new LinkedHashMap<>();
        for (FactRepository.FactBindRow row : facts.selectFactsForParseRun(parseRunId)) {
            String key = PackInstructions.factSlotKey(row.fieldKey(), row.lineKey());
            byKey.put(key, new SlotFact(
                    row.factId(),
                    row.fieldKey(),
                    row.lineKey(),
                    row.valueText(),
                    row.pageIndex() == null ? null : row.pageIndex().intValue()));
        }
        return byKey;
    }

    private static boolean sameValue(SlotFact a, SlotFact b) {
        String left = normalize(a.displayValue());
        String right = normalize(b.displayValue());
        return Objects.equals(left, right);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static Long findCanonicalParseRun(
            Connection connection,
            long documentVersionId,
            DocTypePack pack,
            String extractorBundle) throws SQLException {
        WorkspaceRepository repo = new WorkspaceRepository(connection);
        Long sourceFileId = sourceFileId(connection, documentVersionId);
        if (sourceFileId == null) {
            return null;
        }
        String configHash = ExtractService.configHash(pack, extractorBundle);
        return repo.findParseRunId(sourceFileId, ExtractService.PARSER_VERSION, configHash);
    }

    private static Long sourceFileId(Connection connection, long documentVersionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT source_file_id FROM document_version WHERE document_version_id = ?")) {
            ps.setLong(1, documentVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong("source_file_id");
                    return rs.wasNull() ? null : id;
                }
            }
        }
        return null;
    }

    private static void recordRunStat(Connection connection, long parseRunId, int compared, int drift)
            throws SQLException {
        new FactRepository(connection).recordRunStat(parseRunId, STAGE, "slots_compared", (double) compared, null);
        new FactRepository(connection).recordRunStat(parseRunId, STAGE, "unstable_slots", (double) drift, null);
    }

    private record SlotFact(
            long factId,
            String fieldKey,
            String lineKey,
            String displayValue,
            Integer pageIndex) {}
}
