package com.resurgent.tev.parser.ingest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Infers locked column roles and region unit/currency from headers, number
 * formats, and worksheet banners. File and sheet names never become positive
 * evidence; they can only force a review when they look like a unit guess.
 */
final class RegionSchemaInferencer {

    static final String SERIAL = "serial";
    static final String DESCRIPTION = "description";
    static final String QUANTITY = "quantity";
    static final String RATE = "rate";
    static final String AMOUNT = "amount";
    static final String PERIOD = "period";
    static final String OTHER = "other";

    static final String UNIT_RS = "rs";
    static final String UNIT_LAKH = "lakh";
    static final String UNIT_CRORE = "crore";
    static final String UNIT_UNKNOWN = "unknown";
    static final String CURRENCY_INR = "INR";
    static final String CURRENCY_UNKNOWN = "unknown";

    private static final double LABEL_CONF = 0.95;
    private static final double FORMAT_CONF = 0.8;
    private static final double WORKSHEET_CONF = 0.7;
    static final long LAKH_TO_RS = 100_000L;
    static final long CRORE_TO_RS = 10_000_000L;

    static BigDecimal rupees(BigDecimal amount, String unit, String currency) {
        if (amount == null || !CURRENCY_INR.equals(currency)) {
            return amount;
        }
        if (UNIT_LAKH.equals(unit)) {
            return amount.multiply(BigDecimal.valueOf(LAKH_TO_RS));
        }
        if (UNIT_CRORE.equals(unit)) {
            return amount.multiply(BigDecimal.valueOf(CRORE_TO_RS));
        }
        return amount;
    }

    private static final Pattern SERIAL_HEADER = Pattern.compile(
            "(?i)(?:^|\\s)(?:s\\.?\\s*no|sr\\.?\\s*no|sl\\.?\\s*no|sno|serial(?:\\s*no)?|#)(?:\\s|$)");
    private static final Pattern DESCRIPTION_HEADER = Pattern.compile(
            "(?i)particulars|description|details|name of work|account head|head of account|(?:^|\\s)item(?:s)?(?:\\s|$)");
    private static final Pattern QUANTITY_HEADER = Pattern.compile(
            "(?i)(?:^|\\s)(?:qty|quantity|nos\\.?|no of)(?:\\s|$)");
    private static final Pattern RATE_HEADER = Pattern.compile(
            "(?i)(?:unit\\s+rate|(?:^|\\s)rate(?:\\s|$))");
    private static final Pattern AMOUNT_HEADER = Pattern.compile(
            "(?i)(?:^|\\s)(?:amount|amt)(?:s)?(?:\\s|$|\\()");
    private static final Pattern VALUE_TOKEN = Pattern.compile("(?i)(?:^|\\s)value(?:s)?(?:\\s|$)");
    private static final Pattern BANNER = Pattern.compile(
            "(?i)(?:figures?|amounts?|values?).{0,24}(?:in|are)");
    private static final Pattern CRORE = Pattern.compile("(?i)crore|crores|(?:^|\\s|\"|')crs?(?:\\s|$|\"|')");
    private static final Pattern LAKH = Pattern.compile("(?i)lakh|lakhs|lacs?\\b");
    private static final Pattern INR = Pattern.compile("(?i)₹|inr|rupees?|(?:^|[^a-z])rs\\.?");
    private static final Pattern USD = Pattern.compile("(?i)\\busd\\b|us\\$");
    private static final Pattern EUR = Pattern.compile("(?i)\\beur\\b|euro|€");
    private static final Pattern GBP = Pattern.compile("(?i)\\bgbp\\b|pound|£");
    private static final Set<String> FOREIGN = Set.of("USD", "EUR", "GBP");

    record Result(
            List<Map<String, Object>> columns,
            String unit,
            double unitConf,
            String currency,
            double currencyConf,
            boolean needsReview,
            List<String> reviewReasons) {}

