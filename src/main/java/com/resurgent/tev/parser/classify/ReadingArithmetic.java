package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.InterpretationCellView;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Types a formula from its precedents and the operators in {@code formula_text}.
 * A product stays unresolved until every factor is typed. A conflict is a refusal.
 * Money of unstated scale never conflicts here: the operators record what they imply
 * about it in {@link UnstatedScales}, and a stated scale wins over an unstated one.
 */
final class ReadingArithmetic {

    private ReadingArithmetic() {}

    static ReadingOutcome derive(
            String formula,
            long worksheetId,
            Set<Long> precedents,
            Set<Long> numericIds,
            Map<Long, InterpretationCellView> cells,
            Map<String, Long> sheetIds,
            Map<Long, ReadingOutcome> settled,
            UnstatedScales unknowns,
            long cellId) {
        if (formula == null || formula.isBlank()) {
            return ReadingOutcome.refused(ReadingOutcome.UNTYPABLE);
        }
        try {
            Parser parser = new Parser(formula, worksheetId, precedents, numericIds, cells, sheetIds, settled,
                    new Links(unknowns, cellId));
            Val value = parser.parseExpression();
            if (!parser.finished() || value.group) {
                return ReadingOutcome.refused(ReadingOutcome.UNTYPABLE);
            }
            return value.toOutcome();
        } catch (IllegalArgumentException e) {
            return ReadingOutcome.refused(ReadingOutcome.UNTYPABLE);
        }
    }

    private static final class Parser {
        private final String text;
        private final long worksheetId;
        private final Set<Long> precedents;
        private final Set<Long> numericIds;
        private final Map<Long, InterpretationCellView> cells;
        private final Map<String, Long> sheetIds;
        private final Map<Long, ReadingOutcome> settled;
        private final Links links;
        private int index;

        Parser(
                String formula,
                long worksheetId,
                Set<Long> precedents,
                Set<Long> numericIds,
                Map<Long, InterpretationCellView> cells,
                Map<String, Long> sheetIds,
                Map<Long, ReadingOutcome> settled,
                Links links) {
            String body = formula.trim();
            if (body.startsWith("=")) {
                body = body.substring(1);
            }
            this.text = body;
            this.worksheetId = worksheetId;
            this.precedents = precedents;
            this.numericIds = numericIds;
            this.cells = cells;
            this.sheetIds = sheetIds;
            this.settled = settled;
            this.links = links;
        }

        boolean finished() {
            skip();
            return index >= text.length();
        }

        Val parseExpression() {
            Val value = parseTerm();
            while (true) {
                char op = peek();
                if (op != '+' && op != '-') {
                    return value;
                }
                index++;
                Val right = parseTerm();
                value = op == '+' ? add(links, value, right) : add(links, value, negate(right));
            }
        }

        private Val parseTerm() {
            Val value = parseFactor();
            while (true) {
                char op = peek();
                if (op != '*' && op != '/') {
                    return value;
                }
                index++;
                Val right = parseFactor();
                value = op == '*' ? multiply(links, value, right) : divide(links, value, right);
            }
        }

        private Val parseFactor() {
            char op = peek();
            if (op == '+') {
                index++;
                return parseFactor();
            }
            if (op == '-') {
                index++;
                return negate(parseFactor());
            }
            return parsePower();
        }

        private Val parsePower() {
            Val base = parsePrimary();
            if (peek() != '^') {
                return base;
            }
            index++;
            Val exponent = parseFactor();
            if (base.number && exponent.number) {
                try {
                    return Val.number(base.numeric.pow(exponent.numeric.intValueExact()));
                } catch (ArithmeticException e) {
                    return Val.refused(ReadingOutcome.UNTYPABLE);
                }
            }
            return Val.refused(ReadingOutcome.UNTYPABLE);
        }

