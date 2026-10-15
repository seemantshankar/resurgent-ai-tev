package com.resurgent.tev.doc.assess;

import com.resurgent.tev.doc.db.AssessmentRepository;
import com.resurgent.tev.doc.db.AssessmentRepository.PageText;
import com.resurgent.tev.doc.extract.DateNormalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic first pass for a document's stated date: scans the layout text for the
 * formulaic phrasings a date kind appears in ("executed on …", "issued on …") and takes
 * the first date expression {@link DateNormalizer} accepts. The LLM assists only when
 * this finds nothing, so the assessment works offline for the common documents.
 */
public final class DocumentDateScanner {

    /** Phrasings per date kind that introduce the document's date. */
    private static final Map<String, List<String>> ANCHORS = Map.of(
            "execution", List.of("executed", "execution"),
            "signing", List.of("signed", "signing"),
            "issue", List.of("issued", "issue"),
            "report", List.of("report date", "dated"),
            "offer", List.of("valid"),
            "date", List.of("dated", "date"));

    private static final String MONTH = "(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?"
            + "|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?"
            + "|Nov(?:ember)?|Dec(?:ember)?)";

    /** Date expressions a line may state: "12 March 2024", "March 2025", "12/03/2024", … */
    private static final Pattern DATE_EXPR = Pattern.compile("(?i)("
            + "FY\\s*\\d{4}\\s*[-/]\\s*\\d{2,4}"
            + "|\\d{4}-\\d{2}-\\d{2}"
            + "|\\d{1,2}(?:st|nd|rd|th)?\\s+(?:of\\s+)?" + MONTH + "\\s*,?\\s+\\d{4}"
            + "|" + MONTH + "\\s+\\d{1,2}(?:st|nd|rd|th)?\\s*,?\\s+\\d{4}"
            + "|\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{4}"
            + "|" + MONTH + "\\s*,?\\s+\\d{4}"
            + ")");

    private DocumentDateScanner() {}

    /**
     * Scans a document's pages for a stated date of the given kind.
     *
     * @param kind pack-declared date kind ({@code execution}/…), null falls back to
     *     the generic {@code date} anchors
     * @param pages page texts in reading order
     * @return the first stated date found, or empty
     */
    public static Optional<StatedDate> scan(String kind, List<PageText> pages) {
        List<String> anchors = ANCHORS.getOrDefault(
                kind == null || kind.isBlank() ? "date" : kind.toLowerCase(Locale.ROOT),
                ANCHORS.get("date"));
        for (PageText page : pages) {
            String[] lines = page.text().split("\\R");
            for (int i = 0; i < lines.length; i++) {
                if (!hasAnchor(lines[i], anchors)) {
                    continue;
                }
                StatedDate found = dateIn(lines[i], page.pageIndex());
                if (found != null) {
                    return Optional.of(found);
                }
                // The anchor and its date may sit on consecutive lines
                // ("Date of execution:" / "12 March 2024").
                if (i + 1 < lines.length) {
                    found = dateIn(lines[i + 1], page.pageIndex());
                    if (found != null) {
                        return Optional.of(found);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static boolean hasAnchor(String line, List<String> anchors) {
        String lower = line.toLowerCase(Locale.ROOT);
        for (String anchor : anchors) {
            if (lower.contains(anchor)) {
                return true;
            }
        }
        return false;
    }

    private static StatedDate dateIn(String line, int pageIndex) {
        Matcher matcher = DATE_EXPR.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        String dateText = matcher.group(1).trim();
        if (!DateNormalizer.parse(dateText).ok()) {
            return null;
        }
        return new StatedDate(AssessmentRepository.normalizeWhitespace(line), dateText, pageIndex);
    }
}
