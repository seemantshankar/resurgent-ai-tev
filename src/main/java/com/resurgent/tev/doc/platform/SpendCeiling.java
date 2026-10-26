package com.resurgent.tev.doc.platform;

import com.resurgent.tev.doc.db.MandatePolicyRepository;
import com.resurgent.tev.doc.extract.LlamaExtractAdapter;
import com.resurgent.tev.doc.extract.ParseTier;
import com.resurgent.tev.parser.db.LlmUsageCosts;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Whether the next cloud call is still inside the mandate's spend ceiling.
 * An unset ceiling adds no limit. Zero blocks every cloud call. A call whose
 * estimate would pass the ceiling is not made.
 */
public final class SpendCeiling {

    /** Request option carrying the effective ceiling in USD, when one applies. */
    public static final String CEILING_OPTION = "spend_ceiling_usd";

    /** Request option carrying accumulated USD spend at the moment of the call. */
    public static final String SPENT_OPTION = "spend_spent_usd";

    /** Request option carrying the platform registry path, when the command named one. */
    public static final String PLATFORM_OPTION = "platform_registry";

    private SpendCeiling() {}

    /**
     * The ceiling that applies. A mandate value wins, including zero. Otherwise
     * the platform default. Null when neither is set.
     */
    public static Double effective(Double mandateCeiling, Double platformCeiling) {
        return mandateCeiling != null ? mandateCeiling : platformCeiling;
    }

    /**
     * True when this call may be made. A null ceiling always allows it. Zero
     * never does. A positive ceiling allows the call only when the spend so far
     * plus this call's estimate stays within it.
     */
    public static boolean allows(Double ceilingUsd, double spentUsd, double estimatedUsd) {
        if (ceilingUsd == null) {
            return true;
        }
        if (ceilingUsd <= 0) {
            return false;
        }
        if (spentUsd >= ceilingUsd) {
            return false;
        }
        double estimate = estimatedUsd > 0 ? estimatedUsd : 0;
        return spentUsd + estimate <= ceilingUsd;
    }

    /** USD already recorded for the mandate, credits converted at the ledger rate. */
    public static double spentUsd(Connection connection, long mandateId) throws SQLException {
        return LlmUsageCosts.totalUsd(LlmUsageCosts.forMandate(
                connection, mandateId, LlamaExtractAdapter.usdPerCredit()));
    }

    /**
     * True when the next cloud call must be skipped.
     *
     * @param platform registry path, or null when this command was not pointed at one
     * @param estimatedUsd expected cost of the call; zero when the call has no estimate
     */
    public static boolean blocks(Connection connection, long mandateId, Path platform,
            double estimatedUsd) throws SQLException {
        Double mandate = new MandatePolicyRepository(connection).spendCeiling(mandateId);
        Double ceiling = effective(mandate, platformDefault(platform));
        return !allows(ceiling, spentUsd(connection, mandateId), estimatedUsd);
    }

    /** True when the next cloud call must be skipped. Opens the workspace to read the ceiling. */
    public static boolean blocks(Path db, long mandateId, Path platform, double estimatedUsd)
            throws SQLException {
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            return blocks(workspace.connection(), mandateId, platform, estimatedUsd);
        }
    }

    /**
     * Expected USD for a cloud parse or extract of {@code pages} pages on {@code tier}.
     */
    public static double estimateUsd(int pages, ParseTier tier) {
        int count = Math.max(1, pages);
        ParseTier priced = tier == null ? ParseTier.COST_EFFECTIVE : tier;
        return count * (double) priced.creditsPerPage() * LlamaExtractAdapter.usdPerCredit();
    }

    /**
     * The platform default ceiling, or null when no registry was named, the file
     * is not there yet, or the default is unset. Does not create a registry.
     */
    public static Double platformDefault(Path platform) {
        if (platform == null || !Files.isRegularFile(platform)) {
            return null;
        }
        try (PlatformRegistry registry = PlatformRegistry.open(platform)) {
            return registry.defaultCeiling();
        } catch (SQLException e) {
            return null;
        }
    }
}
