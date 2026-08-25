package com.resurgent.tev.parser.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Loads and validates an optional user config file over embedded defaults.
 * Unknown keys are rejected, hard ceilings are enforced, and security protections
 * cannot be disabled.
 */
public final class ConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> KNOWN_KEYS = Set.of(
            "maxFileSizeBytes",
            "maxSheetCount",
            "maxRowCount",
            "maxColumnCount",
            "maxCellCount",
            "maxZipExpansionRatio",
            "xlsEnabled",
            "rejectPasswordProtected",
            "rejectActiveXOleDde",
            "classificationEvidenceFloor");

    private ConfigLoader() {}

    /** Loads config from {@code path}, or returns embedded defaults if {@code path} is null. */
    public static ParserConfig load(Path path) throws IOException {
        if (path == null) {
            return ParserConfig.embeddedDefaults();
        }
        Map<String, Object> user = MAPPER.readValue(Files.readString(path), new TypeReference<>() {});
        return mergeAndValidate(user);
    }

    /** Parses a JSON config string directly (useful for tests). */
    public static ParserConfig load(String json) throws IOException {
        if (json == null || json.isBlank()) {
            return ParserConfig.embeddedDefaults();
        }
        Map<String, Object> user = MAPPER.readValue(json, new TypeReference<>() {});
        return mergeAndValidate(user);
    }

    private static ParserConfig mergeAndValidate(Map<String, Object> user) {
        for (String key : user.keySet()) {
            if (!KNOWN_KEYS.contains(key)) {
                throw new ConfigValidationException("unknown config key: " + key);
            }
        }

        ParserConfig defaults = ParserConfig.embeddedDefaults();

        long maxFileSizeBytes = value(user, "maxFileSizeBytes", defaults.maxFileSizeBytes(),
                v -> ((Number) v).longValue());
        int maxSheetCount = value(user, "maxSheetCount", defaults.maxSheetCount(),
                v -> ((Number) v).intValue());
        int maxRowCount = value(user, "maxRowCount", defaults.maxRowCount(),
                v -> ((Number) v).intValue());
        int maxColumnCount = value(user, "maxColumnCount", defaults.maxColumnCount(),
                v -> ((Number) v).intValue());
        long maxCellCount = value(user, "maxCellCount", defaults.maxCellCount(),
                v -> ((Number) v).longValue());
        int maxZipExpansionRatio = value(user, "maxZipExpansionRatio", defaults.maxZipExpansionRatio(),
                v -> ((Number) v).intValue());
        boolean xlsEnabled = value(user, "xlsEnabled", defaults.xlsEnabled(),
                v -> (Boolean) v);
        boolean rejectPasswordProtected = value(user, "rejectPasswordProtected",
                defaults.rejectPasswordProtected(), v -> (Boolean) v);
        boolean rejectActiveXOleDde = value(user, "rejectActiveXOleDde",
                defaults.rejectActiveXOleDde(), v -> (Boolean) v);
        int classificationEvidenceFloor = value(user, "classificationEvidenceFloor",
                defaults.classificationEvidenceFloor(), v -> ((Number) v).intValue());

        ParserConfig effective = new ParserConfig(
                maxFileSizeBytes, maxSheetCount, maxRowCount, maxColumnCount, maxCellCount,
                maxZipExpansionRatio, xlsEnabled, rejectPasswordProtected, rejectActiveXOleDde,
                classificationEvidenceFloor);

        validate(effective);
        return effective;
    }

    private static void validate(ParserConfig config) {
        assertNotAboveCeiling("maxFileSizeBytes", config.maxFileSizeBytes(),
                ParserConfig.MAX_FILE_SIZE_BYTES_CEILING);
        assertNotAboveCeiling("maxSheetCount", config.maxSheetCount(),
                ParserConfig.MAX_SHEET_COUNT_CEILING);
        assertNotAboveCeiling("maxRowCount", config.maxRowCount(),
                ParserConfig.MAX_ROW_COUNT_CEILING);
        assertNotAboveCeiling("maxColumnCount", config.maxColumnCount(),
                ParserConfig.MAX_COLUMN_COUNT_CEILING);
        assertNotAboveCeiling("maxCellCount", config.maxCellCount(),
                ParserConfig.MAX_CELL_COUNT_CEILING);
        assertNotAboveCeiling("maxZipExpansionRatio", config.maxZipExpansionRatio(),
                ParserConfig.MAX_ZIP_EXPANSION_RATIO_CEILING);

        assertSecurityProtectionEnabled(ParserConfig.PROTECTION_PASSWORD_PROTECTED,
                config.rejectPasswordProtected());
        assertSecurityProtectionEnabled(ParserConfig.PROTECTION_ACTIVE_X_OLE_DDE,
                config.rejectActiveXOleDde());
        if (config.classificationEvidenceFloor() < 1) {
            throw new ConfigValidationException("classificationEvidenceFloor must be positive");
        }
    }

    private static void assertNotAboveCeiling(String name, long value, long ceiling) {
        if (value > ceiling) {
            throw new ConfigValidationException(name + " " + value
                    + " exceeds hard ceiling " + ceiling);
        }
    }

    private static void assertSecurityProtectionEnabled(String name, boolean enabled) {
        if (!enabled) {
            throw new ConfigValidationException("security protection cannot be disabled: " + name);
        }
    }

    private static <T> T value(Map<String, Object> map, String key, T defaultValue,
            Function<Object, T> converter) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return converter.apply(value);
        } catch (ClassCastException | NullPointerException e) {
            throw new ConfigValidationException(key + " has invalid type");
        }
    }
}
