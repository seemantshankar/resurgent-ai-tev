package com.resurgent.tev.doc.layout;

/** Word-level text run with its box (document analog of a formula token / cell span). */
public record LayoutSpan(String text, BBox bbox) {}
