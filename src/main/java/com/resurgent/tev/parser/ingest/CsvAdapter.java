package com.resurgent.tev.parser.ingest;

import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.CsvRecord;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV adapter on the known internal UTF-8/comma path: no encoding or delimiter
 * detection yet (ticket 03). Synthesizes one worksheet named from the file stem;
 * all values are literal.
 */
public final class CsvAdapter {

    public CsvSheet parse(Path csv) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (CsvReader<CsvRecord> reader = CsvReader.builder().ofCsvRecord(csv)) {
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
