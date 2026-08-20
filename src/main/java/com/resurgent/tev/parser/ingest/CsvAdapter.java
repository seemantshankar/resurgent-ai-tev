package com.resurgent.tev.parser.ingest;

import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.CsvRecord;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV adapter that parses a file using a detected {@link CsvDialect}. The
 * no-argument {@link #parse(Path)} form preserves the old behavior by sniffing
 * first. All cell values are returned verbatim.
 */
public final class CsvAdapter {

    private final CsvSniffer sniffer = new CsvSniffer();

    public CsvSheet parse(Path csv) throws IOException {
        return parse(csv, sniffer.sniff(csv));
    }

    public CsvSheet parse(Path csv, CsvDialect dialect) throws IOException {
        Charset charset = Charset.forName(dialect.encoding());
        List<List<String>> rows = new ArrayList<>();
        try (CsvReader<CsvRecord> reader = CsvReader.builder()
                .fieldSeparator(dialect.delimiter())
                .detectBomHeader(true)
                .ofCsvRecord(csv, charset)) {
            for (CsvRecord record : reader) {
                rows.add(new ArrayList<>(record.getFields()));
            }
        }
        return new CsvSheet(fileStem(csv.getFileName().toString()), rows);
    }

    private static String fileStem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
