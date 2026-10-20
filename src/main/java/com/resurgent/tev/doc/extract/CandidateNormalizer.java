package com.resurgent.tev.doc.extract;

import com.resurgent.tev.parser.classify.KindTokens;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies deterministic normalizers to a {@link FactCandidate} before persistence.
 * The LLM is never the authority for {@code absolute_amount}.
 */
public final class CandidateNormalizer {

    private static final Pattern SCIENTIFIC = Pattern.compile(
            "(?i)^\\s*[+-]?\\d+(\\.\\d+)?E[+-]?\\d+\\s*$");

    private static final Pattern INR_MARKER = Pattern.compile("(?i)(₹|\\brs\\.?|\\binr\\b|\\brupees?\\b)");
    private static final Pattern USD_MARKER = Pattern.compile("(?i)(\\busd\\b|\\bdollars?\\b|\\$)");
    private static final Pattern EUR_MARKER = Pattern.compile("(?i)(€|\\beur\\b|\\beuros?\\b)");
    private static final Pattern GBP_MARKER = Pattern.compile("(?i)(£|\\bgbp\\b|\\bpounds?\\b)");

    private static final Pattern SCALE_MARKER = Pattern.compile(
            "(?i)\\b(lakhs?|lacs?|crores?|millions?|billions?|thousands?|000s?|mn|cr)\\b");

    private CandidateNormalizer() {}

    /**
     * Normalizes money/date/duration/percent slots in place according to
     * {@code valueKind}. Leaves rate {@code unit} empty when unstated.
     *
     * @param candidate candidate to normalize (required)
     * @return the same candidate for chaining
     */
    public static FactCandidate normalize(FactCandidate candidate) {
        return normalize(candidate, DocMoneyContext.EMPTY);
    }

    /**
     * Same normalization with the document's money context: a money figure that
     * states no currency or scale takes the document's resolved home currency and
     * stated scale — the Excel treatment (figures stay numbers; context labels them;
     * nothing is invented when the context is unresolved).
     *
     * @param candidate candidate to normalize (required)
     * @param context document money context ({@code null} = {@link DocMoneyContext.EMPTY})
     * @return the same candidate for chaining
     */
    public static FactCandidate normalize(FactCandidate candidate, DocMoneyContext context) {
        DocMoneyContext ctx = context == null ? DocMoneyContext.EMPTY : context;
        switch (candidate.valueKind()) {
            case MONEY -> normalizeMoney(candidate, ctx);
            case DATE -> normalizeDate(candidate);
            case DURATION -> normalizeDuration(candidate);
            case RATE, PERCENT -> normalizeRate(candidate);
            case QUANTITY -> normalizeQuantity(candidate);
            default -> {
                // text / enum / boolean / list: leave slots as returned
            }
        }
        return candidate;
    }

    private static void normalizeMoney(FactCandidate c, DocMoneyContext ctx) {
        String raw = c.valueText() != null ? c.valueText() : "";
        MoneyNormalizer.Result r = MoneyNormalizer.parse(raw);
        if (!r.ok() && c.valueNumber() != null) {
            // numeric cell: the figure rides in value_number (Excel reads those as
            // numbers too); repair scientific-notation text, keep prose text as-is
            String plain = MoneyNormalizer.plain(c.valueNumber());
            MoneyNormalizer.Result fromNumber = MoneyNormalizer.parse(plain);
            if (fromNumber.ok()) {
                if (SCIENTIFIC.matcher(raw).matches()) {
                    c.valueText(plain);
                }
                r = fromNumber;
            }
        }
        if (!r.ok()) {
            return;
        }
        c.valueNumber(r.amount() == null ? null : r.amount().doubleValue());
        // V36 mirror: a scale exists only when the text writes one or the
        // document states one; an unlabeled figure keeps scale and
        // absolute_amount empty.
        MoneyNormalizer.Scale scale = r.scale() != null ? r.scale() : ctx.scale();
        if (scale != null) {
            c.scale(scale.wireName());
            c.absoluteAmount(MoneyNormalizer.absoluteAmount(r.amount(), scale));
            if (r.scale() != null) {
                String token = findMatching(SCALE_MARKER, raw, scale.wireName());
                c.addBasisEvidence("scale", token, scale.wireName(), tokenCitation(c, token));
            } else if (ctx.scaleStatement() != null) {
                c.addBasisEvidence("scale", ctx.scaleStatement().sourceText(), scale.wireName(),
                        ctx.scaleStatement().citation());
            }
        }
        if (r.currency() != null) {
            c.currency(r.currency());
            String token = findCurrencyMarker(raw, r.currency());
            c.addBasisEvidence("currency", token, r.currency(), tokenCitation(c, token));
        }
        if ((c.currency() == null || c.currency().isBlank()) && ctx.currency() != null) {
            c.currency(ctx.currency());
            if (ctx.currencyStatement() != null) {
                c.addBasisEvidence("currency", ctx.currencyStatement().sourceText(), ctx.currency(),
                        ctx.currencyStatement().citation());
            }
        }
        if (c.valueText() == null || c.valueText().isBlank()) {
            c.valueText(r.valueText() != null ? r.valueText() : raw);
        }
    }

