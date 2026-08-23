package com.resurgent.tev.parser.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Effective parser configuration. Instances are immutable and carry a deterministic
 * hash that changes when any effective value changes.
 */
public record ParserConfig(
        long maxFileSizeBytes,
        int maxSheetCount,
        int maxRowCount,
        int maxColumnCount,
        long maxCellCount,
        int maxZipExpansionRatio,
        boolean xlsEnabled,
        boolean rejectPasswordProtected,
        boolean rejectActiveXOleDde,
        int regionBreakThreshold) {

    /** Embedded defaults used when no --config file is supplied. */
    public static ParserConfig embeddedDefaults() {
        return new ParserConfig(
                100L * 1024 * 1024,   // 100 MiB
                200,                   // sheets
                1_000_000,             // rows
                16_384,                // columns
                5_000_000L,            // cells
                100,                   // expansion ratio (compressed bytes -> unpacked bytes)
                false,                 // xls adapter disabled by default
                true,                  // reject password-protected files
                true,                  // reject ActiveX/OLE/DDE payloads
                4);                    // region break score
    }

    /** Hard ceilings that user values may not exceed. */
    public static final long MAX_FILE_SIZE_BYTES_CEILING = 1024L * 1024 * 1024; // 1 GiB
    public static final int MAX_SHEET_COUNT_CEILING = 200;
    public static final int MAX_ROW_COUNT_CEILING = 2_000_000;
    public static final int MAX_COLUMN_COUNT_CEILING = 32_000;
    public static final long MAX_CELL_COUNT_CEILING = 50_000_000L;
    public static final int MAX_ZIP_EXPANSION_RATIO_CEILING = 1000;

    /** Stable identifiers for the security protections that cannot be disabled. */
    public static final String PROTECTION_PASSWORD_PROTECTED = "rejectPasswordProtected";
    public static final String PROTECTION_ACTIVE_X_OLE_DDE = "rejectActiveXOleDde";

    /**
     * Deterministic SHA-256 hash of the canonical JSON representation of this config.
     * The JSON is produced with alphabetically sorted keys and no pretty-printing so
     * identical effective configs yield identical hashes.
     */
    public String configHash() {
        try {
            // The weights are intentionally outside this positional record, but still part of
            // the run identity: changing the resource must not reuse an old parse run.
            String canonical = CANONICAL_MAPPER.writeValueAsString(Map.of(
                    "config", this, "regionWeightsHash", RegionWeights.defaults().contentHash()));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize effective config", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.WRITE_ENUMS_USING_TO_STRING, true)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
}