    Result infer(
            List<NormalizedCell> regionCells,
            RegionHeaderContext headers,
            List<NormalizedCell> sheetCells,
            String fileName,
            String sheetName) {
        Set<Integer> columns = new LinkedHashSet<>(headers.columnLabelsByColumn().keySet());
        int minRow = Integer.MAX_VALUE;
        int maxRow = 0;
        int minCol = Integer.MAX_VALUE;
        int maxCol = 0;
        for (NormalizedCell cell : regionCells) {
            columns.add(cell.colNum());
            minRow = Math.min(minRow, cell.rowNum());
            maxRow = Math.max(maxRow, cell.rowNum());
            minCol = Math.min(minCol, cell.colNum());
            maxCol = Math.max(maxCol, cell.colNum());
        }
        List<Integer> ordered = columns.stream().sorted().toList();

        List<Hint> labelHints = new ArrayList<>();
        for (int col : ordered) {
            Hint fromLabel = parse(headers.columnLabelsByColumn().getOrDefault(col, ""));
            if (fromLabel.unit() != null || fromLabel.currency() != null) {
                labelHints.add(fromLabel);
            }
        }
        for (NormalizedCell cell : regionCells) {
            String value = text(cell);
            if (!headers.headerRows().contains(cell.rowNum()) && !isBanner(value)) {
                continue;
            }
            Hint hint = parse(value);
            if (hint.unit() != null || hint.currency() != null) {
                labelHints.add(hint);
            }
        }

        List<Hint> formatHints = new ArrayList<>();
        for (NormalizedCell cell : regionCells) {
            if (headers.headerRows().contains(cell.rowNum()) || cell.numberFormat() == null) {
                continue;
            }
            Hint hint = parse(cell.numberFormat());
            if (hint.unit() != null || hint.currency() != null) {
                formatHints.add(hint);
            }
        }

        List<Hint> worksheetHints = new ArrayList<>();
        for (NormalizedCell cell : sheetCells) {
            boolean inside = cell.rowNum() >= minRow && cell.rowNum() <= maxRow
                    && cell.colNum() >= minCol && cell.colNum() <= maxCol;
            if (inside) {
                continue;
            }
            String value = text(cell);
            Hint hint = parse(value);
            if (hint.unit() == null && hint.currency() == null) {
                continue;
            }
            if (isBanner(value) || (value != null && value.length() <= 24)) {
                worksheetHints.add(hint);
            }
        }

        Resolved unit = resolve(labelHints, formatHints, worksheetHints, true);
        Resolved currency = resolve(labelHints, formatHints, worksheetHints, false);

        List<String> reviewReasons = new ArrayList<>();
        if (unit.conflict() || currency.conflict()) {
            reviewReasons.add("CONFLICT");
        }
        boolean nameHasGuess = parse(fileName).unit() != null || parse(fileName).currency() != null
                || parse(sheetName).unit() != null || parse(sheetName).currency() != null;
        if (UNIT_UNKNOWN.equals(unit.value()) && CURRENCY_UNKNOWN.equals(currency.value()) && nameHasGuess) {
            reviewReasons.add("NAME_ONLY");
        }
        if (FOREIGN.contains(currency.value())) {
            reviewReasons.add("FOREIGN_CURRENCY");
        }

        boolean normalize = !reviewReasons.contains("CONFLICT")
                && CURRENCY_INR.equals(currency.value())
                && unit.conf() >= FORMAT_CONF
                && (UNIT_LAKH.equals(unit.value()) || UNIT_CRORE.equals(unit.value()));
        Long factor = !normalize ? null
                : UNIT_LAKH.equals(unit.value()) ? LAKH_TO_RS : CRORE_TO_RS;
        String normalizedUnit = factor == null ? null : UNIT_RS;

        List<Map<String, Object>> schema = new ArrayList<>();
        for (int col : ordered) {
            schema.add(columnSchema(col, headers, regionCells, unit, currency, normalizedUnit, factor));
        }
        return new Result(schema, unit.value(), unit.conf(), currency.value(), currency.conf(),
                !reviewReasons.isEmpty(), List.copyOf(reviewReasons));
    }

