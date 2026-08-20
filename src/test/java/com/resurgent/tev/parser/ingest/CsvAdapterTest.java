package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Adapter seam: verifies that {@link CsvAdapter} can parse CSVs with a variety of
 * detected dialects without re-running the sniffer.
 */
class CsvAdapterTest {

    @TempDir
    Path tempDir;

    private Path writeBytes(String name, byte[] content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.write(file, content);
        return file;
    }

    @Test
    void parseWithSemicolonDialect_readsCellsVerbatim() throws Exception {
        Path csv = writeBytes("semi.csv", "a;b;c\n1;2;3\n".getBytes(StandardCharsets.UTF_8));
        CsvDialect dialect = new CsvDialect("UTF-8", ';', false, "utf8");

        CsvSheet sheet = new CsvAdapter().parse(csv, dialect);

        assertThat(sheet.sheetName()).isEqualTo("semi");
        assertThat(sheet.rows()).containsExactly(
                java.util.List.of("a", "b", "c"),
                java.util.List.of("1", "2", "3"));
    }

    @Test
    void parseUtf16LeBom_withDetectedDialect_readsCells() throws Exception {
        byte[] bom = {(byte) 0xFF, (byte) 0xFE};
        byte[] body = "h1\th2\n1\t2\n".getBytes(StandardCharsets.UTF_16LE);
        Path csv = writeBytes("utf16le.tsv", concat(bom, body));
        CsvDialect dialect = new CsvSniffer().sniff(csv);

        CsvSheet sheet = new CsvAdapter().parse(csv, dialect);

        assertThat(dialect.encoding()).isEqualTo("UTF-16LE");
        assertThat(sheet.rows()).containsExactly(
                java.util.List.of("h1", "h2"),
                java.util.List.of("1", "2"));
    }

    @Test
    void parseWithoutDialect_sniffsAndParses() throws Exception {
        Path csv = writeBytes("auto.csv", "a|b|c\n1|2|3\n".getBytes(StandardCharsets.UTF_8));

        CsvSheet sheet = new CsvAdapter().parse(csv);

        assertThat(sheet.rows()).containsExactly(
                java.util.List.of("a", "b", "c"),
                java.util.List.of("1", "2", "3"));
    }

    private byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
