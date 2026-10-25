package com.resurgent.tev.doc.query;

/**
 * A report question asked in ordinary words while a TEV or debt report is being
 * written. The caller does not pass a field name or a pack code.
 *
 * @param mandateId mandate the question is about
 * @param words the question in ordinary words
 */
public record ReportQuestion(long mandateId, String words) {

    public ReportQuestion {
        if (words == null || words.isBlank()) {
            throw new IllegalArgumentException("a report question needs words");
        }
    }
}
