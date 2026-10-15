package com.resurgent.tev.doc.extract;

import com.resurgent.tev.parser.classify.HomeCurrency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One document's money context: the home currency and stated scale read from its
 * parsed page text. This is the document-side mirror of the Excel pipeline's rules —
 * {@code HomeCurrency} resolves one home currency for a run and refuses a second,
 * and a sheet scale is taken only when the sheet states one. Figures stay numbers;
 * unstated slots are filled only when the document resolves exactly one answer,
 * and nothing is invented when pages conflict.
 *
 * <p>Region cues on scanned Indian statutory documents are sparse in prose, so
 * besides {@link HomeCurrency#collectInto} this also reads GSTIN and PAN formats,
 * which name India by construction.
 */
public record DocMoneyContext(String currency, MoneyNormalizer.Scale scale) {

    public static final DocMoneyContext EMPTY = new DocMoneyContext(null, null);

    /** "(Amount in Rs.)", "(in lakhs)", "all figures in crores". */
    private static final Pattern STATED_SCALE = Pattern.compile(
            "(?i)(?:\\b(?:amounts?|figures?|values?|rupees?|costs?)\\s+(?:are\\s+)?in\\s+"
                    + "|[\\[(]\\s*in\\s+)"
                    + "(rs\\.?|rupees?|thousands?|000s?|lakhs?|lacs?|crores?|millions?|billions?)"
                    + "(?![a-z])");

    /** GSTIN: 2-digit state code + PAN + entity code + Z + check digit. */
    private static final Pattern GSTIN = Pattern.compile(
            "(?<![A-Z0-9])[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][0-9A-Z]Z[0-9A-Z](?![A-Z0-9])");

    /** PAN: five letters, four digits, one letter. */
    private static final Pattern PAN = Pattern.compile(
            "(?<![A-Z])[A-Z]{5}[0-9]{4}[A-Z](?![A-Z])");

    public static DocMoneyContext from(List<String> pageTexts) {
        Set<String> currencies = new LinkedHashSet<>();
        Set<MoneyNormalizer.Scale> scales = new LinkedHashSet<>();
        if (pageTexts != null) {
            for (String page : pageTexts) {
                if (page == null || page.isBlank()) {
                    continue;
                }
                MoneyNormalizer.collectCurrencies(page, currencies);
                HomeCurrency.collectInto(currencies, page);
                if (GSTIN.matcher(page).find() || PAN.matcher(page).find()) {
                    currencies.add("INR");
                }
                Matcher statement = STATED_SCALE.matcher(page);
                while (statement.find()) {
                    scales.add(MoneyNormalizer.scaleFromToken(statement.group(1)));
                }
            }
        }
        return new DocMoneyContext(
                currencies.size() == 1 ? currencies.iterator().next() : null,
                scales.size() == 1 ? scales.iterator().next() : null);
    }

    public boolean isEmpty() {
        return currency == null && scale == null;
    }
}
