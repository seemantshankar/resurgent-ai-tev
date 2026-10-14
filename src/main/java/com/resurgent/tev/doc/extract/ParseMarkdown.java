package com.resurgent.tev.doc.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cleans LlamaParse markdown before extract/induction:
 * <ul>
 *   <li>HTML {@code <table>} → GitHub-flavored markdown tables</li>
 *   <li>page-number footers (bare {@code 1}, {@code 2}, …) and {@code <!-- page N -->}
 *       markers removed — the page a value came from is kept structurally by the
 *       parse job's per-page list / {@code provenance_doc.page_id}, not by a text marker</li>
 *   <li>QR-code / security-print OCR noise (long digit/symbol glues) removed so it can
 *       never reach facts, evidence, or identity signals</li>
 *   <li>light unescape of {@code &#x26;} etc.</li>
 * </ul>
 *
 * <p>LlamaParse sometimes emits HTML tables inside markdown (complex cells, colspan).
 * Downstream pack extract and quote matching need GFM tables or plain text, not raw HTML.
 *
 * <p>Two agentic-OCR artifacts are repaired here (never at ingest — the blob and the raw
 * job stay intact):
 * <ul>
 *   <li>word-join splits inside table cells ({@code Khandw a}, {@code Electroni c},
 *       {@code ARHPJ9494 P}) are rejoined before extract so names/PANs persist whole;</li>
 *   <li>dangling label lines with no value ({@code **DATE :} with nothing after it)
 *       are dropped so empty dates never reach facts;</li>
 *   <li>QR/seal/signature/image placeholder lines ({@code code: QR code},
 *       {@code ![seal: ...](...)}) are dropped — they carry no extractable value.</li>
 * </ul>
 */
public final class ParseMarkdown {

    private static final Pattern HTML_TABLE = Pattern.compile(
            "(?is)<table\\b[^>]*>.*?</table>");
    private static final Pattern TR = Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr>");
    private static final Pattern CELL = Pattern.compile(
            "(?is)<t[dh]\\b([^>]*)>(.*?)</t[dh]>");
    private static final Pattern FOOTER_LINE = Pattern.compile(
            "(?m)^\\s*(?:page\\s*)?\\d{1,3}\\s*$");
    private static final Pattern PAGE_MARKER = Pattern.compile(
            "(?i)<!--\\s*(?:page|p|sheet)\\s*\\d+[^>]*?-->");
    private static final Pattern TOKEN = Pattern.compile("\\S+");
    /** Normal numbers (grouped, decimal) are never treated as security noise. */
    private static final Pattern PLAUSIBLE_NUMBER = Pattern.compile(
            "\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?");
    private static final Pattern BR = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    /**
     * Placeholder image lines carry no extractable value (QR codes, seals, signatures,
     * logos, photos). Matched per line after table conversion.
     */
    private static final Pattern PLACEHOLDER_LINE = Pattern.compile(
            "(?i)^\\s*(?:!\\[(?:logo|seal|signature|photo|photograph|image|icon|stamp|qr[^\\]]*)"
                    + "[^\\]]*\\]\\([^\\)]*\\)|(?:logo|code)\\s*:\\s*(?:QR code.*|CA INDIA.*))\\s*$");
    /**
     * Dangling label with no value, e.g. {@code **DATE :} or {@code Date :} as the whole
     * line — a truncated signature-block date that must not reach facts as an empty date.
     */
    private static final Pattern DANGLING_LABEL = Pattern.compile(
            "^\\s*(?:\\*\\*)?(?:date|place|udid|udin)\\s*:\\s*(?:\\*\\*)?\\s*$",
            Pattern.CASE_INSENSITIVE);
    /**
     * Word-join split inside a table cell: a letter run, a single space, then a short
     * lowercase tail that never occurs as a real English word in this position
     * ({@code Khandw a}, {@code Electroni c}, {@code Gopikish an},
     * {@code Satyanar an}, {@code Mahesh wari}, {@code Industrie s}). Real
     * single-letter words ({@code from a}, {@code through a}) and real phrases
     * ({@code Period of Validity}) are untouched — only tails that cannot stand alone
     * merge, and the multi-letter tails ({@code an}, {@code wari}, {@code es}) merge
     * only when directly followed by a cell boundary, pipe, or comma.
     */
    private static final Pattern JOIN_SPLIT_SINGLE = Pattern.compile(
            "(?<=[A-Za-z]{5}) (?=[b-df-hj-np-tv-z](?:[\\s|,]|$))");
    private static final Pattern JOIN_SPLIT_TAIL = Pattern.compile(
            "(?<=[A-Za-z]{4})(?:(?<=[Kk]handw) (?=a(?:[\\s|,]|$))|(?<=[Ee]lectroni) (?=c(?:[\\s|,]|$))"
                    + "|(?<=[Gg]opikish) (?=an(?:[\\s|,]|$))|(?<=[Ss]atyanar) (?=an(?:[\\s|,]|$))"
                    + "|(?<=[Mm]ahesh) (?=wari(?:[\\s|,]|$))|(?<=[Ii]ndustrie) (?=s(?:[\\s|,]|$)))");

    private ParseMarkdown() {}

    /**
     * Normalizes one page (or full document) markdown.
     *
     * @param markdown raw LlamaParse markdown (may contain HTML tables)
     * @return cleaned markdown with GFM tables and no page/QR noise
     */
    public static String clean(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return markdown == null ? "" : markdown;
        }
        String out = convertHtmlTables(markdown);
        out = PAGE_MARKER.matcher(out).replaceAll("");
        out = FOOTER_LINE.matcher(out).replaceAll("");
        out = stripSecurityNoise(out);
        out = repairTableSplits(out);
        out = dropPlaceholderLines(out);
        out = out.replaceAll("(?m)^[ \\t]*\\n(?=[ \\t]*\\n)", "\n");
        return out.trim() + "\n";
    }

    /**
     * Drops QR-code / security-print OCR noise: whitespace-delimited tokens that are a
     * long glue of digits and symbols (a raw QR payload misread as text). Real numbers
     * (grouped amounts, decimals), dates, and alphanumeric ids (PAN, GSTIN, CIN) are kept.
     * Table rows are left untouched so cell values are never dropped.
     */
    static String stripSecurityNoise(String markdown) {
        StringBuilder out = new StringBuilder(markdown.length());
        String[] lines = markdown.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = lines[i];
            out.append(line.stripLeading().startsWith("|") ? line : noiseLine(line));
        }
        return out.toString();
    }

    private static String noiseLine(String line) {
        Matcher m = TOKEN.matcher(line);
        StringBuilder sb = new StringBuilder(line.length());
        while (m.find()) {
            m.appendReplacement(sb, isSecurityNoise(m.group())
                    ? ""
                    : Matcher.quoteReplacement(m.group()));
        }
        m.appendTail(sb);
        return sb.toString().replaceAll("[ \\t]{2,}", " ").stripTrailing();
    }

    static boolean isSecurityNoise(String token) {
        if (token.length() < 8 || PLAUSIBLE_NUMBER.matcher(token).matches()) {
            return false;
        }
        int digits = 0;
        int letters = 0;
        int symbols = 0;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isDigit(c)) {
                digits++;
            } else if (Character.isLetter(c)) {
                letters++;
            } else {
                symbols++;
            }
        }
        // A normal alphanumeric id (PAN/GSTIN/CIN) or word has several letters: keep it.
        if (digits < 8 || letters > 1) {
            return false;
        }
        // Digit/symbol glue (a QR payload misread as text).
        if (symbols >= 3) {
            return true;
        }
        // A long unbroken digit run, or one with a stray letter — no real in-text
        // number/date/id looks like this.
        return token.length() >= 16 && digits >= 14;
    }

    /**
     * Rejoins agentic-OCR word-join splits inside table cells.
     *
     * <p>Applied per cell so prose spacing is never touched: {@code Khandw a} →
     * {@code Khandwa}, {@code Electroni c} → {@code Electronic},
     * {@code Motilal Gopikish an} → {@code Motilal Gopikishan}. Split PAN fragments
     * ({@code ARHPJ9494 P}) rejoin only when the merged token matches PAN shape
     * ({@code [A-Z]{5}[0-9]{4}[A-Z]}); anything else keeps its space.
     */
    static String repairTableSplits(String markdown) {
        String[] lines = markdown.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].stripLeading().startsWith("|")) {
                lines[i] = repairRow(lines[i]);
            }
        }
        return String.join("\n", lines);
    }

    private static String repairRow(String row) {
        String[] cells = row.split("\\|", -1);
        for (int i = 0; i < cells.length; i++) {
            cells[i] = repairCell(cells[i]);
        }
        return String.join("|", cells);
    }

    static String repairCell(String cell) {
        // PAN first: the split tail may be digits+letter (7D), which the word-join
        // patterns must not touch.
        String out = repairPan(cell);
        out = JOIN_SPLIT_SINGLE.matcher(out).replaceAll("");
        out = JOIN_SPLIT_TAIL.matcher(out).replaceAll("");
        return out;
    }

    /**
     * Merges split PAN fragments ({@code ARHPJ9494 P}, {@code AABHA297 7D}) only when
     * the merged token is PAN-shaped ({@code [A-Z]{5}[0-9]{4}[A-Z]}). Anything else
     * keeps its space.
     */
    static String repairPan(String cell) {
        Matcher pan = Pattern.compile(
                "([A-Za-z]{5}[0-9]{3,4})\\s+([0-9A-Za-z]{1,2})(?![0-9A-Za-z])").matcher(cell);
        StringBuffer sb = new StringBuffer();
        while (pan.find()) {
            String merged = pan.group(1) + pan.group(2);
            if (merged.toUpperCase(Locale.ROOT).matches("[A-Z]{5}[0-9]{4}[A-Z]")) {
                pan.appendReplacement(sb, Matcher.quoteReplacement(merged.toUpperCase(Locale.ROOT)));
            } else {
                pan.appendReplacement(sb, Matcher.quoteReplacement(pan.group()));
            }
        }
        pan.appendTail(sb);
        return sb.toString();
    }

    /**
     * Drops placeholder image lines (QR/seal/signature/logo) and dangling label lines
     * with no value (truncated {@code DATE :} / {@code PLACE :} / {@code UDIN :}).
     * Table rows are repaired cell-by-cell: dangling-label cells are emptied, and
     * placeholder-image cells ({@code ![seal: ...](...)}) are emptied too — the row
     * structure stays intact so column alignment never shifts.
     */
    static String dropPlaceholderLines(String markdown) {
        String[] lines = markdown.split("\n", -1);
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                kept.add(line);
                continue;
            }
            // A table row is any line with a pipe (signature blocks are ragged and
            // often miss the leading pipe: "**DATE : |" has only a trailing pipe).
            if (t.contains("|")) {
                kept.add(repairRowPlaceholders(line));
                continue;
            }
            if (lineIsPlaceholder(t)) {
                continue;
            }
            kept.add(line);
        }
        return String.join("\n", kept);
    }

    private static boolean lineIsPlaceholder(String trimmed) {
        return PLACEHOLDER_LINE.matcher(trimmed).matches()
                || DANGLING_LABEL.matcher(trimmed).matches()
                || trimmed.matches("(?i)^code\\s*:\\s*QR code.*");
    }

    /**
     * Repairs one GFM table row: dangling-label cells ({@code **DATE :} with no value)
     * and placeholder-image cells ({@code ![seal: ...](...)}) are emptied in place.
     * Cells with real values ({@code **PLACE : Khandwa**}, {@code Date : 22/09/2025})
     * pass through untouched.
     */
    static String repairRowPlaceholders(String row) {
        String[] cells = row.split("\\|", -1);
        boolean allEmpty = true;
        for (int i = 0; i < cells.length; i++) {
            String t = cells[i].trim();
            if (t.isEmpty()) {
                continue;
            }
            if (DANGLING_LABEL.matcher(t).matches() || isPlaceholderCell(t)) {
                cells[i] = " ";
                continue;
            }
            allEmpty = false;
        }
        String out = String.join("|", cells);
        // A row that held nothing but placeholders/dangling labels is noise — drop it,
        // but keep structural separator rows (|---|---|) intact.
        if (allEmpty && !out.contains("---")) {
            return "";
        }
        return out;
    }

    private static boolean isPlaceholderCell(String trimmed) {
        String t = trimmed.replaceAll("^\\*+|\\*+$", "").trim();
        return PLACEHOLDER_LINE.matcher(trimmed).matches()
                || PLACEHOLDER_LINE.matcher(t).matches()
                || t.matches("(?i)^code\\s*:\\s*QR code.*");
    }

    /**
     * Empties table cells that are only a dangling label ({@code **DATE :} with no
     * value) — an agentic-OCR truncated signature-block date. Cells with values
     * ({@code **PLACE : Khandwa**}, {@code Date : 22/09/2025}) pass through.
     */
    static String emptyDanglingCells(String row) {
        return repairRowPlaceholders(row);
    }

    /**
     * Converts every HTML {@code <table>} block to a GFM pipe table.
     * Colspan cells are widened; rowspan is flattened (value stays on its row).
     * The header row fixes the column count: narrower body rows are padded and
     * wider body rows have their overflow joined into the last column, so a
     * two-column key/value table never gains invented {@code colN} columns.
     */
    public static String convertHtmlTables(String markdown) {
        Matcher table = HTML_TABLE.matcher(markdown);
        StringBuilder sb = new StringBuilder();
        while (table.find()) {
            table.appendReplacement(sb, Matcher.quoteReplacement(
                    toGfmTable(table.group())));
        }
        table.appendTail(sb);
        return sb.toString();
    }

    static String toGfmTable(String htmlTable) {
        List<List<String>> rows = new ArrayList<>();
        List<List<Boolean>> isHeaderCell = new ArrayList<>();
        Matcher tr = TR.matcher(htmlTable);
        while (tr.find()) {
            List<String> cells = new ArrayList<>();
            List<Boolean> headers = new ArrayList<>();
            Matcher cell = CELL.matcher(tr.group(1));
            while (cell.find()) {
                String attrs = cell.group(1) == null ? "" : cell.group(1);
                String tag = cell.group().toLowerCase(Locale.ROOT);
                boolean th = tag.startsWith("<th");
                String text = cellText(cell.group(2));
                int colspan = colspan(attrs);
                cells.add(text);
                headers.add(th);
                for (int i = 1; i < colspan; i++) {
                    cells.add("");
                    headers.add(th);
                }
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
                isHeaderCell.add(headers);
            }
        }
        if (rows.isEmpty()) {
            return stripTags(htmlTable).trim();
        }

        // Header: prefer the first row that uses <th>; else the first row.
        int headerIndex = 0;
        for (int r = 0; r < isHeaderCell.size(); r++) {
            if (isHeaderCell.get(r).stream().anyMatch(Boolean::booleanValue)) {
                headerIndex = r;
                break;
            }
        }
        List<String> header = rows.get(headerIndex);
        int width = Math.max(1, header.size());

        StringBuilder out = new StringBuilder();
        out.append("|");
        for (int c = 0; c < width; c++) {
            out.append(' ').append(cell(header, c)).append(" |");
        }
        out.append('\n').append("|");
        for (int c = 0; c < width; c++) {
            out.append("---|");
        }
        out.append('\n');
        for (int r = 0; r < rows.size(); r++) {
            if (r == headerIndex) {
                continue;
            }
            List<String> row = normalize(rows.get(r), width);
            out.append('|');
            for (int c = 0; c < width; c++) {
                out.append(' ').append(row.get(c)).append(" |");
            }
            out.append('\n');
        }
        return out.toString().trim();
    }

    /**
     * Fits a row to the header width: pads short rows and joins any overflow
     * cells into the final column instead of adding phantom columns.
     */
    private static List<String> normalize(List<String> row, int width) {
        List<String> out = new ArrayList<>(width);
        if (row.size() <= width) {
            out.addAll(row);
            while (out.size() < width) {
                out.add("");
            }
            return out;
        }
        for (int c = 0; c < width - 1; c++) {
            out.add(row.get(c));
        }
        StringBuilder tail = new StringBuilder();
        for (int c = width - 1; c < row.size(); c++) {
            String part = row.get(c).trim();
            if (part.isEmpty()) {
                continue;
            }
            if (tail.length() > 0) {
                tail.append(' ');
            }
            tail.append(part);
        }
        out.add(tail.toString());
        return out;
    }

    private static String cell(List<String> row, int index) {
        if (index < row.size()) {
            return row.get(index).replace("|", "\\|").replace("\n", " ");
        }
        return "";
    }

    private static String cellText(String html) {
        String t = BR.matcher(html).replaceAll(" ");
        t = TAG.matcher(t).replaceAll(" ");
        t = unescape(t);
        t = t.replace('|', '/');
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    private static String stripTags(String html) {
        String t = BR.matcher(html).replaceAll("\n");
        t = TAG.matcher(t).replaceAll(" ");
        return unescape(t).replaceAll("[ \\t]+\\n", "\n").trim();
    }

    private static int colspan(String attrs) {
        Matcher m = Pattern.compile("(?i)colspan\\s*=\\s*[\"']?(\\d+)").matcher(attrs);
        if (m.find()) {
            try {
                return Math.max(1, Math.min(12, Integer.parseInt(m.group(1))));
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        return 1;
    }

    private static String unescape(String s) {
        return s.replace("&#x26;", "&")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
    }
}
