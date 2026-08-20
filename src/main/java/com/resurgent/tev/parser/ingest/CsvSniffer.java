package com.resurgent.tev.parser.ingest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects the CSV dialect (encoding and delimiter) of a file without parsing it
 * through FastCSV. The sniffer is deterministic and independently unit-testable.
 *
 * <p>Detection order:
 * <ol>
 *   <li>Explicit encoding/delimiter supplied via internal API.</li>
 *   <li>BOM detection (UTF-8, UTF-16 LE/BE).</li>
 *   <li>No BOM → valid UTF-8, else Windows-1252 fallback.</li>
 *   <li>Delimiter scoring over the first N logical records accounting for
 *       quoted fields; pick the delimiter with the most consistent field count.</li>
 *   <li>Ambiguity → documented default: comma with {@code detectedBy="default"}.</li>
 * </ol>
 */
public final class CsvSniffer {

    private static final int SAMPLE_LIMIT = 8192;
    private static final int MAX_RECORDS = 20;
    private static final char QUOTE = '"';
    private static final char[] CANDIDATES = {',', ';', '\t', '|'};

    public CsvDialect sniff(Path csv) throws IOException {
        return sniff(csv, null, null);
    }

    public CsvDialect sniff(Path csv, String explicitEncoding, Character explicitDelimiter)
            throws IOException {
        byte[] prefix = readPrefix(csv);
        BomResult bom = detectBom(prefix);

        String encoding;
        boolean hasBom;
        int skipBytes;
        String detectedBy;

        if (explicitEncoding != null) {
            encoding = explicitEncoding;
            hasBom = bom.hasBom;
            skipBytes = bom.hasBom ? bom.length : 0;
            detectedBy = "explicit";
        } else if (bom.hasBom) {
            encoding = bom.encoding;
            hasBom = true;
            skipBytes = bom.length;
            detectedBy = "bom";
        } else if (isValidUtf8(prefix)) {
            encoding = "UTF-8";
            hasBom = false;
            skipBytes = 0;
            detectedBy = "utf8";
        } else {
            encoding = "windows-1252";
            hasBom = false;
            skipBytes = 0;
            detectedBy = "fallback";
        }

        Charset charset = Charset.forName(encoding);
        String sample = decodeSample(prefix, skipBytes, charset);
        char delimiter;
        String delimiterReason = detectDelimiter(sample);
        if (explicitDelimiter != null) {
            delimiter = explicitDelimiter;
        } else if (delimiterReason.equals("default")) {
            delimiter = ',';
        } else {
            delimiter = delimiterReason.charAt(0);
        }

        return new CsvDialect(encoding, delimiter, hasBom, detectedBy);
    }

    private byte[] readPrefix(Path csv) throws IOException {
        try (var in = Files.newInputStream(csv)) {
            byte[] buffer = new byte[SAMPLE_LIMIT + 4];
            int read = in.read(buffer);
            if (read < 0) {
                return new byte[0];
            }
            if (read < buffer.length) {
                byte[] trimmed = new byte[read];
                System.arraycopy(buffer, 0, trimmed, 0, read);
                return trimmed;
            }
            return buffer;
        }
    }

    private BomResult detectBom(byte[] bytes) {
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF) {
            return new BomResult("UTF-8", true, 3);
        }
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
            return new BomResult("UTF-16LE", true, 2);
        }
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
            return new BomResult("UTF-16BE", true, 2);
        }
        return new BomResult(null, false, 0);
    }

    private boolean isValidUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private String decodeSample(byte[] bytes, int skipBytes, Charset charset) {
        int len = Math.max(0, bytes.length - skipBytes);
        if (len == 0) {
            return "";
        }
        String decoded = new String(bytes, skipBytes, len, charset);
        // Truncate to MAX_RECORDS logical records later during scoring.
        return decoded;
    }

    /**
     * Scores candidate delimiters over the first {@link #MAX_RECORDS} logical records.
     * A logical record ends at an unquoted line break. Returns the winning delimiter
     * character as a one-character string, or "default" when all candidates are
     * equally ambiguous.
     */
    private String detectDelimiter(String sample) {
        int bestConsistent = 0;
        int bestFieldCount = 0;
        int bestRecords = 0;
        String bestCandidate = null;

        for (char candidate : CANDIDATES) {
            List<Integer> fieldCounts = fieldCounts(sample, candidate);
            if (fieldCounts.isEmpty()) {
                continue;
            }
            int[] mode = modeCount(fieldCounts);
            int consistent = mode[0];
            int fieldCount = mode[1];
            int records = fieldCounts.size();

            boolean better = consistent > bestConsistent
                    || (consistent == bestConsistent && fieldCount > bestFieldCount)
                    || (consistent == bestConsistent && fieldCount == bestFieldCount
                            && records > bestRecords);
            boolean tied = consistent == bestConsistent && fieldCount == bestFieldCount
                    && records == bestRecords;

            if (better) {
                bestConsistent = consistent;
                bestFieldCount = fieldCount;
                bestRecords = records;
                bestCandidate = String.valueOf(candidate);
            } else if (tied && bestCandidate != null) {
                // Two delimiters score identically: ambiguous. Fall back to the
                // documented default (comma) so detection stays deterministic.
                bestCandidate = null;
            }
        }

        return bestCandidate == null ? "default" : bestCandidate;
    }

    /**
     * Splits the sample into logical records and counts fields per record using the
     * given delimiter, respecting double-quote escaping.
     */
    private List<Integer> fieldCounts(String sample, char delimiter) {
        List<Integer> counts = new ArrayList<>();
        int i = 0;
        int n = sample.length();
        while (i < n && counts.size() < MAX_RECORDS) {
            int fields = 1;
            boolean inQuotes = false;
            while (i < n) {
                char c = sample.charAt(i);
                if (c == QUOTE) {
                    if (inQuotes && i + 1 < n && sample.charAt(i + 1) == QUOTE) {
                        i++;
                    } else {
                        inQuotes = !inQuotes;
                    }
                } else if (c == delimiter && !inQuotes) {
                    fields++;
                } else if ((c == '\n' || c == '\r') && !inQuotes) {
                    if (c == '\r' && i + 1 < n && sample.charAt(i + 1) == '\n') {
                        i++;
                    }
                    i++;
                    break;
                }
                i++;
            }
            counts.add(fields);
            if (i >= n) {
                break;
            }
        }
        return counts;
    }

    /**
     * Returns {@code [modeFrequency, modeValue]} for the field counts. The value is
     * returned only for diagnostics; callers compare frequencies.
     */
    private int[] modeCount(List<Integer> values) {
        int bestValue = values.get(0);
        int bestCount = 1;
        for (int v : values) {
            int count = 0;
            for (int w : values) {
                if (w == v) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                bestValue = v;
            }
        }
        return new int[]{bestCount, bestValue};
    }

    private record BomResult(String encoding, boolean hasBom, int length) {
    }
}
