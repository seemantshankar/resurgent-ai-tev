package com.resurgent.tev.parser.classify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads OpenRouter settings from process env, then an optional {@code .env}
 * file ({@code //} and {@code #} comments). Never logs secret values.
 */
public final class LlmEnvironment {

    static final String API_KEY = "OPENROUTER_API_KEY";
    static final String MODEL_ID = "Excel_Enrichment_Model_id";

    private LlmEnvironment() {}

    public static Map<String, String> load() {
        return load(Path.of(".env"));
    }

    static Map<String, String> load(Path dotenv) {
        Map<String, String> values = new LinkedHashMap<>();
        putIfPresent(values, API_KEY, System.getenv(API_KEY));
        putIfPresent(values, MODEL_ID, System.getenv(MODEL_ID));
        if (dotenv != null && Files.isRegularFile(dotenv)) {
            try {
                for (String line : Files.readAllLines(dotenv)) {
                    parseLine(values, line);
                }
            } catch (IOException ignored) {
                // Fall through with whatever process env already provided.
            }
        }
        return Map.copyOf(values);
    }

    public static ClassifierLlm classifierOrUnconfigured() {
        Map<String, String> env = load();
        String key = env.get(API_KEY);
        String model = env.get(MODEL_ID);
        if (key == null || key.isBlank() || model == null || model.isBlank()) {
            return new UnconfiguredClassifierLlm();
        }
        return new OpenRouterClassifierLlm(key, model);
    }

    public static boolean liveConfigured() {
        Map<String, String> env = load();
        String key = env.get(API_KEY);
        String model = env.get(MODEL_ID);
        return key != null && !key.isBlank() && model != null && !model.isBlank();
    }

    private static void parseLine(Map<String, String> values, String line) {
        if (line == null) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()
                || trimmed.startsWith("#")
                || trimmed.startsWith("//")) {
            return;
        }
        int eq = trimmed.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String name = trimmed.substring(0, eq).trim();
        String value = unquote(trimmed.substring(eq + 1).trim());
        values.putIfAbsent(name, value);
    }

    private static void putIfPresent(Map<String, String> values, String name, String value) {
        if (value != null && !value.isBlank()) {
            values.put(name, value);
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
