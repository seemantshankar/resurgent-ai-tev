package com.resurgent.tev.doc.assess;

/**
 * A date stated in a document: the quote it was read from, the date expression itself,
 * and the 1-based page the quote is anchored to (null when the quote could not be
 * anchored in the layout).
 *
 * @param quote verbatim text the date was read from (whitespace-normalized when
 *     anchored to a layout block)
 * @param dateText the date expression exactly as stated ("12 March 2024", "March 2025")
 * @param pageIndex page the quote sits on, or null when not anchored
 */
public record StatedDate(String quote, String dateText, Integer pageIndex) {}