    private Map<String, Object> columnSchema(
            int col,
            RegionHeaderContext headers,
            List<NormalizedCell> regionCells,
            Resolved unit,
            Resolved currency,
            String normalizedUnit,
            Long factor) {
        String name = columnTitle(col, headers, regionCells);
        String role;
        List<String> reasons = new ArrayList<>();
        double conf;
        if (headers.periodAxisByColumn().containsKey(columnLetter(col))) {
            role = PERIOD;
            conf = LABEL_CONF;
            reasons.add("HEADER_PERIOD");
        } else {
            Set<String> matches = roleMatches(name);
            if (matches.size() == 1) {
                role = matches.iterator().next();
                conf = LABEL_CONF;
                reasons.add("HEADER_" + role.toUpperCase(Locale.ROOT));
            } else if (matches.size() > 1) {
                role = OTHER;
                conf = 0.4;
                reasons.add("AMBIGUOUS_HEADER");
            } else if (name.isBlank()) {
                role = OTHER;
                conf = 0.2;
                reasons.add("MISSING_HEADER");
            } else {
                role = OTHER;
                conf = 0.3;
                reasons.add("UNRECOGNIZED_HEADER");
            }
        }
        String type = columnType(col, headers.headerRows(), regionCells);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("col", col);
        json.put("name", name);
        json.put("type", type);
        json.put("role", role);
        json.put("conf", conf);
        json.put("reasons", reasons);
        if (AMOUNT.equals(role)) {
            if (!UNIT_UNKNOWN.equals(unit.value())) {
                json.put("unit", unit.value());
            }
            if (!CURRENCY_UNKNOWN.equals(currency.value())) {
                json.put("currency", currency.value());
            }
            if (normalizedUnit != null) {
                json.put("normalizedUnit", normalizedUnit);
                json.put("normalizeFactor", factor);
            }
        }
        return json;
    }

