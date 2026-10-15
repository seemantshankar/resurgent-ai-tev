package com.resurgent.tev.doc.extract;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic money/scale parsing shared with Excel cell-reading scale rules
 * (Architecture §7.4). Multipliers: unit 1, thousand 1e3, lakh 1e5, million 1e6,
 * crore 1e7, billion 1e9. Never invents rate units.
 */
public final class MoneyNormalizer {

    /** Shared scale multipliers, identical to {@code CellScale}. */
    public enum Scale {
        UNIT(1d),
        THOUSAND(1_000d),
        LAKH(100_000d),
        MILLION(1_000_000d),
        CRORE(10_000_000d),
        BILLION(1_000_000_000d);

        private final double multiplier;

        Scale(double multiplier) {
            this.multiplier = multiplier;
        }

        public double multiplier() {
            return multiplier;
        }

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Scale fromWire(String value) {
            if (value == null || value.isBlank()) {
                return UNIT;
            }
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    // Currency markers are word-anchored the way the Excel side's KindTokens.CURRENCY
    // is: "corps" must not read as "Rs", and a bare "$" names no ISO currency
    // (HomeCurrency leaves it unresolved), so it is deliberately not a marker here.
    private static final Pattern INR_TOKEN = Pattern.compile(
            "(?i)(₹|\\brs\\.?|\\binr\\b|\\brupees?\\b)");
    private static final Pattern USD_TOKEN = Pattern.compile(
            "(?i)(\\busd\\b|\\bdollars?\\b)");
    private static final Pattern EUR_TOKEN = Pattern.compile(
            "(?i)(€|\\beur\\b|\\beuros?\\b)");
    private static final Pattern GBP_TOKEN = Pattern.compile(
            "(?i)(£|\\bgbp\\b|\\bpounds?\\b)");

    private static final Pattern MONEY = Pattern.compile(
            "(?i)(?:₹|[€£]|\\b(?:rs\\.?|inr|usd|eur|gbp|dollars?|pounds?))\\.?\\s*"
                    + "([0-9][0-9,]*(?:\\.[0-9]+)?)"
                    + "(?:\\s+(lakhs?|lacs?|crores?|millions?|billions?|thousands?|000s?|mn|cr))?");
    private static final Pattern SUFFIX_SCALE = Pattern.compile(
            "(?i)\\s+(lakhs?|lacs?|crores?|millions?|billions?|thousands?|000s?|mn|cr)\\b");
    private static final Pattern BARE_NUMBER = Pattern.compile(
            "^\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*$");

    private MoneyNormalizer() {}

    /**
     * Parses a money string into slots. Leaves {@code unit} empty (money has no rate unit);
     * rate/percent units are never invented here.
     *
     * <p>Scale follows the Excel {@code cell_reading} rule (V36): a scale is only
     * returned when the text itself writes a scale token ("12.5 lakhs"). A figure
     * that states no scale — with or without a currency marker — comes back with
     * null scale and null absolute amount; the document's stated-scale context
     * may label it later, and nothing is written as "unit" by default.
     *
     * @param raw source text (required)
     * @return normalized money, or empty when the text has no parseable amount
     */
    public static Result parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Result.empty();
        }
        String text = raw.trim();
        Matcher money = MONEY.matcher(text);
        if (money.find()) {
            BigDecimal amount = parseNumber(money.group(1));
            Scale scale = money.group(2) == null ? null : scaleFromToken(money.group(2));
            return build(amount, scale, detectCurrency(text), text);
        }
        Matcher suffix = SUFFIX_SCALE.matcher(text);
        if (suffix.find()) {
            String head = text.substring(0, suffix.start()).trim();
            Matcher bare = BARE_NUMBER.matcher(head);
            if (bare.matches()) {
                BigDecimal amount = parseNumber(bare.group(1));
                Scale scale = scaleFromToken(suffix.group(1));
                return build(amount, scale, detectCurrency(text), text);
            }
        }
        Matcher bare = BARE_NUMBER.matcher(text);
        if (bare.matches()) {
            return build(parseNumber(bare.group(1)), null, detectCurrency(text), text);
        }
        return Result.empty();
    }

    /**
     * Absolute amount = displayed figure × scale multiplier, as a plain decimal string.
     * {@code 12.5} lakhs → {@code "1250000"}.
     */
    public static String absoluteAmount(BigDecimal amount, Scale scale) {
        if (amount == null || scale == null) {
            return null;
        }
        BigDecimal factor = BigDecimal.valueOf(Math.round(scale.multiplier()));
        return amount.multiply(factor).stripTrailingZeros().toPlainString();
    }

    private static Result build(BigDecimal amount, Scale scale, String currency, String raw) {
        if (amount == null) {
            return Result.empty();
        }
        return new Result(amount, scale, currency, absoluteAmount(amount, scale), raw, true);
    }

    private static BigDecimal parseNumber(String digits) {
        try {
            return new BigDecimal(digits.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Adds the currencies one text names to {@code found} (stated markers only). */
    public static void collectCurrencies(String text, Set<String> found) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (INR_TOKEN.matcher(text).find()) {
            found.add("INR");
        }
        if (USD_TOKEN.matcher(text).find()) {
            found.add("USD");
        }
        if (EUR_TOKEN.matcher(text).find()) {
            found.add("EUR");
        }
        if (GBP_TOKEN.matcher(text).find()) {
            found.add("GBP");
        }
    }

    private static String detectCurrency(String text) {
        Set<String> found = new LinkedHashSet<>();
        collectCurrencies(text, found);
        return found.size() == 1 ? found.iterator().next() : null;
    }

    /**
     * Maps a written scale token ("lakhs", "cr", "000s") to its multiplier.
     * Callers pass only tokens the text actually states; unstated figures
     * never reach here (V36: nothing defaults them to unit).
     */
    static Scale scaleFromToken(String token) {
        if (token == null || token.isBlank()) {
            return Scale.UNIT;
        }
        String t = token.toLowerCase(Locale.ROOT);
        if (t.startsWith("lakh") || t.startsWith("lac")) {
            return Scale.LAKH;
        }
        if (t.startsWith("crore") || t.equals("cr")) {
            return Scale.CRORE;
        }
        if (t.startsWith("million") || t.equals("mn")) {
            return Scale.MILLION;
        }
        if (t.startsWith("billion")) {
            return Scale.BILLION;
        }
        if (t.startsWith("thousand") || t.startsWith("000")) {
            return Scale.THOUSAND;
        }
        return Scale.UNIT;
    }

    /**
     * Normalized money slots.
     *
     * @param amount displayed figure (not yet scaled)
     * @param scale named scale
     * @param currency ISO-like currency code when stated
     * @param absoluteAmount figure × scale multiplier
     * @param raw display string kept as {@code value_text}
     * @param ok true when a figure was parsed
     */
    public record Result(
            BigDecimal amount,
            Scale scale,
            String currency,
            String absoluteAmount,
            String raw,
            boolean ok) {

        public static Result empty() {
            return new Result(null, null, null, null, null, false);
        }

        /** Display string for {@code fact.value_text}. */
        public String valueText() {
            return raw;
        }
    }

    /**
     * Rounds a double to a plain string without scientific notation (absolute_amount).
     */
    public static String plain(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros()
                .toPlainString();
    }
}
