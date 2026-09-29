package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.InterpretationCellView;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One home currency for a parse run, read from address text on any sheet.
 * A country, an Indian state, or a 6-digit PIN names India. Two different
 * currencies leave the home currency unresolved, so a bare {@code $} stays unresolved.
 */
final class HomeCurrency {

    private static final Pattern INDIA = Pattern.compile(
            "(?i)\\b(?:india|andhra pradesh|arunachal pradesh|assam|bihar|chhattisgarh|goa|"
                    + "gujarat|haryana|himachal pradesh|jharkhand|karnataka|kerala|"
                    + "madhya pradesh|maharashtra|manipur|meghalaya|mizoram|nagaland|"
                    + "odisha|orissa|punjab|rajasthan|sikkim|tamil nadu|telangana|tripura|"
                    + "uttar pradesh|uttarakhand|west bengal|delhi|new delhi|"
                    + "jammu and kashmir|ladakh|chandigarh|puducherry|pondicherry|"
                    + "andaman|nicobar|lakshadweep|dadra|nagar haveli|daman|diu)\\b");

    private static final Pattern PIN = Pattern.compile("(?i)(?:\\bPIN[-\\s:]*)?(?<!\\d)(\\d{6})(?!\\d)");

    private static final Pattern AUSTRALIA = Pattern.compile("(?i)\\baustralia\\b");
    private static final Pattern CANADA = Pattern.compile("(?i)\\bcanada\\b");
    private static final Pattern UNITED_STATES = Pattern.compile("(?i)\\b(?:united states|u\\.s\\.a\\.?|usa)\\b");
    private static final Pattern SAUDI = Pattern.compile("(?i)\\bsaudi arabia\\b");

    private HomeCurrency() {}

    static String resolve(List<InterpretationCellView> cells) {
        Set<String> found = new LinkedHashSet<>();
        for (InterpretationCellView cell : cells) {
            if (cell.numericValue() != null && !cell.numericValue().isBlank()) {
                continue;
            }
            collect(found, cell.textValue());
            if (!sameText(cell.textValue(), cell.displayValue())) {
                collect(found, cell.displayValue());
            }
        }
        if (found.size() == 1) {
            return found.iterator().next();
        }
        return null;
    }

    private static boolean sameText(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equals(right);
    }

    private static void collect(Set<String> found, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (INDIA.matcher(text).find() || PIN.matcher(text).find()) {
            found.add("INR");
        }
        if (AUSTRALIA.matcher(text).find()) {
            found.add("AUD");
        }
        if (CANADA.matcher(text).find()) {
            found.add("CAD");
        }
        if (UNITED_STATES.matcher(text).find()) {
            found.add("USD");
        }
        if (SAUDI.matcher(text).find()) {
            found.add("SAR");
        }
        // A cell that is only a currency name ("INR", "Rs.") is a place signal.
        // A longer label such as "(in USD)" types that cell; it is not a second country.
        var matcher = KindTokens.CURRENCY.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            String iso = KindTokens.normalizeCurrency(token);
            if (iso == null) {
                continue;
            }
            String rest = text.replace(token, "").replaceAll("[\\s.₹$€£(),]", "");
            if (rest.isEmpty()) {
                found.add(iso.toUpperCase(Locale.ROOT));
            }
        }
    }
}
