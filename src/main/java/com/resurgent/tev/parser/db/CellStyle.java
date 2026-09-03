package com.resurgent.tev.parser.db;

/**
 * Shared cell appearance flyweight (ADR 0013). Colour fields hold {@code #rrggbb}
 * when available, else a palette index string, else null.
 */
public record CellStyle(
        Boolean isBold,
        String numberFormat,
        String fillFgColor,
        String fillPattern,
        String borderTopStyle,
        String borderTopColor,
        String borderRightStyle,
        String borderRightColor,
        String borderBottomStyle,
        String borderBottomColor,
        String borderLeftStyle,
        String borderLeftColor) {
}
