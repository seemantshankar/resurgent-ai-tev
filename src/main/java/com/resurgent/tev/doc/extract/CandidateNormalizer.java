package com.resurgent.tev.doc.extract;

/**
 * Applies deterministic normalizers to a {@link FactCandidate} before persistence.
 * The LLM is never the authority for {@code absolute_amount}.
 */
public final class CandidateNormalizer {

    private CandidateNormalizer() {}

    /**
     * Normalizes money/date/duration/percent slots in place according to
     * {@code valueKind}. Leaves rate {@code unit} empty when unstated.
     *
     * @param candidate candidate to normalize (required)
     * @return the same candidate for chaining
     */
    public static FactCandidate normalize(FactCandidate candidate) {
        switch (candidate.valueKind()) {
            case MONEY -> normalizeMoney(candidate);
            case DATE -> normalizeDate(candidate);
            case DURATION -> normalizeDuration(candidate);
            case RATE, PERCENT -> normalizeRate(candidate);
            default -> {
                // text / enum / boolean / quantity / list: leave slots as returned
            }
        }
        return candidate;
    }

    private static void normalizeMoney(FactCandidate c) {
        String raw = c.valueText() != null ? c.valueText() : "";
        MoneyNormalizer.Result r = MoneyNormalizer.parse(raw);
        if (!r.ok()) {
            return;
        }
        c.valueNumber(r.amount() == null ? null : r.amount().doubleValue());
        if (r.scale() != null) {
            c.scale(r.scale().wireName());
        }
        if (r.currency() != null) {
            c.currency(r.currency());
        }
        c.absoluteAmount(r.absoluteAmount());
        c.valueText(r.valueText() != null ? r.valueText() : raw);
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
        }
        c.valueText(r.valueText() != null ? r.valueText() : raw);
    }

    private static void normalizeRate(FactCandidate c) {
        String raw = c.valueText() != null ? c.valueText() : "";
        RateNormalizer.Result r = RateNormalizer.parse(raw);
        if (r.valueNumber() == null) {
            return;
        }
        c.valueNumber(r.valueNumber());
        // never invent a unit
        if (r.unitStated() && r.unit() != null) {
            c.unit(r.unit());
        }
        c.valueText(r.valueText() != null ? r.valueText() : raw);
    }
}
