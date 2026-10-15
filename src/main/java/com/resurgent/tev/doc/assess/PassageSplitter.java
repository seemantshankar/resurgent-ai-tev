package com.resurgent.tev.doc.assess;

import com.resurgent.tev.doc.db.PassageRepository.ParsePage;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackPassage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits stored parse markdown into chapter passages: a passage starts at a markdown
 * heading ({@code # …}) and runs to the next heading, across page boundaries. Lines
 * before the first heading are preamble and never become a passage.
 */
final class PassageSplitter {

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+?)\\s*$");

    private PassageSplitter() {}

    /**
     * One chapter passage.
     *
     * @param heading the heading text without its markdown hashes
     * @param pageFrom page carrying the heading
     * @param pageTo last page carrying the passage's text ({@code pageFrom} when empty)
     * @param text the passage's stored text lines, newline-joined
     */
    record Passage(String heading, int pageFrom, int pageTo, String text) {}

    /**
     * Splits pages into passages in reading order.
     *
     * @param pages stored parse pages in page order
     * @return the passages, possibly empty
     */
    static List<Passage> split(List<ParsePage> pages) {
        List<Passage> passages = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        String heading = null;
        int pageFrom = 0;
        int pageTo = 0;
        for (ParsePage page : pages) {
            String pageText = page.text() == null ? "" : page.text();
            for (String line : pageText.split("\r?\n", -1)) {
                Matcher matcher = HEADING.matcher(line);
                if (matcher.matches()) {
                    flush(passages, heading, pageFrom, pageTo, text);
                    heading = matcher.group(1).trim();
                    pageFrom = page.pageIndex();
                    pageTo = page.pageIndex();
                    text.setLength(0);
                    continue;
                }
                if (heading == null) {
                    continue; // preamble before the first heading
                }
                String trimmed = line.strip();
                if (!trimmed.isEmpty()) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(trimmed);
                    pageTo = page.pageIndex();
                }
            }
        }
        flush(passages, heading, pageFrom, pageTo, text);
        return passages;
    }

    /**
     * The pack passage spec whose heading terms cover this heading, or null when no
     * declared spec's terms occur in it. First declared spec wins, deterministically.
     */
    static PackPassage specFor(DocTypePack pack, String heading) {
        String lower = heading.toLowerCase(Locale.ROOT);
        for (PackPassage spec : pack.passages()) {
            for (String term : spec.headingTerms()) {
                if (lower.contains(term)) {
                    return spec;
                }
            }
        }
        return null;
    }

    /**
     * The pack topic whose chapter fields cover this heading, or null when no declared
     * spec's heading terms occur in it. First declared spec wins, deterministically.
     */
    static String topicFor(DocTypePack pack, String heading) {
        PackPassage spec = specFor(pack, heading);
        return spec == null ? null : spec.topic();
    }

    private static void flush(List<Passage> passages, String heading, int pageFrom,
            int pageTo, StringBuilder text) {
        if (heading != null && !heading.isEmpty()) {
            passages.add(new Passage(heading, pageFrom, pageTo, text.toString()));
        }
    }
}
