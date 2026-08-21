package com.resurgent.tev.parser.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

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
            "xlsEnabled",
            "rejectPasswordProtected",
            "rejectActiveXOleDde");

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

        long maxFileSizeBytes = longValue(user, "maxFileSizeBytes", defaults.maxFileSizeBytes());
        int maxSheetCount = intValue(user, "maxSheetCount", defaults.maxSheetCount());
        int maxRowCount = intValue(user, "maxRowCount", defaults.maxRowCount());
        int maxColumnCount = intValue(user, "maxColumnCount", defaults.maxColumnCount());
        long maxCellCount = longValue(user, "maxCellCount", defaults.maxCellCount());
        boolean xlsEnabled = boolValue(user, "xlsEnabled", defaults.xlsEnabled());
        boolean rejectPasswordProtected = boolValue(user, "rejectPasswordProtected",
                defaults.rejectPasswordProtected());
        boolean rejectActiveXOleDde = boolValue(user, "rejectActiveXOleDde",
                defaults.rejectActiveXOleDde());

        ParserConfig effective = new ParserConfig(
                maxFileSizeBytes, maxSheetCount, maxRowCount, maxColumnCount, maxCellCount,
                xlsEnabled, rejectPasswordProtected, rejectActiveXOleDde);

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

        assertSecurityProtectionEnabled(ParserConfig.PROTECTION_PASSWORD_PROTECTED,
                config.rejectPasswordProtected());
        assertSecurityProtectionEnabled(ParserConfig.PROTECTION_ACTIVE_X_OLE_DDE,
                config.rejectActiveXOleDde());
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

    private static long longValue(Map<String, Object> map, String key, long defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        throw new ConfigValidationException(key + " must be a number");
    }

    private static int intValue(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        throw new ConfigValidationException(key + " must be a number");
    }

    private static boolean boolValue(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        throw new ConfigValidationException(key + " must be a boolean");
    }
}
