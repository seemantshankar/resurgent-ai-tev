package com.resurgent.tev.parser.classify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads OpenRouter settings from process env, then an optional {@code .env}
 * file ({@code //} and {@code #} comments). Prefers Model2 when set.
 */
public final class LlmEnvironment {

    static final String API_KEY = "OPENROUTER_API_KEY";
    static final String MODEL_ID = "Excel_Enrichment_Model_id";
    static final String MODEL2_ID = "Excel_Enrichment_Model2_id";
    static final String MODEL3_ID = "Excel_Enrichment_Model3_id";

    private LlmEnvironment() {}

    public static Map<String, String> load() {
        return load(Path.of(".env"));
    }

    static Map<String, String> load(Path dotenv) {
        Map<String, String> values = new LinkedHashMap<>();
        putIfPresent(values, API_KEY, System.getenv(API_KEY));
        putIfPresent(values, MODEL_ID, System.getenv(MODEL_ID));
        putIfPresent(values, MODEL2_ID, System.getenv(MODEL2_ID));
        putIfPresent(values, MODEL3_ID, System.getenv(MODEL3_ID));
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

    /**
     * Models to try, in order: Model 1, then Model 3, then Model 2. Unset and duplicate ids
     * are skipped. A request that fails on one model is re-sent (that request only) to the next.
     */
    static List<String> modelChain(Map<String, String> env) {
        List<String> chain = new ArrayList<>();
        for (String name : new String[] {MODEL_ID, MODEL3_ID, MODEL2_ID}) {
            String id = env.get(name);
            if (id != null && !id.isBlank() && !chain.contains(id.trim())) {
                chain.add(id.trim());
            }
        }
        return List.copyOf(chain);
    }

    public static ClassifierLlm classifierOrUnconfigured() {
        Map<String, String> env = load();
        String key = env.get(API_KEY);
        List<String> models = modelChain(env);
        if (key == null || key.isBlank() || models.isEmpty()) {
            return new UnconfiguredClassifierLlm();
        }
        return new OpenRouterClassifierLlm(key, models);
    }

    public static boolean liveConfigured() {
        Map<String, String> env = load();
        String key = env.get(API_KEY);
        return key != null && !key.isBlank() && !modelChain(env).isEmpty();
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
