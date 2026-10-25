package com.resurgent.tev.doc.query;

import java.util.List;

/**
 * One stored answer to a {@link ReportQuestion}. Each answer names the store it
 * came from and the file pages an analyst can open. When two answers state
 * different amounts, both stay and {@code disagrees} is true on each of them.
 *
 * @param store {@code fact}, {@code document_type}, {@code passage}, {@code clause},
 *     or {@code layout_text}
 * @param text the stored wording, not a field name or a pack code
 * @param filePage first file page (1-based {@code page.page_index})
 * @param disagrees true when this answer states an amount and another answer
 *     states a different one
 * @param filePages every file page the answer was read from, in reading order
 */
public record ReportAnswer(String store, String text, int filePage, boolean disagrees,
        List<Integer> filePages) {

    public ReportAnswer {
        filePages = filePages == null || filePages.isEmpty()
                ? List.of(filePage) : List.copyOf(filePages);
    }

    /** An answer read from one file page. */
    public ReportAnswer(String store, String text, int filePage, boolean disagrees) {
        this(store, text, filePage, disagrees, List.of(filePage));
    }

    /** An answer that is not part of a visible disagreement. */
    public ReportAnswer(String store, String text, int filePage) {
        this(store, text, filePage, false);
    }

    /** A current fact. */
    public static final String FACT = "fact";

    /** The document's type. */
    public static final String DOCUMENT_TYPE = "document_type";

    /** A passage. */
    public static final String PASSAGE = "passage";

    /** A clause. */
    public static final String CLAUSE = "clause";

    /** The words on a page ({@code block} text). */
    public static final String LAYOUT_TEXT = "layout_text";
}
