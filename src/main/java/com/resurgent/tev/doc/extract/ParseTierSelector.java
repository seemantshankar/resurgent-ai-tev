package com.resurgent.tev.doc.extract;

import java.util.Locale;

/**
 * Chooses a {@link ParseTier} from document layout signals (LlamaParse tier guide).
 *
 * <p>Decision ladder (first match wins):
 * <ol>
 *   <li>scanned / {@code ocrRequired} → {@code agentic}</li>
 *   <li>identity cards/certificates (ePAN, GST, COI) → {@code agentic}
 *       (seals, QR/security print, multi-region layout)</li>
 *   <li>figures or charts present → {@code agentic} (or {@code agentic_plus} when dense)</li>
 *   <li>dense financial / complex tables → {@code agentic_plus}</li>
 *   <li>multi-column or real tables → {@code agentic}</li>
 *   <li>simple tables, mostly text → {@code cost_effective}</li>
 *   <li>dense multi-page text → {@code cost_effective}</li>
 *   <li>plain single-column text → {@code fast}</li>
 * </ol>
 *
 * <p>Mixed simple-plus-complex pages stay on {@code agentic} (or {@code agentic_plus}
 * when mission-critical accuracy is requested via {@link Signals#missionCritical()}).
 */
public final class ParseTierSelector {

    private ParseTierSelector() {}

    /**
     * Layout/content signals gathered from M1 layout + document metadata.
     *
     * @param ocrRequired true when the version is a scan / needs OCR
     * @param pageCount total pages
     * @param tableCount number of table grids detected
     * @param complexTableCount tables with many rows/cols or multi-level headers
     * @param figureCount figure/chart/image blocks
     * @param multiColumn true when ≥2 text columns on a sample page
     * @param denseText true when text coverage is high (financial reports)
     * @param fileRole M1 file_role (e.g. {@code technical_report}, {@code dpr})
     * @param missionCritical force higher accuracy (dense financials, board packs)
     */
    public record Signals(
            boolean ocrRequired,
            int pageCount,
            int tableCount,
            int complexTableCount,
            int figureCount,
            boolean multiColumn,
            boolean denseText,
            String fileRole,
            boolean missionCritical) {

        public Signals {
            pageCount = Math.max(0, pageCount);
            tableCount = Math.max(0, tableCount);
            complexTableCount = Math.max(0, complexTableCount);
            figureCount = Math.max(0, figureCount);
        }

        /** Plain digital text, no structure of interest. */
        public static Signals plainText() {
            return new Signals(false, 1, 0, 0, 0, false, false, "other", false);
        }
    }

    /**
     * Picks the tier for the given signals (official guide).
     *
     * @param signals document signals (required)
     * @return the recommended tier
     */
    public static ParseTier select(Signals signals) {
        if (signals == null) {
            return ParseTier.AGENTIC;
        }
        boolean scan = signals.ocrRequired();
        boolean hasFigures = signals.figureCount() > 0;
        boolean hasComplexTables = signals.complexTableCount() > 0;
        boolean hasTables = signals.tableCount() > 0;
        boolean financialDense = signals.denseText() || isFinancialRole(signals.fileRole());

        // Scans: agentic OCR; mission-critical / dense / complex → agentic_plus.
        if (scan) {
            return signals.missionCritical() || hasComplexTables || financialDense
                    ? ParseTier.AGENTIC_PLUS
                    : ParseTier.AGENTIC;
        }

        // Identity cards / short certificates (ePAN, GST REG-06, COI): seals, QR codes,
        // security print, and multi-region layout — plain "fast" OCR mangles them.
        if (isIdentityDoc(signals.fileRole()) && signals.pageCount() <= 3) {
            return signals.missionCritical() || hasComplexTables
                    ? ParseTier.AGENTIC_PLUS
                    : ParseTier.AGENTIC;
        }

        // Embedded charts/figures → agentic; dense + critical → agentic_plus.
        if (hasFigures) {
            return signals.missionCritical() || financialDense || hasComplexTables
                    ? ParseTier.AGENTIC_PLUS
                    : ParseTier.AGENTIC;
        }

        // Dense financial or complex tables → highest accuracy.
        if (hasComplexTables || signals.missionCritical() || (financialDense && hasTables)) {
            return ParseTier.AGENTIC_PLUS;
        }

        // Multi-column layouts → agentic.
        if (signals.multiColumn()) {
            return ParseTier.AGENTIC;
        }

        // Simple tables, mostly text, single column → cost_effective.
        if (hasTables) {
            return ParseTier.COST_EFFECTIVE;
        }

        // Dense multi-page text (legal AOA/MOA, long certificates) → cost_effective
        // for clean markdown; plain short text stays fast.
        if (signals.denseText() && signals.pageCount() >= 3) {
            return ParseTier.COST_EFFECTIVE;
        }

        // Plain text, single column, no tables → fast.
        return ParseTier.FAST;
    }

    /**
     * Convenience selector used when only a few signals are known.
     *
     * @param ocrRequired scan flag
     * @param tableCount table count
     * @param multiColumn multi-column flag
     * @param figureCount figure count
     * @return recommended tier
     */
    public static ParseTier select(boolean ocrRequired, int tableCount,
            boolean multiColumn, int figureCount) {
        return select(new Signals(ocrRequired, 1, tableCount, 0, figureCount,
                multiColumn, false, "other", false));
    }

    private static boolean isFinancialRole(String fileRole) {
        if (fileRole == null) {
            return false;
        }
        String role = fileRole.toLowerCase(Locale.ROOT);
        return role.contains("dpr") || role.contains("technical_report")
                || role.contains("financial") || role.contains("cma")
                || role.contains("fm_");
    }

    private static boolean isIdentityDoc(String fileRole) {
        if (fileRole == null) {
            return false;
        }
        String role = fileRole.toLowerCase(Locale.ROOT);
        return role.contains("identity") || role.contains("certificate")
                || role.contains("license") || role.contains("licence");
    }
}