        private Val parsePrimary() {
            char c = peek();
            if (c == '(') {
                index++;
                Val inner = parseExpression();
                if (peek() != ')') {
                    throw new IllegalArgumentException("unclosed");
                }
                index++;
                return inner;
            }
            if (c == '"' || c == '\'') {
                return parseQuotedOrRef();
            }
            if (isDigit(c) || c == '.') {
                return Val.number(readNumber());
            }
            if (c == '$' || isLetter(c)) {
                return parseRefOrCall();
            }
            throw new IllegalArgumentException("token");
        }

        private Val parseQuotedOrRef() {
            if (peek() == '"') {
                throw new IllegalArgumentException("string");
            }
            String sheet = readQuotedSheet();
            if (peek() != '!') {
                throw new IllegalArgumentException("sheet");
            }
            index++;
            return parseRefRest(sheet);
        }

        private Val parseRefOrCall() {
            if (peek() == '$') {
                return parseRefRest(null);
            }
            int saved = index;
            String ident = readIdent();
            if (peek() == '(') {
                return function(ident);
            }
            if (peek() == '!') {
                index++;
                return parseRefRest(ident);
            }
            index = saved;
            return parseRefRest(null);
        }

        private Val function(String name) {
            if (peek() != '(') {
                throw new IllegalArgumentException("call");
            }
            index++;
            List<Val> args = new ArrayList<>();
            if (peek() != ')') {
                args.add(parseExpression());
                while (peek() == ',') {
                    index++;
                    args.add(parseExpression());
                }
            }
            if (peek() != ')') {
                throw new IllegalArgumentException("call");
            }
            index++;
            return apply(links, name, args);
        }

        private Val parseRefRest(String sheetName) {
            Ref start = readRef(sheetName);
            if (peek() == ':') {
                index++;
                Ref end = readRef(sheetName);
                return range(start, end);
            }
            return lookup(start);
        }

        private Ref readRef(String inheritedSheet) {
            String sheet = inheritedSheet;
            if (peek() == '\'') {
                sheet = readQuotedSheet();
                if (peek() != '!') {
                    throw new IllegalArgumentException("sheet");
                }
                index++;
            } else if (isLetter(peek())) {
                int saved = index;
                String ident = readIdent();
                if (peek() == '!') {
                    index++;
                    sheet = ident;
                } else {
                    index = saved;
                }
            }
            if (peek() == '$') {
                index++;
            }
            int col = readColumn();
            if (peek() == '$') {
                index++;
            }
            int row = readRow();
            return new Ref(sheet, row, col);
        }

