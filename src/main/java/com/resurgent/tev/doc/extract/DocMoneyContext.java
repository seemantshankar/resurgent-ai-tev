package com.resurgent.tev.doc.extract;

import com.resurgent.tev.parser.classify.HomeCurrency;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
public record DocMoneyContext(
        String currency,
        MoneyNormalizer.Scale scale,
        ScaleStatement scaleStatement,
        CurrencyStatement currencyStatement) {

    public static final DocMoneyContext EMPTY = new DocMoneyContext(null, null, null, null);

    public record ScaleStatement(
            MoneyNormalizer.Scale scale, String sourceText, int pageIndex, Citation citation) {}

    public record CurrencyStatement(
            String currency, String sourceText, int pageIndex, Citation citation) {}

    /**
     * Constructs money context with detected currency and scale.
     */
    public DocMoneyContext(String currency, MoneyNormalizer.Scale scale) {
        this(currency, scale, null, null);
    }

    /** "(Amount in Rs.)", "(in lakhs)", "all figures in crores", "Project cost (Rs. in Lakhs)". */
    private static final Pattern STATED_SCALE = Pattern.compile(
            "(?i)(?:\\b(?:amounts?|figures?|values?|rupees?|costs?|rs\\.?)\\s+(?:are\\s+)?in\\s+"
                    + "|[\\[(]\\s*(?:(?:amounts?|figures?|values?|costs?|rs\\.?|inr)\\s+(?:are\\s+)?in\\s+|in\\s+)"
                    + "|\\brs\\.?\\s+in\\s+)"
                    + "(rs\\.?|rupees?|thousands?|000s?|lakhs?|lacs?|crores?|millions?|billions?)"
                    + "[)\\]]?"
                    + "(?![a-z])");

    private static final Pattern INR_MARKER = Pattern.compile("(?i)(₹|\\brs\\.?|\\binr\\b|\\brupees?\\b)");
    private static final Pattern USD_MARKER = Pattern.compile("(?i)(\\busd\\b|\\bdollars?\\b|\\$)");
    private static final Pattern EUR_MARKER = Pattern.compile("(?i)(€|\\beur\\b|\\beuros?\\b)");
    private static final Pattern GBP_MARKER = Pattern.compile("(?i)(£|\\bgbp\\b|\\bpounds?\\b)");

    /** GSTIN: 2-digit state code + PAN + entity code + Z + check digit. */
    private static final Pattern GSTIN = Pattern.compile(
            "(?<![A-Z0-9])[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][0-9A-Z]Z[0-9A-Z](?![A-Z0-9])");

    /** PAN: five letters, four digits, one letter. */
    private static final Pattern PAN = Pattern.compile(
            "(?<![A-Z])[A-Z]{5}[0-9]{4}[A-Z](?![A-Z])");

    /**
     * Detects currency and scale from page text representations.
     */
    public static DocMoneyContext from(List<String> pageTexts) {
        Map<String, CurrencyStatement> currencyStatements = new LinkedHashMap<>();
        Map<MoneyNormalizer.Scale, ScaleStatement> scaleStatements = new LinkedHashMap<>();
        if (pageTexts != null) {
            for (int i = 0; i < pageTexts.size(); i++) {
                String page = pageTexts.get(i);
                if (page == null || page.isBlank()) {
                    continue;
                }
                int pageIndex = i + 1;
                Set<String> pageCurrencies = new LinkedHashSet<>();
                MoneyNormalizer.collectCurrencies(page, pageCurrencies);
                HomeCurrency.collectInto(pageCurrencies, page);
                for (String curr : pageCurrencies) {
                    String token = findCurrencyToken(page, curr);
                    currencyStatements.putIfAbsent(curr,
                            new CurrencyStatement(curr, token != null ? token : curr, pageIndex,
                                    Citation.of(pageIndex, token != null ? token : curr)));
                }
                if (GSTIN.matcher(page).find() || PAN.matcher(page).find()) {
                    String token = findCurrencyToken(page, "INR");
                    currencyStatements.putIfAbsent("INR",
                            new CurrencyStatement("INR", token != null ? token : "INR", pageIndex,
                                    Citation.of(pageIndex, token != null ? token : "INR")));
                }
                Matcher statement = STATED_SCALE.matcher(page);
                while (statement.find()) {
                    String matchedText = statement.group(0).trim();
                    MoneyNormalizer.Scale s = MoneyNormalizer.scaleFromToken(statement.group(1));
                    if (s != null) {
                        scaleStatements.putIfAbsent(s,
                                new ScaleStatement(s, matchedText, pageIndex, Citation.of(pageIndex, matchedText)));
                    }
                }
            }
        }
        String resolvedCurrency = currencyStatements.size() == 1 ? currencyStatements.keySet().iterator().next() : null;
        MoneyNormalizer.Scale resolvedScale = scaleStatements.size() == 1 ? scaleStatements.keySet().iterator().next() : null;
        return new DocMoneyContext(
                resolvedCurrency,
                resolvedScale,
                resolvedScale != null ? scaleStatements.get(resolvedScale) : null,
                resolvedCurrency != null ? currencyStatements.get(resolvedCurrency) : null);
    }

    /**
     * Finds specific currency token representation within text.
     */
    static String findCurrencyToken(String text, String currency) {
        if (text == null) {
            return currency;
        }
        Pattern p = switch (currency) {
            case "INR" -> INR_MARKER;
            case "USD" -> USD_MARKER;
            case "EUR" -> EUR_MARKER;
            case "GBP" -> GBP_MARKER;
            default -> null;
        };
        if (p != null) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                return m.group(1);
            }
        }
        return currency;
    }

    /**
     * Detects currency and scale from layout blocks of document version.
     */
    public static DocMoneyContext fromBlocks(Connection conn, long documentVersionId) {
        if (conn == null) {
            return EMPTY;
        }
        try {
            Map<Integer, StringBuilder> pages = new LinkedHashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT b.text_raw, p.page_index FROM block b"
                            + " JOIN page p ON p.page_id = b.page_id"
                            + " WHERE p.document_version_id = ? ORDER BY p.page_index, b.ordinal")) {
                ps.setLong(1, documentVersionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int pageIndex = rs.getInt("page_index");
                        String text = rs.getString("text_raw");
                        if (text != null && !text.isBlank()) {
                            pages.computeIfAbsent(pageIndex, k -> new StringBuilder()).append('\n').append(text);
                        }
                    }
                }
            }
            if (pages.isEmpty()) {
                return EMPTY;
            }
            int maxPage = pages.keySet().stream().max(Integer::compareTo).orElse(0);
            List<String> pageTexts = new ArrayList<>();
            for (int i = 1; i <= maxPage; i++) {
                StringBuilder sb = pages.get(i);
                pageTexts.add(sb == null ? "" : sb.toString());
            }
            return from(pageTexts);
        } catch (SQLException e) {
            return EMPTY;
        }
    }

    public boolean isEmpty() {
        return currency == null && scale == null;
    }
}
