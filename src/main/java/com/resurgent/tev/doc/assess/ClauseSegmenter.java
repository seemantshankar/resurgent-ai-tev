package com.resurgent.tev.doc.assess;

import com.resurgent.tev.doc.db.AssessmentRepository.PageText;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits stored page text into clause segments along their headings (M3-08). Clause
 * reading is heading-driven: a segment starts at a heading line and runs until the next
 * heading (or the end). Text before the first heading is preamble and is ignored.
 * Everything is deterministic — the model never invents verbatim text.
 */
public final class ClauseSegmenter {

    /** {@code 12. TERMINATION} / {@code 12 TERMINATION} / {@code 7.2 FORCE MAJEURE}. */
    private static final Pattern NUMBERED_CAPS = Pattern.compile(
            "^\\s*(\\d+(?:\\.\\d+)*)[.)]?\\s+([A-Z][A-Z0-9 ,&'()/\\-–—]{2,})\\s*$");

    /** {@code Article 7: Force Majeure} / {@code Clause 5.2 Termination} / {@code Section 3}. */
    private static final Pattern ARTICLE = Pattern.compile(
            "(?i)^\\s*(?:article|clause|section)\\s+(\\d+(?:\\.\\d+)*)\\s*[:.\\-–—]?\\s*(\\S.*)$");

    /** A bare all-caps heading line: {@code FORCE MAJEURE}, {@code TERMINATION}. */
    private static final Pattern BARE_CAPS = Pattern.compile(
            "^\\s*([A-Z][A-Z0-9 ,&'()/\\-–—]{3,80})\\s*$");

    private static final int MAX_CAPS_HEADING_WORDS = 8;

    private ClauseSegmenter() {}

    /**
     * One clause segment.
     *
     * @param heading the heading as written (e.g. {@code 12. TERMINATION})
     * @param text heading plus body, verbatim from the stored text
     * @param pageStart first page the segment appears on
     * @param pageEnd last page the segment appears on
     */
    public record Segment(String heading, String text, int pageStart, int pageEnd) {}

    /**
     * Segments the pages of one document version along clause headings.
     *
     * @param pages stored page texts in page order (layout blocks, else parse markdown)
     * @return the segments in reading order; empty when the document has no heading lines
     */
    public static List<Segment> segment(List<PageText> pages) {
        List<Segment> segments = new ArrayList<>();
        String heading = null;
        StringBuilder body = new StringBuilder();
        int startPage = 0;
        int endPage = 0;

        for (PageText page : pages) {
            for (String rawLine : page.text().split("\\R")) {
                String line = rawLine.strip();
                if (line.isEmpty()) {
                    continue;
                }
                String detected = headingOf(line);
                if (detected != null) {
                    if (heading != null && !body.isEmpty()) {
                        segments.add(new Segment(heading, heading + "\n" + body, startPage, endPage));
                    }
                    heading = detected;
                    body.setLength(0);
                    startPage = page.pageIndex();
                    endPage = page.pageIndex();
                    continue;
                }
                if (heading != null) {
                    if (!body.isEmpty()) {
                        body.append('\n');
                    }
                    body.append(line);
                    endPage = page.pageIndex();
                }
            }
        }
        if (heading != null && !body.isEmpty()) {
            segments.add(new Segment(heading, heading + "\n" + body, startPage, endPage));
        }
        return List.copyOf(segments);
    }

    /** The heading line as written, or null when the line does not start a clause. */
    static String headingOf(String line) {
        Matcher numbered = NUMBERED_CAPS.matcher(line);
        if (numbered.matches()) {
            return line.strip();
        }
        Matcher article = ARTICLE.matcher(line);
        if (article.matches() && !article.group(2).isBlank()) {
            return line.strip();
        }
        Matcher caps = BARE_CAPS.matcher(line);
        if (caps.matches()) {
            String title = caps.group(1).strip();
            int words = title.split("\\s+").length;
            if (words <= MAX_CAPS_HEADING_WORDS && !title.chars().allMatch(Character::isDigit)) {
                return line.strip();
            }
        }
        return null;
    }
}
