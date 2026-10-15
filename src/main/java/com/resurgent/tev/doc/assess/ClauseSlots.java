package com.resurgent.tev.doc.assess;

import com.resurgent.tev.doc.db.ClauseRepository.SlotWrite;
import com.resurgent.tev.doc.extract.DateNormalizer;
import com.resurgent.tev.doc.extract.DurationNormalizer;
import com.resurgent.tev.doc.extract.MoneyNormalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalises a clause's key numbers with M2's normalisers (M3-08): a {@code 90 days}
 * notice period becomes a duration slot ({@code value_number} 90, {@code unit} days), a
 * penalty becomes money (currency + scale), a tenure a duration. The value is kept as
 * stated in {@code value_text} — normalisation never rewrites the clause.
 */
public final class ClauseSlots {

    private static final Set<String> DURATION_KEYS = Set.of(
            "notice_period", "notice", "tenure", "term", "lock_in", "duration", "period",
            "validity", "tenor");

    private static final Set<String> MONEY_KEYS = Set.of(
            "penalty", "penalties", "damages", "liquidated_damages", "compensation", "amount",
            "fee", "fees", "security_deposit", "deposit", "charge", "charges", "payment");

    private static final Set<String> DATE_KEYS = Set.of(
            "date", "effective_date", "expiry", "expiry_date", "start_date", "end_date",
            "deadline", "due_date");

    /** {@code ninety (90) days} — the figure stated alongside its written-out number. */
    private static final Pattern DURATION_FALLBACK = Pattern.compile(
            "(?i)(\\d+(?:\\.\\d+)?)\\)?\\s*(years?|yrs?|months?|mos?|days?|weeks?)");

    /** {@code Rs. 5,00,000} — the money figure inside a stated sentence. */
    private static final Pattern MONEY_FALLBACK = Pattern.compile(
            "(?i)(?:₹|rs\\.?|inr)\\s*[0-9][0-9,]*(?:\\.[0-9]+)?(?:\\s*(?:lakhs?|lacs?|crores?|millions?|billions?|thousands?|000s?|mn|cr))?");

    private ClauseSlots() {}

    /**
     * Normalises one stated slot value.
     *
     * @param key slot key as proposed by the model ({@code notice_period}/…)
     * @param rawValue the value exactly as written in the clause
     * @return the slot to persist, or empty when the key/value is blank
     */
    public static Optional<SlotWrite> normalize(String key, String rawValue) {
        if (key == null || key.isBlank() || rawValue == null || rawValue.isBlank()) {
            return Optional.empty();
        }
        String slotKey = normalizeKey(key);
        if (slotKey.isEmpty()) {
            return Optional.empty();
        }
        String value = rawValue.strip();
        if (DURATION_KEYS.contains(slotKey)) {
            DurationNormalizer.Result duration = DurationNormalizer.parse(value);
            if (!duration.ok()) {
                Matcher m = DURATION_FALLBACK.matcher(value);
                if (m.find()) {
                    duration = DurationNormalizer.parse(m.group(1) + " " + m.group(2));
                }
            }
            return Optional.of(new SlotWrite(slotKey, value,
                    duration.ok() ? duration.valueNumber() : null,
                    duration.ok() ? duration.unit() : null,
                    null, null, null));
        }
        if (MONEY_KEYS.contains(slotKey)) {
            MoneyNormalizer.Result money = MoneyNormalizer.parse(value);
            if (!money.ok()) {
                Matcher m = MONEY_FALLBACK.matcher(value);
                if (m.find()) {
                    money = MoneyNormalizer.parse(m.group());
                }
            }
            return Optional.of(new SlotWrite(slotKey, value,
                    money.ok() ? money.amount().doubleValue() : null,
                    null,
                    money.ok() ? money.currency() : null,
                    money.ok() && money.scale() != null ? money.scale().wireName() : null,
                    null));
        }
        if (DATE_KEYS.contains(slotKey)) {
            DateNormalizer.Result date = DateNormalizer.parse(value);
            return Optional.of(new SlotWrite(slotKey, value, null, null, null, null,
                    date.ok() ? date.valueDate() : null));
        }
        return Optional.of(new SlotWrite(slotKey, value, null, null, null, null, null));
    }

    /** Lowercase snake_case so {@code "Notice Period"} and {@code notice_period} agree. */
    static String normalizeKey(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }
}
