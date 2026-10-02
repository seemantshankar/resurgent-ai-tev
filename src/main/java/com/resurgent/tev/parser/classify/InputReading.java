package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Types a hardcoded number or a constant-only formula from its row label, and
 * from the column header only when that row is an entity name (ADR 0020).
 * A formula divisor states scale before either label; a label's scale word beats the
 * region or sheet statement. Money none of them scales is left unstated. Percent is stated.
 */
final class InputReading {

    private static final Pattern PERIOD = Pattern.compile(
            "(?i)(?:\\b(?:year|yr|fy)\\b|\\b(?:19|20)\\d{2}\\b)");
    private static final Pattern EXPLICIT_COUNT = Pattern.compile(
            "(?i)(?:\\bno\\.?\\s*of\\b|\\bnumber\\s+of\\b|\\bqty\\b|\\bquantity\\b|\\bnos\\.?\\b|\\bcount\\b)");
    private static final Pattern EXPLICIT_QUANTITY = Pattern.compile("(?i)(?:\\barea\\b|\\bcapacity\\b)");
    private static final Pattern MEASURED = Pattern.compile("(?i)\\b(sq\\.?\\s*ft|sqft|sqm)\\b");
    private static final Pattern COLUMN_NOUN = Pattern.compile(
            "(?i)\\b(keys?|rooms?|beds?|days?|months?|guests?)\\b");
    private static final Pattern PER = Pattern.compile("(?i)\\bper\\b|/");
    private static final String CONFLICT = "\u0000";

    private InputReading() {}

    /**
     * {@code statedScale} is the scale the cell's region or sheet states for its money
     * (for example {@code Rs. In Lacs} in the title). It applies to money inputs whose own
     * labels and formula carry no scale word. Money with none of the three gets a fresh
     * unknown from {@code unknowns}, not {@code unit}: nothing said it was rupees.
     */
    static ReadingOutcome type(
            InterpretationCellView cell,
            String rowLabel,
            String columnHeader,
            String numberFormat,
            String homeCurrency,
            CellScale statedScale,
            UnstatedScales unknowns) {
        Label row = Label.read(rowLabel, false);
        boolean entity = rowLabel != null && !rowLabel.isBlank() && row.kind == null && !periodOnly(rowLabel);
        Label column = entity ? Label.read(columnHeader, true) : Label.none();
        Label chosen = row.kind != null ? row : column;
        Label surface = Label.fromSurface(numberFormat, cell.displayValue());

        if (chosen.kind != null && surface.kind != null && !chosen.kind.equals(surface.kind)) {
            return ReadingOutcome.refused(ReadingOutcome.KIND_CONFLICT);
        }
        String kind = chosen.kind != null ? chosen.kind : surface.kind;
        if (kind == null) {
            return ReadingOutcome.untyped();
        }

        CellScale divisor = InterpretationEvidenceResolver.formulaDivisorScale(cell.formulaText());
        CellScale scale = divisor != null
                ? divisor
                : chosen.scale != null
                        ? chosen.scale
                        : ReadingOutcome.MONEY.equals(kind) ? statedScale : CellScale.UNIT;
        if (ReadingOutcome.PERCENT.equals(kind)) {
            scale = CellScale.UNIT;
        }

        String unit = "";
        if (ReadingOutcome.RATE.equals(kind)
                || ReadingOutcome.QUANTITY.equals(kind)
                || ReadingOutcome.COUNT.equals(kind)) {
            unit = chosen.unit == null ? "" : chosen.unit;
        }

        String currency = currency(chosen, surface, homeCurrency);
        if (CONFLICT.equals(currency)) {
            return ReadingOutcome.refused(ReadingOutcome.KIND_CONFLICT);
        }
        if (!ReadingOutcome.MONEY.equals(kind) && !ReadingOutcome.RATE.equals(kind)) {
            currency = "";
        }
        if (scale == null) {
            return ReadingOutcome.unstated(unit, currency, ReadingOutcome.INPUT, unknowns.fresh(cell.cellId()));
        }
        return ReadingOutcome.typed(kind, scale, unit, currency, ReadingOutcome.INPUT);
    }

    private static String currency(Label label, Label surface, String homeCurrency) {
        Set<String> isos = new LinkedHashSet<>();
        boolean bareDollar = label.bareDollar || surface.bareDollar;
        if (label.currency != null) {
            isos.add(label.currency);
        }
        if (surface.currency != null) {
            isos.add(surface.currency);
        }
        if (isos.size() > 1) {
            return CONFLICT;
        }
        if (isos.size() == 1) {
            return isos.iterator().next();
        }
        if (bareDollar) {
            return homeCurrency == null ? "" : homeCurrency;
        }
        return "";
    }

