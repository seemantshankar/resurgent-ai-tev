package com.resurgent.tev.parser.ingest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Pure evaluator for constant formulas (formulas without cell/range references).
 */
public final class ConstantFormulaEvaluator {

    public record EvalResult(
            BigDecimal numericValue,
            boolean isError,
            String errorType
    ) {}

    private ConstantFormulaEvaluator() {}

    /**
     * Evaluates a constant formula (numeric-literal arithmetic only, no references —
     * §9). Returns {@code null} when no evaluation was possible or applicable at all:
     * not a constant formula (has reference tokens), blank, or unparseable — as opposed
     * to "evaluated to null". A genuine error literal or div-by-zero instead returns an
     * actual {@code EvalResult} with {@code isError=true}.
     */
    public static EvalResult evaluate(String formulaText, List<FormulaToken> refTokens) {
        if (refTokens != null && !refTokens.isEmpty()) {
            return null; // Not a constant formula
        }
        if (formulaText == null || formulaText.isBlank()) {
            return null;
        }

        String clean = formulaText.startsWith("=") ? formulaText.substring(1).trim() : formulaText.trim();

        if (isErrorLiteral(clean)) {
            return new EvalResult(null, true, clean.toUpperCase());
        }

        try {
            double value = new ExpressionParser(clean).parse();
            return new EvalResult(BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros(), false, null);
        } catch (ArithmeticException e) {
            return new EvalResult(null, true, "#DIV/0!");
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isErrorLiteral(String text) {
        String u = text.toUpperCase();
        return u.equals("#REF!") || u.equals("#VALUE!") || u.equals("#N/A") ||
               u.equals("#DIV/0!") || u.equals("#NAME?") || u.equals("#NUM!") || u.equals("#NULL!");
    }

    private static final class ExpressionParser {
        private final String str;
        private int pos = -1;
        private int ch;

        ExpressionParser(String str) {
            this.str = str;
        }

        private void nextChar() {
            ch = (++pos < str.length()) ? str.charAt(pos) : -1;
        }

        private boolean eat(int charToEat) {
            while (ch == ' ') nextChar();
            if (ch == charToEat) {
                nextChar();
                return true;
            }
            return false;
        }

        double parse() {
            nextChar();
            double x = parseExpression();
            if (pos < str.length()) throw new RuntimeException("Unexpected char: " + (char) ch);
            return x;
        }

        private double parseExpression() {
            double x = parseTerm();
            for (;;) {
                if (eat('+')) x += parseTerm();
                else if (eat('-')) x -= parseTerm();
                else return x;
            }
        }

        private double parseTerm() {
            double x = parseFactor();
            for (;;) {
                if (eat('*')) x *= parseFactor();
                else if (eat('/')) {
                    double div = parseFactor();
                    if (div == 0.0) throw new ArithmeticException("Division by zero");
                    x /= div;
                } else return x;
            }
        }

        private double parseFactor() {
            if (eat('+')) return +parseFactor();
            if (eat('-')) return -parseFactor();

            double x;
            int startPos = this.pos;
            if (eat('(')) {
                x = parseExpression();
                if (!eat(')')) throw new RuntimeException("Missing ')'");
            } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                x = Double.parseDouble(str.substring(startPos, this.pos));
            } else {
                throw new RuntimeException("Unexpected char: " + (char) ch);
            }

            if (eat('^')) x = Math.pow(x, parseFactor());

            return x;
        }
    }
}
