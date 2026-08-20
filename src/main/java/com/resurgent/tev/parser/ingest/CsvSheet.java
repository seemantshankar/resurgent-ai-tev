package com.resurgent.tev.parser.ingest;

import java.util.List;

/** A parsed CSV as one synthesized worksheet: every field of every record, verbatim. */
record CsvSheet(String sheetName, List<List<String>> rows) {}
