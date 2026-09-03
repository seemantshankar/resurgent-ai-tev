package com.resurgent.tev.parser.ingest;

import java.util.Locale;

/**
 * Safe formula normalization per ADR 0003 / 0013:
 * <ul>
 *   <li>Strip legacy {@code =+} only at the start.</li>
 *   <li>Collapse whitespace only outside string literals and quoted sheet names.</li>
 *   <li>Uppercase only outside those quoted regions ({@link Locale#ROOT}).</li>
 *   <li>Never alter quoted sheet names or quoted string constants, including
 *       doubled quote escapes.</li>
 * </ul>
 */
public final class FormulaNormalizer {

    private FormulaNormalizer() {
    }

    /**
     * Normalize a formula string. The input is expected without a leading
     * {@code =}; the output is also without a leading {@code =}.
     */
    public static String normalize(String formula) {
        if (formula == null || formula.isEmpty()) {
            return formula;
        }

        String work = formula;
        if (work.startsWith("=+")) {
            work = work.substring(2);
        } else if (work.startsWith("=")) {
            work = work.substring(1);
        }

        StringBuilder out = new StringBuilder(work.length());
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        boolean lastWasSpace = false;

        for (int i = 0; i < work.length(); i++) {
            char c = work.charAt(i);

            if (inDoubleQuote) {
                out.append(c);
                if (c == '"') {
                    if (i + 1 < work.length() && work.charAt(i + 1) == '"') {
                        out.append('"');
                        i++;
                    } else {
                        inDoubleQuote = false;
                    }
                }
                lastWasSpace = false;
                continue;
            }

            if (inSingleQuote) {
                out.append(c);
                if (c == '\'') {
                    if (i + 1 < work.length() && work.charAt(i + 1) == '\'') {
                        out.append('\'');
                        i++;
                    } else {
                        inSingleQuote = false;
                    }
                }
                lastWasSpace = false;
                continue;
            }

            if (c == '"') {
                inDoubleQuote = true;
                out.append(c);
                lastWasSpace = false;
                continue;
            }

            if (c == '\'') {
                inSingleQuote = true;
                out.append(c);
                lastWasSpace = false;
                continue;
            }

            if (Character.isWhitespace(c)) {
                if (!lastWasSpace) {
                    out.append(' ');
                }
                lastWasSpace = true;
                continue;
            }

            out.append(String.valueOf(c).toUpperCase(Locale.ROOT));
            lastWasSpace = false;
        }

        return out.toString().trim();
    }
}