    private static void normalizeDate(FactCandidate c) {
        String raw = c.valueText() != null ? c.valueText() : "";
        DateNormalizer.Result r = DateNormalizer.parse(raw);
        if (!r.ok()) {
            return;
        }
        c.valueDate(r.valueDate());
        c.valueText(r.valueText() != null ? r.valueText() : raw);
    }

    private static void normalizeDuration(FactCandidate c) {
        String raw = c.valueText() != null ? c.valueText() : "";
        DurationNormalizer.Result r = DurationNormalizer.parse(raw);
        if (r.valueNumber() == null) {
            return;
        }
        c.valueNumber(r.valueNumber());
        if (r.unit() != null) {
            c.unit(r.unit());
            c.addBasisEvidence("unit", r.unit(), r.unit(), tokenCitation(c, r.unit()));
        }
        // Excel types durations as quantity at unit scale
        c.scale(MoneyNormalizer.Scale.UNIT.wireName());
        c.valueText(r.valueText() != null ? r.valueText() : raw);
    }

    private static void normalizeRate(FactCandidate c) {
        String raw = c.valueText() != null ? c.valueText() : "";
        RateNormalizer.Result r = RateNormalizer.parse(raw);
        if (r.valueNumber() == null) {
            return;
        }
        // Excel treatment: a written percent is stored as a fraction with no
        // unit ("12.5%" → 0.125, unit ''); "p.a." keeps its qualifier because
        // Excel has no annualised-percent counterpart.
        boolean percentKind = c.valueKind() == ValueKind.PERCENT
                && r.unit() != null && r.unit().startsWith("percent");
        if (percentKind) {
            c.valueNumber(r.valueNumber() / 100.0);
            c.unit("percent".equals(r.unit()) ? "" : KindTokens.normalizeUnit(r.unit()));
        } else {
            c.valueNumber(r.valueNumber());
            // never invent a unit
            if (r.unitStated() && r.unit() != null) {
                String norm = KindTokens.normalizeUnit(r.unit());
                c.unit(norm);
                c.addBasisEvidence("unit", r.unit(), norm, tokenCitation(c, r.unit()));
            }
        }
        // Excel forces scale 'unit' on every non-money reading (InputReading)
        c.scale(MoneyNormalizer.Scale.UNIT.wireName());
        c.valueText(r.valueText() != null ? r.valueText() : raw);
    }

    private static void normalizeQuantity(FactCandidate c) {
        String raw = c.valueText() != null ? c.valueText() : "";
        QuantityNormalizer.Result q = QuantityNormalizer.parse(raw);
        if (q.valueNumber() != null) {
            c.valueNumber(q.valueNumber());
        }
        String verbatimUnit = null;
        if (q.unit() != null) {
            verbatimUnit = q.verbatimUnit() != null ? q.verbatimUnit() : q.unit();
            c.unit(q.unit());
        } else if (c.unit() != null && !c.unit().isBlank()) {
            verbatimUnit = c.unit();
            // schema-declared units arrive mixed case; store the canonical key
            c.unit(KindTokens.normalizeUnit(c.unit()));
        }
        if (c.unit() != null && !c.unit().isBlank()) {
            String token = verbatimUnit != null ? verbatimUnit : c.unit();
            c.addBasisEvidence("unit", token, c.unit(), tokenCitation(c, token));
        }
        c.scale(MoneyNormalizer.Scale.UNIT.wireName());
    }

    private static Citation tokenCitation(FactCandidate c, String token) {
        if (!c.citations().isEmpty()) {
            Citation primary = c.citations().get(0);
            return Citation.of(primary.pageIndex(), token);
        }
        return null;
    }

    private static String findMatching(Pattern pattern, String text, String fallback) {
        if (text == null) {
            return fallback;
        }
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : fallback;
    }

    private static String findCurrencyMarker(String text, String fallback) {
        if (text == null) {
            return fallback;
        }
        for (Pattern p : List.of(INR_MARKER, USD_MARKER, EUR_MARKER, GBP_MARKER)) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                return m.group(1);
            }
        }
        return fallback;
    }
}