    private static boolean periodOnly(String text) {
        if (text == null || !PERIOD.matcher(text).find()) {
            return false;
        }
        return !KindTokens.PERCENT_TOKEN.matcher(text).find()
                && !KindTokens.CURRENCY.matcher(text).find()
                && !KindTokens.MONEY_TOKEN.matcher(text).find()
                && !EXPLICIT_COUNT.matcher(text).find()
                && !EXPLICIT_QUANTITY.matcher(text).find()
                && !MEASURED.matcher(text).find();
    }

    private record Label(String kind, CellScale scale, String unit, String currency, boolean bareDollar) {

        static Label none() {
            return new Label(null, null, null, null, false);
        }

        static Label read(String text, boolean column) {
            if (text == null || text.isBlank() || periodOnly(text)) {
                return none();
            }
            String measured = measuredUnit(text);
            boolean moneyish = KindTokens.MONEY_TOKEN.matcher(text).find()
                    || KindTokens.CURRENCY.matcher(text).find();
            String kind;
            String unit = null;
            if (KindTokens.PERCENT_TOKEN.matcher(text).find()) {
                kind = ReadingOutcome.PERCENT;
            } else if (measured != null && (moneyish || PER.matcher(text).find())) {
                kind = ReadingOutcome.RATE;
                unit = measured;
            } else if (EXPLICIT_COUNT.matcher(text).find()) {
                kind = ReadingOutcome.COUNT;
                unit = countUnit(text);
            } else if (measured != null || EXPLICIT_QUANTITY.matcher(text).find()) {
                kind = ReadingOutcome.QUANTITY;
                unit = measured;
            } else if (column && COLUMN_NOUN.matcher(text).find()) {
                kind = ReadingOutcome.QUANTITY;
                unit = nounUnit(text);
            } else if (moneyish) {
                kind = ReadingOutcome.MONEY;
            } else {
                return none();
            }
            return new Label(kind, scaleOf(text), unit, explicitCurrency(text), bareDollar(text));
        }

        static Label fromSurface(String numberFormat, String display) {
            boolean percent = numberFormat != null && numberFormat.indexOf('%') >= 0;
            String currency = firstExplicit(numberFormat, display);
            boolean bare = currency == null && (bareDollar(numberFormat) || bareDollar(display));
            String kind = null;
            if (percent) {
                kind = ReadingOutcome.PERCENT;
            } else if (currency != null || bare) {
                kind = ReadingOutcome.MONEY;
            }
            return new Label(kind, null, null, currency, bare);
        }

        private static CellScale scaleOf(String text) {
            CellScale named = CellScale.fromText(text);
            if (named != null) {
                return named;
            }
            if (SheetScaleStatement.statesRupees(text)) {
                return CellScale.UNIT;
            }
            return null;
        }

        private static String measuredUnit(String text) {
            Matcher matcher = MEASURED.matcher(text);
            if (!matcher.find()) {
                return null;
            }
            return KindTokens.normalizeUnit(matcher.group());
        }

        private static String countUnit(String text) {
            Matcher matcher = Pattern.compile("(?i)\\bnos?\\.?\\b").matcher(text);
            if (matcher.find()) {
                return "nos";
            }
            return "";
        }

        private static String nounUnit(String text) {
            Matcher matcher = COLUMN_NOUN.matcher(text);
            if (!matcher.find()) {
                return "";
            }
            return KindTokens.normalizeUnit(matcher.group());
        }

        private static String explicitCurrency(String text) {
            return firstExplicit(text);
        }

        private static String firstExplicit(String... texts) {
            if (texts == null) {
                return null;
            }
            for (String text : texts) {
                if (text == null || text.isBlank()) {
                    continue;
                }
                Matcher matcher = KindTokens.CURRENCY.matcher(text);
                while (matcher.find()) {
                    String iso = KindTokens.normalizeCurrency(matcher.group());
                    if (iso != null) {
                        return iso.toUpperCase(Locale.ROOT);
                    }
                }
            }
            return null;
        }

        private static boolean bareDollar(String text) {
            if (text == null || text.indexOf('$') < 0) {
                return false;
            }
            return firstExplicit(text) == null;
        }
    }
}
