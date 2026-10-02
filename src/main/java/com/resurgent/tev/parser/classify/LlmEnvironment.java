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
    static final String CELL_DECISION_MODEL_ID = "Excel_Enrichment_Cell_decision_model_id";
    static final String CELL_DECISION_COMPARE = "Excel_Enrichment_Cell_decision_compare";
    static final String CELL_DECISION_MIN_CONFIDENCE = "Excel_Enrichment_Cell_decision_min_confidence";
    static final String CELL_DECISION_CONCURRENCY = "Excel_Enrichment_Cell_decision_concurrency";
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
        putIfPresent(values, CELL_DECISION_MODEL_ID, System.getenv(CELL_DECISION_MODEL_ID));
        putIfPresent(values, CELL_DECISION_COMPARE, System.getenv(CELL_DECISION_COMPARE));
        putIfPresent(values, CELL_DECISION_MIN_CONFIDENCE, System.getenv(CELL_DECISION_MIN_CONFIDENCE));
        putIfPresent(values, CELL_DECISION_CONCURRENCY, System.getenv(CELL_DECISION_CONCURRENCY));
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

    /** The configured model ids in the order they are tried, for the run banner. */
    public static String describeModels() {
        List<String> models = modelChain(load());
        return models.isEmpty() ? "(none configured)" : String.join("  ->  ", models);
    }

    /**
     * Decision model for cell kind + scale, used before the chat models when
     * {@code Excel_Enrichment_Cell_decision_model_id} is set (e.g. {@code liquid/d1}); else null.
     */
    static CellDecisionClient decisionClientOrNull() {
        Map<String, String> env = load();
        String key = env.get(API_KEY);
        String model = env.get(CELL_DECISION_MODEL_ID);
        if (key == null || key.isBlank() || model == null || model.isBlank()) {
            return null;
        }
        System.err.println("[cell-decision] using decision model " + model.trim());
        return new OpenRouterDecisionClient(key, model.trim());
    }

    /** {@code Excel_Enrichment_Cell_decision_min_confidence}, default 0.90; ignored when not a number in (0, 1]. */
    static double decisionMinConfidence() {
        String v = load().get(CELL_DECISION_MIN_CONFIDENCE);
        if (v != null) {
            try {
                double parsed = Double.parseDouble(v.trim());
                if (parsed > 0 && parsed <= 1) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
            System.err.println("[cell-decision] ignoring " + CELL_DECISION_MIN_CONFIDENCE + "='" + v + "'");
        }
        return CellTypeClassifierLlm.DEFAULT_DECISION_MIN_CONFIDENCE;
    }

    /** {@code Excel_Enrichment_Cell_decision_concurrency}, default 8. */
    static int decisionConcurrency() {
        String v = load().get(CELL_DECISION_CONCURRENCY);
        if (v != null) {
            try {
                int parsed = Integer.parseInt(v.trim());
                if (parsed >= 1) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
            System.err.println("[cell-decision] ignoring " + CELL_DECISION_CONCURRENCY + "='" + v + "'");
        }
        return CellTypeClassifierLlm.DEFAULT_DECISION_CONCURRENCY;
    }

    /** True when {@code Excel_Enrichment_Cell_decision_compare} is set to true: D1 runs in shadow mode. */
    static boolean decisionCompareRequested() {
        String v = load().get(CELL_DECISION_COMPARE);
        return v != null && v.trim().equalsIgnoreCase("true");
    }

    /** Where shadow-mode rows go: {@code reports/d1_compare_<timestamp>.csv}. */
    static Path decisionCompareCsv() {
        return Path.of("reports", "d1_compare_"
                + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + ".csv");
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
