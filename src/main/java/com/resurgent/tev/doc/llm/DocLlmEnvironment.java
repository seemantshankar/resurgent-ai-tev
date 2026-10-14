package com.resurgent.tev.doc.llm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves the document-side LLM client from process env and an optional {@code .env}
 * file. Reuses the platform's OpenRouter credential; the model may be a doc-specific
 * override ({@code DOC_LLM_MODEL}) or fall back to the shared enrichment model.
 *
 * <pre>
 *   OPENROUTER_API_KEY   required to enable live document LLM calls
 *   DOC_LLM_MODEL        optional model id (falls back to Excel_Enrichment_Model_id)
 *   DOC_LLM_BASE_URL     optional OpenAI-compatible chat completions endpoint
 *   DOC_LLM_TIMEOUT_MS   optional per-call deadline (default 240000)
 *   DOC_LLM_DEBUG        when set, doc LLM clients print raw replies to stderr
 * </pre>
 *
 * When no key/model is configured the caller keeps its offline path (fixture mining /
 * declining tie-break), so the CLI and the test suite run without a network.
 */
public final class DocLlmEnvironment {

    static final String API_KEY = "OPENROUTER_API_KEY";
    static final String DOC_MODEL = "DOC_LLM_MODEL";
    static final String DOC_MODEL_ALIAS = "Doc_Llm_Model_id";
    static final String FALLBACK_MODEL = "Excel_Enrichment_Model_id";
    static final String BASE_URL = "DOC_LLM_BASE_URL";
    static final String TIMEOUT_MS = "DOC_LLM_TIMEOUT_MS";
    static final String DEBUG = "DOC_LLM_DEBUG";

    /** Process env wins; a {@code .env} found in this dir or an ancestor fills the gaps. */
    public static Map<String, String> load() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : new String[] {API_KEY, DOC_MODEL, DOC_MODEL_ALIAS, FALLBACK_MODEL,
                BASE_URL, TIMEOUT_MS, DEBUG}) {
            putIfPresent(values, key, System.getenv(key));
        }
        Path dotenv = findDotenv();
        if (dotenv != null) {
            try {
                for (String line : Files.readAllLines(dotenv)) {
                    parseLine(values, line);
                }
            } catch (IOException ignored) {
                // keep whatever process env provided
            }
        }
        return Map.copyOf(values);
    }

    /**
     * Builds the live client, or returns {@code null} when no key/model is configured.
     * Callers must fall back to their offline path on {@code null}.
     */
    public static DocLlmClient clientOrNull() {
        Map<String, String> env = load();
        String key = env.get(API_KEY);
        String model = modelId(env);
        if (key == null || key.isBlank() || model == null) {
            return null;
        }
        long timeoutMs = parseLong(env.get(TIMEOUT_MS), 240_000L);
        String baseUrl = env.get(BASE_URL);
        return new HttpDocLlmClient(key, model, baseUrl, timeoutMs);
    }

    /** Resolved model id, or null when none is configured. */
    static String modelId(Map<String, String> env) {
        for (String key : new String[] {DOC_MODEL, DOC_MODEL_ALIAS, FALLBACK_MODEL}) {
            String value = env.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * True when {@code DOC_LLM_DEBUG} is set (process env, or a non-blank value in
     * {@code .env}): doc LLM clients print raw replies to stderr for debugging.
     * Behaviour is configured here so the doc LLM seam owns all of its env.
     */
    public static boolean debugEnabled() {
        if (System.getenv(DEBUG) != null) {
            return true;
        }
        return load().get(DEBUG) != null;
    }

    /** Human-readable description for the run banner. */
    public static String describe() {
        DocLlmClient client = clientOrNull();
        if (client == null) {
            return "(offline: no OPENROUTER_API_KEY / model configured)";
        }
        return ((HttpDocLlmClient) client).model();
    }

    private static Path findDotenv() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++) {
            Path candidate = dir.resolve(".env");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static void parseLine(Map<String, String> values, String rawLine) {
        if (rawLine == null) {
            return;
        }
        String line = rawLine.strip();
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
            return;
        }
        int comment = indexOfComment(line);
        if (comment >= 0) {
            line = line.substring(0, comment).strip();
        }
        int eq = line.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String key = line.substring(0, eq).strip();
        String value = line.substring(eq + 1).strip();
        if (!key.isEmpty() && !value.isEmpty()) {
            values.putIfAbsent(key, unquote(value));
        }
    }

    private static int indexOfComment(String line) {
        int hash = line.indexOf('#');
        int slash = line.indexOf("//");
        if (hash < 0) {
            return slash;
        }
        if (slash < 0) {
            return hash;
        }
        return Math.min(hash, slash);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void putIfPresent(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }

    private DocLlmEnvironment() {}
}
