package com.resurgent.tev.parser.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal KEY=VALUE loader for a root {@code .env} file. */
public final class DotEnv {

    private DotEnv() {}

    public static Map<String, String> load(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String rawLine : Files.readAllLines(path)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).strip();
            String value = unquote(line.substring(separator + 1).strip());
            if (!key.isEmpty()) {
                values.put(key, value);
            }
        }
        return Map.copyOf(values);
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
