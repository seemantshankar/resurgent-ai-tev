package com.resurgent.tev.parser.classify;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one home for the label tokens that decide what a numeric cell is. The typing
 * stack ({@link LayerBAmountSupport}), the reporting evidence
 * ({@link InterpretationEvidenceResolver}) and the number-format reader
 * ({@link InputTyping}) all used to carry private copies of these patterns; a new
 * scale or currency term added to one silently missed the others. Each regex lives
 * here once, with its normalizer beside it.
 */
final class KindTokens {

    /** Currency and scale symbols: ₹, $, €, £, Rs., INR, USD, EUR. */
    static final Pattern CURRENCY = Pattern.compile(
            "(?i)(₹|\\binr\\b|\\brs\\.?\\b|\\busd\\b|\\beur\\b|\\$|€|£)");

    /** Measured nouns a column header may claim directly: sqft, Nos., keys, rooms. */
    static final Pattern UNIT = Pattern.compile(
            "(?i)\\b(sq\\.?\\s*ft|sqft|sqm|nos?\\.?|keys?|rooms?|%|percent)\\b");

    /** Any counted quantity cue, column or row: No. of, Qty, rooms, days, area. */
    static final Pattern QUANTITY_TOKEN = Pattern.compile(
            "(?i)(?:\\bno\\.?\\s*of\\b|\\bnumber\\s+of\\b|\\bqty\\b|\\bquantity\\b|\\bnos\\.?\\b|"
                    + "\\bunits?\\b|\\brooms?\\b|\\bkeys\\b|\\bbeds?\\b|\\bdays?\\b|"
                    + "\\bmonths?\\b|\\byears?\\b|\\bsq\\.?\\s*ft\\b|\\bsqft\\b|\\bsqm\\b|"
                    + "\\barea\\b|\\bcapacity\\b|\\bcount\\b)");

    /** Percent is stated, never implied by a word: only % or "percent" counts. */
    static final Pattern PERCENT_TOKEN = Pattern.compile("(?i)(?:%|\\bpercent\\b)");

    /**
     * Money words plus currency symbols: amount, cost, capex, depreciation, lac,
     * crore, Rs., INR, ₹, $, … The broad cue the kind reader uses; the reporting
     * evidence uses the narrower {@link #CURRENCY} so prose never invents money.
     */
    static final Pattern MONEY_TOKEN = Pattern.compile(
            "(?i)(?:\\brs\\.?\\b|\\binr\\b|₹|\\$|€|£|amount|cost|price|value|fee|payment|"
                    + "capex|opex|expense|outlay|investment|lac|lakh|crore|less\\s*:|"
                    + "total\\s+cost|project\\s+cost|means\\s+of\\s+finance|"
                    + "depreciation|\\bdep\\.?\\b)");

    private KindTokens() {}

    /**
     * The display string names a currency (₹, $, Rs, INR, …), used by the kind
     * reader and by the number-format mask check so both agree on what counts.
     * Currency symbols only — money words stay in {@link #MONEY_TOKEN}, which the
     * kind reader already checks separately.
     */
    static boolean displayNamesCurrency(String display) {
        if (display == null || display.isBlank()) {
            return false;
        }
        return CURRENCY.matcher(display).find();
    }

    /** Canonical currency key: Rs./₹/INR → INR; $ stays unnormalized (not invented). */
    static String normalizeCurrency(String token) {
        String t = token.toLowerCase(Locale.ROOT).replace(".", "");
        if (t.contains("₹") || t.equals("inr") || t.equals("rs")) {
            return "INR";
        }
        if (t.equals("usd")) {
            return "USD";
        }
        if (t.contains("$")) {
            // Bare $ is USD/CAD/AUD/… — keep source cue without inventing a currency.
            return null;
        }
        if (t.contains("€") || t.equals("eur")) {
            return "EUR";
        }
        if (t.contains("£")) {
            return "GBP";
        }
        // Ambiguous bare symbol already handled by explicit tokens; leave null if unknown.
        return null;
    }

    /** Canonical unit key so case/plural spellings of one unit agree on the wire. */
    static String normalizeUnit(String token) {
        String t = token.toLowerCase(Locale.ROOT).replace(".", "").replace(" ", "");
        if (t.equals("%") || t.equals("percent")) {
            return "percent";
        }
        if (t.startsWith("sq")) {
            return t;
        }
        if (t.equals("nos") || t.equals("no")) {
            return "nos";
        }
        if (t.equals("rooms") || t.equals("room")) {
            return "room";
        }
        if (t.equals("keys") || t.equals("key")) {
            return "key";
        }
        return t;
    }
}
