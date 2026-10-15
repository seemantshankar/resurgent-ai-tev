package com.resurgent.tev.doc.pack;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One chapter-topic declaration inside a prose pack (M3, issue #139): which data
 * points to take from a chapter of this topic, and the heading wording that assigns
 * a passage to the topic. Pack data, not code — a hotel DPR and a solar DPR each
 * carry their own topics.
 *
 * @param topic stable topic key stored on {@code passage.topic} (e.g. {@code land})
 * @param headingTerms lower-cased heading phrases that assign a passage to the topic
 * @param fields data-point keys to take from the chapter: pack field keys or row
 *     table names declared by the same pack
 */
public record PackPassage(String topic, List<String> headingTerms, List<String> fields) {

    public PackPassage {
        Objects.requireNonNull(topic, "topic");
        if (topic.isBlank()) {
            throw new IllegalArgumentException("passage topic must be non-blank");
        }
        headingTerms = headingTerms == null ? List.of() : headingTerms.stream()
                .filter(Objects::nonNull)
                .map(term -> term.trim().toLowerCase(Locale.ROOT))
                .filter(term -> !term.isEmpty())
                .toList();
        fields = fields == null ? List.of() : List.copyOf(fields);
        if (headingTerms.isEmpty()) {
            throw new IllegalArgumentException(
                    "passage topic '" + topic + "' needs at least one heading term");
        }
    }
}