        private String readQuotedSheet() {
            if (peek() != '\'') {
                throw new IllegalArgumentException("quote");
            }
            index++;
            StringBuilder name = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == '\'' && index + 1 < text.length() && text.charAt(index + 1) == '\'') {
                    name.append('\'');
                    index += 2;
                    continue;
                }
                if (c == '\'') {
                    index++;
                    return name.toString();
                }
                name.append(c);
                index++;
            }
            throw new IllegalArgumentException("quote");
        }

        private String readIdent() {
            int start = index;
            if (!isLetter(peek()) && peek() != '_') {
                throw new IllegalArgumentException("ident");
            }
            index++;
            while (index < text.length()) {
                char c = text.charAt(index);
                if (!isLetter(c) && !isDigit(c) && c != '_') {
                    break;
                }
                index++;
            }
            return text.substring(start, index);
        }

        private int readColumn() {
            if (!isLetter(peek())) {
                throw new IllegalArgumentException("column");
            }
            int col = 0;
            while (isLetter(peek())) {
                col = col * 26 + (Character.toUpperCase(text.charAt(index)) - 'A' + 1);
                index++;
            }
            return col;
        }

        private int readRow() {
            if (!isDigit(peek())) {
                throw new IllegalArgumentException("row");
            }
            int row = 0;
            while (isDigit(peek())) {
                row = row * 10 + (text.charAt(index) - '0');
                index++;
            }
            return row;
        }

        private BigDecimal readNumber() {
            int start = index;
            while (isDigit(peek()) || peek() == '.') {
                index++;
            }
            if (peek() == 'e' || peek() == 'E') {
                index++;
                if (peek() == '+' || peek() == '-') {
                    index++;
                }
                while (isDigit(peek())) {
                    index++;
                }
            }
            return new BigDecimal(text.substring(start, index));
        }

        private Val lookup(Ref ref) {
            Long sheet = sheetId(ref.sheet);
            if (sheet == null) {
                return Val.refused(ReadingOutcome.UNTYPABLE);
            }
            InterpretationCellView found = find(sheet, ref.row, ref.col);
            if (found == null) {
                return Val.refused(ReadingOutcome.UNTYPABLE);
            }
            return fromSettled(found.cellId());
        }

        private Val range(Ref start, Ref end) {
            Long sheet = sheetId(start.sheet != null ? start.sheet : end.sheet);
            if (sheet == null || (end.sheet != null && start.sheet != null
                    && !start.sheet.equalsIgnoreCase(end.sheet))) {
                return Val.refused(ReadingOutcome.UNTYPABLE);
            }
            int rowLo = Math.min(start.row, end.row);
            int rowHi = Math.max(start.row, end.row);
            int colLo = Math.min(start.col, end.col);
            int colHi = Math.max(start.col, end.col);
            List<Val> members = new ArrayList<>();
            for (long id : precedents) {
                if (!numericIds.contains(id)) {
                    continue;
                }
                InterpretationCellView cell = cells.get(id);
                if (cell == null || cell.worksheetId() != sheet) {
                    continue;
                }
                if (cell.rowNum() < rowLo || cell.rowNum() > rowHi
                        || cell.colNum() < colLo || cell.colNum() > colHi) {
                    continue;
                }
                members.add(fromSettled(id));
            }
            if (members.isEmpty()) {
                return Val.refused(ReadingOutcome.UNTYPABLE);
            }
            return Val.group(members);
        }

        private Long sheetId(String name) {
            if (name == null || name.isBlank()) {
                return worksheetId;
            }
            Long exact = sheetIds.get(name.toLowerCase(Locale.ROOT));
            return exact != null ? exact : sheetIds.get(name.trim().toLowerCase(Locale.ROOT));
        }

        private InterpretationCellView find(long sheet, int row, int col) {
            for (long id : precedents) {
                InterpretationCellView cell = cells.get(id);
                if (cell != null && cell.worksheetId() == sheet
                        && cell.rowNum() == row && cell.colNum() == col) {
                    return cell;
                }
            }
            for (InterpretationCellView cell : cells.values()) {
                if (cell.worksheetId() == sheet && cell.rowNum() == row && cell.colNum() == col) {
                    return cell;
                }
            }
            return null;
        }

        private Val fromSettled(long cellId) {
            ReadingOutcome outcome = settled.get(cellId);
            if (outcome == null || !outcome.typed()) {
                return Val.refused(ReadingOutcome.UNTYPABLE);
            }
            return Val.of(outcome);
        }

        private char peek() {
            skip();
            return index < text.length() ? text.charAt(index) : 0;
        }

        private void skip() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private boolean isLetter(char c) {
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
        }

        private boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }
    }

    private record Ref(String sheet, int row, int col) {}

    /** Where the operators record what they imply about unstated scales, and on whose authority. */
    private record Links(UnstatedScales unknowns, long cellId) {
        void same(int a, int b) {
            unknowns.same(a, b);
        }

        void require(int unknown, CellScale scale) {
            unknowns.require(unknown, scale, cellId);
        }
    }

    private static Val apply(Links links, String name, List<Val> args) {
        String fn = name.toUpperCase(Locale.ROOT);
        List<Val> flat = flatten(args);
        if (fn.equals("SUM") || fn.equals("AVERAGE") || fn.equals("MIN") || fn.equals("MAX")) {
            return fold(links, flat, true);
        }
        if (fn.equals("PRODUCT")) {
            return fold(links, flat, false);
        }
        if (fn.equals("ROUND") || fn.equals("ABS")) {
            for (Val arg : flat) {
                if (arg.refusal != null) {
                    return arg;
                }
                if (!arg.number && arg.kind != null) {
                    return arg;
                }
            }
            return flat.isEmpty() ? Val.refused(ReadingOutcome.UNTYPABLE) : flat.get(0);
        }
        return Val.refused(ReadingOutcome.UNTYPABLE);
    }

    private static List<Val> flatten(List<Val> args) {
        List<Val> flat = new ArrayList<>();
        for (Val arg : args) {
            if (arg.group) {
                flat.addAll(arg.members);
            } else {
                flat.add(arg);
            }
        }
        return flat;
    }

    private static Val fold(Links links, List<Val> args, boolean adding) {
        Val acc = null;
        for (Val arg : args) {
            if (arg.refusal != null) {
                return arg;
            }
            if (acc == null) {
                acc = arg;
                continue;
            }
            acc = adding ? add(links, acc, arg) : multiply(links, acc, arg);
        }
        return acc == null ? Val.refused(ReadingOutcome.UNTYPABLE) : acc;
    }

    private static Val negate(Val value) {
        if (value.number) {
            return Val.number(value.numeric.negate());
        }
        return value;
    }

    private static Val add(Links links, Val left, Val right) {
        if (left.refusal != null) {
            return left;
        }
        if (right.refusal != null) {
            return right;
        }
        if (left.number && right.number) {
            return Val.number(left.numeric.add(right.numeric));
        }
        if (left.number) {
            return right;
        }
        if (right.number) {
            return left;
        }
        if (left.kind == null || right.kind == null) {
            return Val.refused(ReadingOutcome.UNTYPABLE);
        }
        boolean stated = left.unknown < 0 && right.unknown < 0;
        if (!left.kind.equals(right.kind) || (stated && left.scale != right.scale)) {
            return Val.refused(ReadingOutcome.KIND_CONFLICT);
        }
        String currency = mergeCurrency(left, right);
        if (currency == null) {
            return Val.refused(ReadingOutcome.KIND_CONFLICT);
        }
        if (!left.unit.isEmpty() && !right.unit.isEmpty() && !left.unit.equals(right.unit)) {
            return Val.refused(ReadingOutcome.KIND_CONFLICT);
        }
        String unit = left.unit.isEmpty() ? right.unit : left.unit;
        if (stated) {
            return Val.dim(left.kind, left.scale, unit, currency);
        }
        // Adding is only meaningful in one scale, so a stated side fixes the unstated one.
        if (left.unknown >= 0 && right.unknown >= 0) {
            links.same(left.unknown, right.unknown);
            return Val.unstated(unit, currency, left.unknown);
        }
        Val known = left.unknown < 0 ? left : right;
        links.require((left.unknown < 0 ? right : left).unknown, known.scale);
        return Val.dim(known.kind, known.scale, unit, currency);
    }

    private static Val multiply(Links links, Val left, Val right) {
        if (left.refusal != null) {
            return left;
        }
        if (right.refusal != null) {
            return right;
        }
        if (left.number && right.number) {
            return Val.number(left.numeric.multiply(right.numeric));
        }
        if (left.number) {
            return scaledByFactor(links, right, left.numeric);
        }
        if (right.number) {
            return scaledByFactor(links, left, right.numeric);
        }
        if (ReadingOutcome.PERCENT.equals(left.kind)) {
            return right;
        }
        if (ReadingOutcome.PERCENT.equals(right.kind)) {
            return left;
        }
        // A ratio is a dimensionless multiple (a proration, a share): it keeps the kind of what it scales.
        if (ReadingOutcome.RATIO.equals(left.kind)) {
            return right;
        }
        if (ReadingOutcome.RATIO.equals(right.kind)) {
            return left;
        }
        if (isQuantity(left.kind) && ReadingOutcome.RATE.equals(right.kind)) {
            return money(left, right);
        }
        if (isQuantity(right.kind) && ReadingOutcome.RATE.equals(left.kind)) {
            return money(right, left);
        }
        // Money × Rate = Money (e.g., principal × interest rate, or any money × dimensionless multiplier)
        if (ReadingOutcome.MONEY.equals(left.kind) && ReadingOutcome.RATE.equals(right.kind)) {
            return left;
        }
        if (ReadingOutcome.MONEY.equals(right.kind) && ReadingOutcome.RATE.equals(left.kind)) {
            return right;
        }
        return Val.refused(ReadingOutcome.KIND_CONFLICT);
    }

    private static Val money(Val quantity, Val rate) {
        String currency = mergeCurrency(quantity, rate);
        if (currency == null) {
            return Val.refused(ReadingOutcome.KIND_CONFLICT);
        }
        CellScale scale = combineScale(quantity.scale, rate.scale);
        if (scale == null) {
            return Val.refused(ReadingOutcome.KIND_CONFLICT);
        }
        return Val.dim(ReadingOutcome.MONEY, scale, "", currency);
    }

    /** Powers of ten that convert between scales; other literals (x12, /365, 0.35) are plain arithmetic. */
    private static boolean isScaleFactor(double factor) {
        return factor == 1_000d || factor == 100_000d || factor == 1_000_000d
                || factor == 10_000_000d || factor == 1_000_000_000d;
    }

    /**
     * A money value multiplied by a literal. {@code x100000} turns lakhs into rupees, so the
     * scale follows; {@code x1000} is left alone because a bare thousand is usually a count.
     * A conversion that lands on no named scale (rupees x 100000) is refused, not guessed.
     * Unstated money multiplied into rupees was in the scale the factor undoes.
     */
    private static Val scaledByFactor(Links links, Val value, java.math.BigDecimal factor) {
        double f = factor.doubleValue();
        if (value.refusal == null && ReadingOutcome.RATIO.equals(value.kind) && f == 100d) {
            return Val.dim(ReadingOutcome.PERCENT, CellScale.UNIT, "", ""); // x / y * 100
        }
        if (value.refusal != null || !ReadingOutcome.MONEY.equals(value.kind)) {
            return value;
        }
        if (f == 1_000d || !isScaleFactor(f)) {
            return value;
        }
        if (value.unknown >= 0) {
            links.require(value.unknown, CellScale.dividedBy(CellScale.UNIT, f));
            return Val.dim(value.kind, CellScale.UNIT, value.unit, value.currency);
        }
        CellScale scaled = CellScale.multipliedBy(value.scale, f);
        return scaled == null
                ? Val.refused(ReadingOutcome.KIND_CONFLICT)
                : Val.dim(value.kind, scaled, value.unit, value.currency);
    }

    private static Val divide(Links links, Val left, Val right) {
        if (left.refusal != null) {
            return left;
        }
        if (right.refusal != null) {
            return right;
        }
        if (left.number && right.number) {
            if (right.numeric.signum() == 0) {
                return Val.refused(ReadingOutcome.UNTYPABLE);
            }
            return Val.number(left.numeric.divide(right.numeric, java.math.MathContext.DECIMAL64));
        }
        if (right.number) {
            if (left.kind == null || right.numeric.signum() == 0) {
                return Val.refused(ReadingOutcome.UNTYPABLE);
            }
            double divisor = right.numeric.doubleValue();
            if (left.unknown >= 0 && isScaleFactor(divisor)) {
                // Divided by 100000 to show lakhs: what was divided was rupees.
                links.require(left.unknown, CellScale.UNIT);
                return Val.dim(left.kind, CellScale.dividedBy(CellScale.UNIT, divisor), left.unit, left.currency);
            }
            if (ReadingOutcome.MONEY.equals(left.kind) && isScaleFactor(divisor)) {
                CellScale scaled = CellScale.dividedBy(left.scale, divisor);
                // A conversion that lands on no named scale (lakh / 100000) is not guessed.
                return scaled == null
                        ? Val.refused(ReadingOutcome.KIND_CONFLICT)
                        : Val.dim(left.kind, scaled, left.unit, left.currency);
            }
            return left;
        }
        if (left.kind == null || right.kind == null) {
            return Val.refused(ReadingOutcome.UNTYPABLE);
        }
        if (ReadingOutcome.MONEY.equals(left.kind) && ReadingOutcome.MONEY.equals(right.kind)) {
            return Val.dim(ReadingOutcome.RATIO, CellScale.UNIT, "", "");
        }
        if (ReadingOutcome.MONEY.equals(left.kind) && isQuantity(right.kind)) {
            return Val.dim(ReadingOutcome.RATE, CellScale.UNIT, right.unit, left.currency);
        }
        if (ReadingOutcome.MONEY.equals(left.kind) && ReadingOutcome.PERCENT.equals(right.kind)) {
            return left;
        }
        if (ReadingOutcome.MONEY.equals(left.kind) && ReadingOutcome.RATE.equals(right.kind)) {
            return Val.dim(ReadingOutcome.QUANTITY, CellScale.UNIT, right.unit, "");
        }
        if (left.kind.equals(right.kind)) {
            return Val.dim(ReadingOutcome.RATIO, CellScale.UNIT, "", "");
        }
        if (ReadingOutcome.RATIO.equals(right.kind)) {
            return left; // divided by a dimensionless multiple
        }
        return Val.refused(ReadingOutcome.KIND_CONFLICT);
    }

    private static boolean isQuantity(String kind) {
        return ReadingOutcome.QUANTITY.equals(kind) || ReadingOutcome.COUNT.equals(kind);
    }

    private static CellScale combineScale(CellScale left, CellScale right) {
        if (left == null) {
            return right;
        }
        if (right == null || left == right || right == CellScale.UNIT) {
            return left;
        }
        if (left == CellScale.UNIT) {
            return right;
        }
        return null;
    }

    /** {@code null} when the two currencies disagree. */
    private static String mergeCurrency(Val left, Val right) {
        if (!left.currency.isEmpty() && !right.currency.isEmpty()
                && !left.currency.equals(right.currency)) {
            return null;
        }
        return left.currency.isEmpty() ? right.currency : left.currency;
    }

    private static final class Val {
        final boolean number;
        final BigDecimal numeric;
        final String kind;
        final CellScale scale;
        final String unit;
        final String currency;
        final String refusal;
        final boolean group;
        final List<Val> members;
        final int unknown; // UnstatedScales unknown in place of scale, or -1

        private Val(
                boolean number,
                BigDecimal numeric,
                String kind,
                CellScale scale,
                String unit,
                String currency,
                String refusal,
                boolean group,
                List<Val> members,
                int unknown) {
            this.number = number;
            this.numeric = numeric;
            this.kind = kind;
            this.scale = scale;
            this.unit = unit == null ? "" : unit;
            this.currency = currency == null ? "" : currency;
            this.refusal = refusal;
            this.group = group;
            this.members = members;
            this.unknown = unknown;
        }

        static Val number(BigDecimal numeric) {
            return new Val(true, numeric, null, null, "", "", null, false, List.of(), -1);
        }

        static Val of(ReadingOutcome outcome) {
            return outcome.scaleUnstated()
                    ? unstated(outcome.unit, outcome.currency, outcome.scaleUnknown)
                    : dim(outcome.kind, outcome.scale, outcome.unit, outcome.currency);
        }

        static Val dim(String kind, CellScale scale, String unit, String currency) {
            return new Val(false, null, kind, scale == null ? CellScale.UNIT : scale,
                    unit, currency, null, false, List.of(), -1);
        }

        static Val unstated(String unit, String currency, int unknown) {
            return new Val(false, null, ReadingOutcome.MONEY, null, unit, currency, null, false, List.of(), unknown);
        }

        static Val refused(String refusal) {
            return new Val(false, null, null, null, "", "", refusal, false, List.of(), -1);
        }

        static Val group(List<Val> members) {
            return new Val(false, null, null, null, "", "", null, true, List.copyOf(members), -1);
        }

        ReadingOutcome toOutcome() {
            if (refusal != null) {
                return ReadingOutcome.refused(refusal);
            }
            if (number || kind == null) {
                return ReadingOutcome.refused(ReadingOutcome.UNTYPABLE);
            }
            if (unknown >= 0) {
                return ReadingOutcome.unstated(unit, currency, ReadingOutcome.DERIVED, unknown);
            }
            return ReadingOutcome.typed(kind, scale, unit, currency, ReadingOutcome.DERIVED);
        }
    }
}
