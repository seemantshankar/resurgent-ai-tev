package com.resurgent.tev.parser.enrichment;

import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Loads and updates the region type menu shared by enrichment runs. */
public final class RegionTypeMenuService {

    private static final Map<String, String> SYNONYMS = Map.of(
            "civil", "civil cost",
            "civil works", "civil cost");

    private final Path dbPath;

    public RegionTypeMenuService(Path dbPath) {
        this.dbPath = Objects.requireNonNull(dbPath, "dbPath");
    }

    public List<String> load() throws SQLException {
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            return load(db.connection());
        }
    }

    public RegionTypeNormalizationResult normalizeProposals(List<String> proposedTypes)
            throws SQLException {
        Objects.requireNonNull(proposedTypes, "proposedTypes");
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            Connection connection = db.connection();
            List<String> types = new ArrayList<>(load(connection));
            Map<String, String> canonicalByKey = index(types);
            List<String> canonicalTypes = new ArrayList<>();
            List<String> newTypesAdded = new ArrayList<>();

            for (String proposal : proposedTypes) {
                String candidate = requireValidProposal(proposal);
                String key = key(candidate);
                String synonymKey = SYNONYMS.getOrDefault(key, key);
                String canonical = canonicalByKey.get(synonymKey);
                if (canonical == null) {
                    insert(connection, candidate);
                    canonical = candidate;
                    types.add(canonical);
                    canonicalByKey.put(key, canonical);
                    newTypesAdded.add(canonical);
                }
                canonicalTypes.add(canonical);
            }

            return new RegionTypeNormalizationResult(canonicalTypes, types, newTypesAdded);
        }
    }

    private static List<String> load(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT canonical_name FROM region_type_menu ORDER BY region_type_id")) {
            List<String> types = new ArrayList<>();
            while (rows.next()) {
                types.add(rows.getString(1));
            }
            return List.copyOf(types);
        }
    }

    private static Map<String, String> index(List<String> types) {
        Map<String, String> canonicalByKey = new LinkedHashMap<>();
        for (String type : types) {
            canonicalByKey.put(key(type), type);
        }
        return canonicalByKey;
    }

    private static String requireValidProposal(String proposal) {
        if (proposal == null || proposal.isBlank()) {
            throw new IllegalArgumentException("region type must not be blank");
        }
        String candidate = proposal.trim().replaceAll("\\s+", " ");
        if (key(candidate).equals("other")) {
            throw new IllegalArgumentException("\"Other\" is not a valid region type");
        }
        return candidate;
    }

    private static String key(String value) {
        return value.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    private static void insert(Connection connection, String canonicalName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO region_type_menu (canonical_name) VALUES (?)")) {
            statement.setString(1, canonicalName);
            statement.executeUpdate();
        }
    }
}