    /**
     * HeaderAnalyzer may keep only the last stacked header row. Role inference
     * also reads every label above the first numeric row so merged/stacked
     * titles such as Amount / Rs. Lakh still classify.
     */
    private static String columnTitle(int col, RegionHeaderContext headers, List<NormalizedCell> regionCells) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        String analyzed = headers.columnLabelsByColumn().getOrDefault(col, "");
        if (!analyzed.isBlank()) {
            for (String part : analyzed.split("\\s*/\\s*")) {
                if (!part.isBlank()) {
                    parts.add(part.trim());
                }
            }
        }
        int firstNumericRow = Integer.MAX_VALUE;
        for (NormalizedCell cell : regionCells) {
            if (cell.numericValue() != null && !cell.isMergedParticipant()) {
                firstNumericRow = Math.min(firstNumericRow, cell.rowNum());
            }
        }
        for (NormalizedCell cell : regionCells) {
            if (cell.colNum() != col || cell.rowNum() >= firstNumericRow || cell.isMergedParticipant()) {
                continue;
            }
            String value = text(cell);
            if (value != null && !value.isBlank()) {
                parts.add(value.trim());
            }
        }
        return String.join(" / ", parts);
    }

    private static Set<String> roleMatches(String name) {
        String normalized = name == null ? "" : name;
        Set<String> matches = new LinkedHashSet<>();
        if (SERIAL_HEADER.matcher(normalized).find()) {
            matches.add(SERIAL);
        }
        if (DESCRIPTION_HEADER.matcher(normalized).find()) {
            matches.add(DESCRIPTION);
        }
        if (QUANTITY_HEADER.matcher(normalized).find()) {
            matches.add(QUANTITY);
        }
        if (RATE_HEADER.matcher(normalized).find()) {
            matches.add(RATE);
        }
        if (AMOUNT_HEADER.matcher(normalized).find()) {
            matches.add(AMOUNT);
        }
        if (VALUE_TOKEN.matcher(normalized).find() && !matches.contains(AMOUNT)) {
            // "Value" is not a locked role. Alone it stays other; with another
            // locked role it makes the header ambiguous.
            if (!matches.isEmpty()) {
                matches.add(OTHER);
            }
        }
        return matches;
    }

    private static String columnType(int col, List<Integer> headerRows, List<NormalizedCell> regionCells) {
        for (NormalizedCell cell : regionCells) {
            if (cell.colNum() != col || headerRows.contains(cell.rowNum()) || cell.isMergedParticipant()) {
                continue;
            }
            if (cell.numericValue() != null) {
                return "number";
            }
        }
        return "text";
    }

    private static Resolved resolve(
            List<Hint> labels, List<Hint> formats, List<Hint> worksheet, boolean unit) {
        if (hasValue(labels, unit)) {
            return unique(labels, unit, LABEL_CONF);
        }
        if (hasValue(formats, unit)) {
            return unique(formats, unit, FORMAT_CONF);
        }
        if (hasValue(worksheet, unit)) {
            return unique(worksheet, unit, WORKSHEET_CONF);
        }
        return new Resolved(unit ? UNIT_UNKNOWN : CURRENCY_UNKNOWN, 0);
    }

    private static boolean hasValue(List<Hint> hints, boolean unit) {
        for (Hint hint : hints) {
            if ((unit ? hint.unit() : hint.currency()) != null) {
                return true;
            }
        }
        return false;
    }

    private static Resolved unique(List<Hint> hints, boolean unit, double conf) {
        Set<String> values = new LinkedHashSet<>();
        for (Hint hint : hints) {
            String value = unit ? hint.unit() : hint.currency();
            if (value != null) {
                values.add(value);
            }
        }
        if (values.size() > 1) {
            return new Resolved(unit ? UNIT_UNKNOWN : CURRENCY_UNKNOWN, 0, true);
        }
        if (values.isEmpty()) {
            return new Resolved(unit ? UNIT_UNKNOWN : CURRENCY_UNKNOWN, 0);
        }
        return new Resolved(values.iterator().next(), conf);
    }

    private static boolean isBanner(String text) {
        return text != null && BANNER.matcher(text).find();
    }

    static Hint parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Hint.EMPTY;
        }
        String text = raw;
        String unit = null;
        String currency = null;
        if (CRORE.matcher(text).find()) {
            unit = UNIT_CRORE;
        } else if (LAKH.matcher(text).find()) {
            unit = UNIT_LAKH;
        }
        if (INR.matcher(text).find()) {
            currency = CURRENCY_INR;
            if (unit == null) {
                unit = UNIT_RS;
            }
        }
        if (USD.matcher(text).find() && !text.contains("₹")) {
            if (currency == null) {
                currency = "USD";
            }
        }
        if (EUR.matcher(text).find()) {
            currency = "EUR";
        }
        if (GBP.matcher(text).find()) {
            currency = "GBP";
        }
        return new Hint(unit, currency);
    }

    private static String text(NormalizedCell cell) {
        if (cell.displayValue() != null && !cell.displayValue().isBlank()) {
            return cell.displayValue();
        }
        return cell.textValue();
    }

    private static String columnLetter(int column) {
        StringBuilder name = new StringBuilder();
        for (int value = column; value > 0; value = (value - 1) / 26) {
            name.append((char) ('A' + (value - 1) % 26));
        }
        return name.reverse().toString();
    }

    record Hint(String unit, String currency) {
        static final Hint EMPTY = new Hint(null, null);
    }

    private record Resolved(String value, double conf, boolean conflict) {
        Resolved(String value, double conf) {
            this(value, conf, false);
        }
    }
}
