package com.resurgent.tev.parser.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvSnifferTest {

    @TempDir
    Path tempDir;

    private Path write(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private Path writeBytes(String name, byte[] content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.write(file, content);
        return file;
    }

    @Test
    void plainUtf8Comma_detectsUtf8AndComma() throws Exception {
        Path csv = write("plain.csv", "a,b,c\n1,2,3\n");

        CsvDialect dialect = new CsvSniffer().sniff(csv);

        assertThat(dialect.encoding()).isEqualTo("UTF-8");
        assertThat(dialect.delimiter()).isEqualTo(',');
        assertThat(dialect.hasBom()).isFalse();
        assertThat(dialect.detectedBy()).isEqualTo("utf8");
    }

    @Test
    void utf8Bom_detectsBomAndSkipsItForDelimiterScoring() throws Exception {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "a;b;c\n1;2;3\n".getBytes(StandardCharsets.UTF_8);
        Path csv = writeBytes("bom-utf8.csv", concat(bom, body));

        CsvDialect dialect = new CsvSniffer().sniff(csv);

        assertThat(dialect.encoding()).isEqualTo("UTF-8");
        assertThat(dialect.delimiter()).isEqualTo(';');
        assertThat(dialect.hasBom()).isTrue();
        assertThat(dialect.detectedBy()).isEqualTo("bom");
    }

    @Test
    void utf16LeBom_detectsEncodingAndDelimiter() throws Exception {
        byte[] bom = {(byte) 0xFF, (byte) 0xFE};
        byte[] body = "a\t1\nb\t2\n".getBytes(StandardCharsets.UTF_16LE);
        Path csv = writeBytes("bom-utf16le.csv", concat(bom, body));

        CsvDialect dialect = new CsvSniffer().sniff(csv);

        assertThat(dialect.encoding()).isEqualTo("UTF-16LE");
        assertThat(dialect.delimiter()).isEqualTo('\t');
        assertThat(dialect.hasBom()).isTrue();
        assertThat(dialect.detectedBy()).isEqualTo("bom");
    }

    @Test
    void utf16BeBom_detectsEncodingAndDelimiter() throws Exception {
        byte[] bom = {(byte) 0xFE, (byte) 0xFF};
        byte[] body = "a|1\nb|2\n".getBytes(StandardCharsets.UTF_16BE);
        Path csv = writeBytes("bom-utf16be.csv", concat(bom, body));

        CsvDialect dialect = new CsvSniffer().sniff(csv);

        assertThat(dialect.encoding()).isEqualTo("UTF-16BE");
        assertThat(dialect.delimiter()).isEqualTo('|');
        assertThat(dialect.hasBom()).isTrue();
        assertThat(dialect.detectedBy()).isEqualTo("bom");
    }

    @Test
    void windows1252Bytes_fallsBackToWindows1252() throws Exception {
        // windows-1252 encoded euro sign (0x80) is not valid UTF-8.
        byte[] content = "a;b;c\n1;2;\u20ac\n".getBytes("windows-1252");
        Path csv = writeBytes("win1252.csv", content);

        CsvDialect dialect = new CsvSniffer().sniff(csv);

        assertThat(dialect.encoding()).isEqualTo("windows-1252");
        assertThat(dialect.delimiter()).isEqualTo(';');
        assertThat(dialect.hasBom()).isFalse();
        assertThat(dialect.detectedBy()).isEqualTo("fallback");
    }

    @Test
    void quotedFieldsWithCommaInside_doNotMisleadDelimiterScoring() throws Exception {
        Path csv = write("quoted.csv", "\"a,b\";c\n\"x,y\";z\n");

        CsvDialect dialect = new CsvSniffer().sniff(csv);

        assertThat(dialect.delimiter()).isEqualTo(';');
    }

    @Test
    void singleLineWithoutClearDelimiter_defaultsToComma() throws Exception {
        Path csv = write("ambiguous.csv", "abc");

        CsvDialect dialect = new CsvSniffer().sniff(csv);

        assertThat(dialect.encoding()).isEqualTo("UTF-8");
        assertThat(dialect.delimiter()).isEqualTo(',');
        assertThat(dialect.detectedBy()).isEqualTo("utf8");
    }

    @Test
    void explicitEncodingAndDelimiter_areHonored() throws Exception {
        Path csv = write("explicit.csv", "a,b,c\n1,2,3\n");

        CsvDialect dialect = new CsvSniffer().sniff(csv, "windows-1252", '|');

        assertThat(dialect.encoding()).isEqualTo("windows-1252");
        assertThat(dialect.delimiter()).isEqualTo('|');
        assertThat(dialect.detectedBy()).isEqualTo("explicit");
    }

    @Test
    void emptyFile_defaultsToUtf8Comma() throws Exception {
        Path csv = write("empty.csv", "");

        CsvDialect dialect = new CsvSniffer().sniff(csv);

        assertThat(dialect.encoding()).isEqualTo("UTF-8");
        assertThat(dialect.delimiter()).isEqualTo(',');
        assertThat(dialect.detectedBy()).isEqualTo("utf8");
    }

    private byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
