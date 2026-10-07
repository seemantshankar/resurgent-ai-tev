package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * OpenRouter adapter for region layout + Layer A. Reasoning effort is low.
 */
public final class OpenRouterClassifierLlm implements ClassifierLlm {

    static final String DEFAULT_URL = "https://openrouter.ai/api/v1/chat/completions";
    public static final int LAYER_A_MAX_COMPLETION_TOKENS = 4_096;
    /** A Layer A batch reply: about 800 tokens a candidate (its "about" paragraph and labels) plus reasoning. */
    private static final int LAYER_A_TOKENS_PER_CANDIDATE = 800;
    private static final int LAYER_A_BATCH_MAX_COMPLETION_TOKENS = 24_576;
    /** The most a truncated Layer A batch reply is retried with; every model in the chain allows far more. */
    private static final int LAYER_A_RETRY_MAX_COMPLETION_TOKENS = 49_152;
    public static final int REGION_LAYOUT_MAX_COMPLETION_TOKENS = 32_768;
    public static final int LAYER_A_LABEL_MAX_ITEMS = 32;

    /** Xiaomi's own pay-as-you-go endpoint (OpenAI-compatible). */
    static final String XIAOMI_URL = "https://api.xiaomimimo.com/v1/chat/completions";

    /** Xiaomi MiMo models are asked without thinking; see {@link HttpCompletionsClient#requestBody}. */
    private static final String MIMO_PREFIX = "xiaomi/mimo";

    /** Which API a client talks to; it decides the request shape and where the cost comes from. */
    enum Route {
        OPENROUTER("OpenRouter"),
        XIAOMI("Xiaomi");

        final String label;

        Route(String label) {
            this.label = label;
        }
    }

    static boolean isMimo(String model) {
        return model.startsWith(MIMO_PREFIX);
    }

    /** The reply budget for a Layer A call over {@code candidates} regions: it grows with them, up to a bound. */
    static int layerAMaxCompletionTokens(int candidates) {
        return Math.min(LAYER_A_BATCH_MAX_COMPLETION_TOKENS,
                Math.max(LAYER_A_MAX_COMPLETION_TOKENS, LAYER_A_TOKENS_PER_CANDIDATE * candidates));
    }

    /** A binding answer is a short list of row and cell paths; a small prompt needs no more than this. */
    private static final int BIND_SMALL_COMPLETION_TOKENS = 12_288;

    /**
     * The completion cap for a binding call. The cap also decides which hang deadline applies
     * ({@link Deadlines}), and a cap of 32k classed every binding call as a big batch, so one
     * stuck request held the whole run for three minutes. A small prompt gets a small cap and
     * the fail-fast deadline; a big prompt, whose answer may be long, keeps the large one.
     */
    static int bindMaxCompletionTokens(int promptChars) {
        return promptChars >= Deadlines.LARGE_PROMPT_CHARS
                ? REGION_LAYOUT_MAX_COMPLETION_TOKENS
                : BIND_SMALL_COMPLETION_TOKENS;
    }

    private final CompletionsClient client;
    /** Takes the big calls (region layout, Layer A batches); the same chain as {@link #client} unless a large-call model is set. */
    private final CompletionsClient largeClient;

    public OpenRouterClassifierLlm(String apiKey, String model) {
        this(new HttpCompletionsClient(apiKey, model, DEFAULT_URL));
    }

    /** Models in the order to try them; a failed request moves to the next model. */
    public OpenRouterClassifierLlm(String apiKey, List<String> models) {
        this(chainOf(apiKey, null, models));
    }

    /**
     * As above, and a Xiaomi MiMo model is called on Xiaomi's own API with {@code xiaomiKey}
     * (its own rate limit, not the pool OpenRouter's users share) when that key is given.
     */
    public OpenRouterClassifierLlm(String apiKey, String xiaomiKey, List<String> models) {
        this(chainOf(apiKey, xiaomiKey, models));
    }

    /**
     * As above, and the big calls (region layout and Layer A batches) try {@code largeModel} first,
     * then the rest of the chain. A slow model that writes about 60 tokens a second can stall for
     * minutes on a big call, while a fast one answers in seconds; small calls keep the cheap order.
     */
    public OpenRouterClassifierLlm(String apiKey, String xiaomiKey, List<String> models, String largeModel) {
        this(chainOf(apiKey, xiaomiKey, models), largeChainOf(apiKey, xiaomiKey, models, largeModel));
    }

    private static CompletionsClient largeChainOf(
            String apiKey, String xiaomiKey, List<String> models, String largeModel) {
        if (largeModel == null || largeModel.isBlank() || largeModel.trim().equals(models.get(0))) {
            return null; // the ordinary chain already starts with it
        }
        List<String> order = new java.util.ArrayList<>();
        order.add(largeModel.trim());
        for (String model : models) {
            if (!order.contains(model)) {
                order.add(model);
            }
        }
        return chainOf(apiKey, xiaomiKey, order);
    }

    private static CompletionsClient chainOf(String apiKey, String xiaomiKey, List<String> models) {
        if (models.size() == 1) {
            return clientFor(apiKey, xiaomiKey, models.get(0), deadlinesFor(models.get(0), 0, 1));
        }
        List<FallbackCompletionsClient.Link> links = new java.util.ArrayList<>();
        for (int i = 0; i < models.size(); i++) {
            String model = models.get(i);
            links.add(new FallbackCompletionsClient.Link(model,
                    clientFor(apiKey, xiaomiKey, model, deadlinesFor(model, i, models.size()))));
        }
        return new FallbackCompletionsClient(links);
    }

    private static HttpCompletionsClient clientFor(
            String apiKey, String xiaomiKey, String model, Deadlines deadlines) {
        if (isMimo(model) && xiaomiKey != null && !xiaomiKey.isBlank()) {
            return new HttpCompletionsClient(xiaomiKey, model, XIAOMI_URL, deadlines, Route.XIAOMI);
        }
        return new HttpCompletionsClient(apiKey, model, DEFAULT_URL, deadlines, Route.OPENROUTER);
    }

    /** The limits for {@code model} at {@code index} in a chain of {@code chainLength}. */
    static Deadlines deadlinesFor(String model, int index, int chainLength) {
        if (isMimo(model)) {
            return index == chainLength - 1 ? Deadlines.LAST_RESORT : Deadlines.SLOW_WRITER;
        }
        return deadlinesFor(index, chainLength);
    }

    /**
     * How long one request may take before the model is given up on. A model with another
     * behind it fails fast, because a healthy small call answers in a few seconds and waiting
     * longer only delays the fallback. The last model has nothing behind it, so it is patient.
     */
    static Deadlines deadlinesFor(int index, int chainLength) {
        if (index == chainLength - 1) {
            return Deadlines.LAST_RESORT;
        }
        return index == 0 ? Deadlines.FIRST_CHOICE : Deadlines.FALLBACK;
    }

    /** Whole-exchange limits: {@code small} for ordinary calls, {@code large} for big batches. */
    record Deadlines(Duration small, Duration large) {
        /**
         * Chosen from a measured run: the first-choice model's healthy small calls took 2-10s
         * (slowest 9.7s of 63), so anything past 15s is a hang worth abandoning. At 45s a hang
         * made it slower on average than using the fallback alone.
         */
        static final Deadlines FIRST_CHOICE = new Deadlines(Duration.ofSeconds(15), Duration.ofSeconds(180));
        /** A slower middle model: its healthy small calls reached 31s in that run, so it gets 45s. */
        static final Deadlines FALLBACK = new Deadlines(Duration.ofSeconds(45), Duration.ofSeconds(180));
        static final Deadlines LAST_RESORT = new Deadlines(Duration.ofSeconds(120), Duration.ofSeconds(240));
        /**
         * MiMo-Flash writes about 60 tokens a second, so a 750-token answer takes about 12s and
         * the slowest of 12 probed calls took 20s: 15s timed out calls that were working. Across
         * three full runs (about 1,900 calls) the slowest healthy large call took 141s and only five
         * took over 95s, while a stalled one never answers: at 300s each stall held a run for five
         * minutes (three of them added about ten minutes to one run). 150s clears every healthy call
         * measured and gives up on a stall two and a half minutes sooner; the request then goes to
         * the next model.
         */
        static final Deadlines SLOW_WRITER = new Deadlines(Duration.ofSeconds(45), Duration.ofSeconds(150));

        /** A prompt this long, or a completion cap this high, is treated as a big batch. */
        static final int LARGE_PROMPT_CHARS = 40_000;
        static final int LARGE_COMPLETION_TOKENS = 16_384;

        /** Large when the model may write a long answer or the prompt itself is big. */
        Duration forRequest(int promptChars, int maxCompletionTokens) {
            boolean large = maxCompletionTokens >= LARGE_COMPLETION_TOKENS || promptChars >= LARGE_PROMPT_CHARS;
            return large ? this.large : this.small;
        }
    }

    OpenRouterClassifierLlm(CompletionsClient client) {
        this(client, null);
    }

    OpenRouterClassifierLlm(CompletionsClient client, CompletionsClient largeClient) {
        this.client = Objects.requireNonNull(client, "client");
        this.largeClient = largeClient != null ? largeClient : client;
    }

    public UsageTotals usageTotals() {
        UsageTotals ordinary = client.usageTotals();
        if (largeClient == client) {
            return ordinary;
        }
        UsageTotals large = largeClient.usageTotals();
        return new UsageTotals(ordinary.calls() + large.calls(), ordinary.promptTokens() + large.promptTokens(),
                ordinary.completionTokens() + large.completionTokens(), ordinary.costUsd() + large.costUsd(),
                ordinary.costMissing() + large.costMissing());
    }

    /** Sum of OpenRouter {@code usage} across calls made by this client. */
    public record UsageTotals(
            long calls, long promptTokens, long completionTokens, double costUsd, long costMissing) {
        public static UsageTotals empty() {
            return new UsageTotals(0, 0, 0, 0, 0);
        }

        public boolean costKnown() {
            return costMissing == 0;
        }
    }

    @Override
    public List<RegionProposal> proposeRegions(RegionLayoutPrompt prompt) {
        return largeClient.perModel(c -> {
            String system = RegionLayoutPromptAssembler.SYSTEM;
            String user = RegionLayoutPromptAssembler.userMessage(prompt);
            CompletionResult result = c.completeJson(system, user, REGION_LAYOUT_MAX_COMPLETION_TOKENS);
            if (result.truncated()) {
                // Deliberation ate the budget; retry with a lead-with-JSON instruction.
                result = c.completeJson(
                        system + "\nPrior response was TRUNCATED. Emit the JSON object immediately,"
                                + " shortest form, no deliberation.",
                        user + "\n\nRetry after truncation: JSON object only.",
                        REGION_LAYOUT_MAX_COMPLETION_TOKENS);
                if (result.truncated()) {
                    throw new IllegalStateException(
                            "OpenRouter region layout truncated after retry: finish="
                                    + result.finishReason());
                }
            }
            return RegionLayoutResponseParser.parse(result.content());
        });
    }

    @Override
    public LayerAJudgment classifyLayerA(LayerAPrompt prompt) {
        return client.perModel(c -> {
            String system = LayerAPromptAssembler.SYSTEM;
            String user = LayerAPromptAssembler.userMessage(prompt);
            List<String> families = prompt.scheduleFamilies();
            CompletionResult result = c.completeLayerA(system, user, families);
            if (result.truncated()) {
                throw new IllegalStateException(
                        "OpenRouter Layer A truncated: finish=" + result.finishReason());
            }
            return LayerAResponseParser.parse(result.content());
        });
    }

    @Override
    public List<LayerBAssignment> bindLayerB(LayerBPrompt prompt) {
        return client.perModel(c -> {
            String system = LayerBPromptAssembler.SYSTEM;
            String user = LayerBPromptAssembler.userMessage(prompt);
            int maxTokens = bindMaxCompletionTokens(user.length());
            CompletionResult result = c.completeJson(system, user, maxTokens);
            if (result.truncated()) {
                result = c.completeJson(
                        system + "\nPrior response was TRUNCATED. Emit the JSON object immediately.",
                        user + "\n\nRetry after truncation: JSON object only.",
                        maxTokens);
                if (result.truncated()) {
                    throw new IllegalStateException(
                            "OpenRouter Layer B truncated after retry: finish=" + result.finishReason());
                }
            }
            return LayerBResponseParser.parse(result.content());
        });
    }

    @Override
    public String classifyCellJson(String systemPrompt, String userPrompt, int maxTokens) {
        return client.perModel(c -> validJson(c.completeJson(systemPrompt, userPrompt, maxTokens).content()));
    }

    /** The content when it parses as JSON; otherwise the model failed and the next one is tried. */
    private static String validJson(String content) {
        try {
            new ObjectMapper().readTree(content);
            return content;
        } catch (Exception e) {
            throw new IllegalStateException("model returned invalid JSON: " + e.getMessage(), e);
        }
    }

    @Override
    public String classifyLayerAJson(String userPrompt, int maxTokens) {
        String systemPrompt = """
                You are a financial document region classifier. Classify each candidate region/table.
                For each region, determine: scheduleFamily, triage (MAIN/HELPER), relevance (PRIMARY/SECONDARY/TERTIARY),
                row labels, column headers, packet default head, and a brief description.
                Return a JSON array with one object per candidate.""";
        return largeClient.perModel(c -> {
            CompletionResult result = c.completeJson(systemPrompt, userPrompt, maxTokens);
            if (result.truncated()) {
                // The reply ran out of room mid-answer: that is the request's budget, not a failed model, and
                // the same prompt sent on down the chain only pays for the whole prompt again. Ask once more
                // with more room before any model is blamed.
                result = c.completeJson(systemPrompt, userPrompt,
                        Math.min(maxTokens * 2, LAYER_A_RETRY_MAX_COMPLETION_TOKENS));
                if (result.truncated()) {
                    throw new IllegalStateException(
                            "OpenRouter Layer A batch truncated after retry: finish=" + result.finishReason());
                }
            }
            return validJson(result.content());
        });
    }

    @Override
    public java.util.Map<String, List<RegionProposal>> proposeRegionsBatch(
            java.util.List<RegionLayoutPrompt> prompts) {
        return largeClient.perModel(c -> {
            String system = """
                    You classify regions on multiple Excel worksheets for a TEV clean financial-model extract.
                    For each worksheet, return ONLY JSON (no markdown):
                    {
                      "main": [{"bbox":"A1:F10","label":"...","why":"..."}],
                      "helper": [{"bbox":"A1:F10","label":"...","why":"..."}],
                      "scratch": [{"bbox":"A1:F10","label":"...","why":"..."}]
                    }

                    DEFINITIONS
                    - main = RETAIN for the model
                    - helper = EXCLUDE from core model (audit / breakout / variance) — do not double-count
                    - scratch = OMIT (floating orphans only)

                    CRITICAL — DO NOT OVER-SPLIT MAINS
                    - Prefer a SMALL number of large mains (ideally ~4 section mains + optional sheet title band).
                    - A section MAIN must be ONE bbox that includes, together:
                      section header + item/detail rows + official section total / Lacs summary figures
                      for that section.
                    - NEVER emit a main that is only a header row.
                    - NEVER emit a main that is only a total row detached from its section.
                    - Document title / units may be one small main OR absorbed into the first section main
                      — but do NOT put floating scratch digits into main.

                    HELPER (keep separate from mains)
                    - Inline BoQ / vendor quote / green-style breakout blocks.
                    - Side variance/scenario pads: alternate + difference columns. Prefer one helper bbox
                      (or few) for that pad band, not dozens of singletons.
                    - Official cost lines on the primary estimate column belong on the SECTION MAIN;
                      only the breakout math under them is helper.
                    - NEVER widen a section MAIN into side pad columns. If a column sits to the right of
                      the primary amount column and looks like another amount band, treat it as helper
                      unless you are sure it is part of the official section schedule.

                    WHEN UNSURE — READ THE FORMULAS
                    - The dump shows formulas (not only cached values). Use them before deciding.
                    - Difference / variance / scenario formulas (e.g. =I9-J9, =J-K, compare-to-quote)
                      → that column (or pad) is HELPER, not main.
                    - Formulas that only restate a primary amount in Lacs / another unit, or pull the
                      same line for a check → HELPER tear-out, not an extension of the main bbox.
                    - Primary section totals that SUM the official estimate column stay on MAIN;
                      do not fold neighboring variance columns into that main just because they
                      share the same rows.
                    - If still ambiguous after reading formulas, prefer a separate helper bbox over
                      merging the side pad into main.

                    SCRATCH
                    - Only unanchored floats / far-right checksums with no section label.
                    - Do NOT mark intermediate cells inside a helper breakout as separate scratch.

                    bboxes must use addresses present in the dump. No invented cells.
                    """;
            String user = formatBatchRegionLayoutPrompt(prompts);
            CompletionResult result = c.completeJson(system, user, REGION_LAYOUT_MAX_COMPLETION_TOKENS);
            if (result.truncated()) {
                result = c.completeJson(
                        system + "\nPrior response was TRUNCATED. Emit the JSON objects immediately, shortest form.",
                        user + "\n\nRetry after truncation: JSON objects only.",
                        REGION_LAYOUT_MAX_COMPLETION_TOKENS);
                if (result.truncated()) {
                    throw new IllegalStateException(
                            "OpenRouter region layout batch truncated after retry: finish="
                                    + result.finishReason());
                }
            }
            return RegionLayoutBatchResponseParser.parse(result.content());
        });
    }

    private static String formatBatchRegionLayoutPrompt(java.util.List<RegionLayoutPrompt> prompts) {
        StringBuilder sb = new StringBuilder();
        sb.append("Classify regions on ").append(prompts.size()).append(" worksheets:\n\n");
        for (int i = 0; i < prompts.size(); i++) {
            RegionLayoutPrompt p = prompts.get(i);
            sb.append("=== WORKSHEET ").append(i + 1).append(": ").append(p.sheetName()).append(" ===\n");
            sb.append("Cell dump (address \\t value-or-formula; formulas are authoritative — use them when a side column might be variance/helper):\n");
            sb.append(p.cellDump()).append("\n\n");
        }
        sb.append("Return a JSON object with sheet names as keys, each containing main/helper/scratch arrays:\n");
        sb.append("{\"SheetName1\": {\"main\": [...], \"helper\": [...], \"scratch\": [...]}, \"SheetName2\": {...}, ...}\n");
        return sb.toString();
    }

    record CompletionResult(
            String content,
            Integer promptTokens,
            Integer completionTokens,
            Integer reasoningTokens,
            String finishReason,
            boolean truncated) {
        CompletionResult {
            Objects.requireNonNull(content, "content");
        }

        static CompletionResult of(String content) {
            return new CompletionResult(content, null, null, null, null, false);
        }
    }

    interface CompletionsClient {
        CompletionResult completeJson(String system, String user, int maxCompletionTokens);

        CompletionResult completeLayerA(String system, String user, List<String> scheduleFamilies);

        default UsageTotals usageTotals() {
            return UsageTotals.empty();
        }

        /**
         * Run a whole operation (call + parse) against this client. A model chain overrides
         * this to try each model in turn when the operation throws.
         */
        default <T> T perModel(java.util.function.Function<CompletionsClient, T> operation) {
            return operation.apply(this);
        }
    }

    @FunctionalInterface
    interface HttpExchange {
        ExchangeResponse send(String jsonBody, Duration deadline) throws Exception;
    }

    record ExchangeResponse(int statusCode, String body) {}

    static final class HttpCompletionsClient implements CompletionsClient {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final String model;
        private final HttpExchange exchange;
        private final LongAdder calls = new LongAdder();
        private final LongAdder promptTokens = new LongAdder();
        private final LongAdder completionTokens = new LongAdder();
        private final DoubleAdder costUsd = new DoubleAdder();
        private final LongAdder costMissing = new LongAdder();

        private final Deadlines deadlines;
        private final Route route;

        HttpCompletionsClient(String apiKey, String model, String url) {
            this(apiKey, model, url, Deadlines.LAST_RESORT);
        }

        HttpCompletionsClient(String apiKey, String model, String url, Deadlines deadlines) {
            this(apiKey, model, url, deadlines, Route.OPENROUTER);
        }

        /**
         * The deadline bounds the whole exchange, response body included. The request
         * timeout alone only covers waiting for the response to begin: a provider that sends
         * headers and then goes quiet would otherwise block the run indefinitely.
         */
        HttpCompletionsClient(String apiKey, String model, String url, Deadlines deadlines, Route route) {
            Objects.requireNonNull(apiKey, "apiKey");
            this.model = Objects.requireNonNull(model, "model");
            this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
            this.route = Objects.requireNonNull(route, "route");
            URI uri = URI.create(url);
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .build();
            this.exchange = (body, deadline) -> {
                HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                        .timeout(deadline)
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json");
                if (route == Route.OPENROUTER) {
                    builder.header("HTTP-Referer", "https://github.com/seemantshankar/resurgent-ai-tev")
                            .header("X-OpenRouter-Title", "TEV Parser");
                }
                HttpRequest request = builder
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                java.util.concurrent.CompletableFuture<HttpResponse<String>> pending =
                        http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                try {
                    HttpResponse<String> response =
                            pending.get(deadline.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                    return new ExchangeResponse(response.statusCode(), response.body());
                } catch (java.util.concurrent.TimeoutException e) {
                    pending.cancel(true);
                    throw new HttpTimeoutException(
                            "no complete response within " + deadline.toSeconds() + "s");
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception exception) {
                        throw exception;
                    }
                    throw e;
                } catch (InterruptedException e) {
                    pending.cancel(true);
                    Thread.currentThread().interrupt();
                    throw e;
                }
            };
        }

        @Override
        public CompletionResult completeJson(String system, String user, int maxCompletionTokens) {
            try {
                System.err.println("[http-client] completeJson: model=" + model + ", maxTokens=" + maxCompletionTokens + ", userLen=" + user.length());
                System.err.flush();
                return post(requestBody(model, system, user, null, maxCompletionTokens, route),
                        deadlines.forRequest(user.length(), maxCompletionTokens));
            } catch (IllegalStateException e) {
                throw e; // already says what failed (a timeout, an HTTP error, an empty reply)
            } catch (Exception e) {
                throw new IllegalStateException("OpenRouter request could not be built: " + e.getMessage(), e);
            }
        }

        @Override
        public CompletionResult completeLayerA(
                String system, String user, List<String> scheduleFamilies) {
            try {
                return post(requestBody(
                        model,
                        system,
                        user,
                        layerAResponseFormat(scheduleFamilies),
                        LAYER_A_MAX_COMPLETION_TOKENS,
                        route),
                        deadlines.forRequest(user.length(), LAYER_A_MAX_COMPLETION_TOKENS));
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("OpenRouter request could not be built: " + e.getMessage(), e);
            }
        }

        @Override
        public UsageTotals usageTotals() {
            return new UsageTotals(
                    calls.sum(),
                    promptTokens.sum(),
                    completionTokens.sum(),
                    costUsd.sum(),
                    costMissing.sum());
        }

        private void recordUsage(String body, CompletionResult result, long latencyMs) {
            calls.increment();
            if (result.promptTokens() != null) {
                promptTokens.add(result.promptTokens());
            }
            if (result.completionTokens() != null) {
                completionTokens.add(result.completionTokens());
            }
            Double cost = route == Route.XIAOMI ? xiaomiCostUsd(body) : costUsd(body);
            if (cost == null) {
                costMissing.increment();
            } else {
                costUsd.add(cost);
            }
            LlmStats.GLOBAL.recordCall(model,
                    result.promptTokens() == null ? 0 : result.promptTokens(),
                    result.completionTokens() == null ? 0 : result.completionTokens(),
                    cachedTokens(body),
                    cost, latencyMs);
        }

        /** The prompt tokens the provider served from its cache, 0 when it does not say. */
        static long cachedTokens(String body) {
            try {
                return MAPPER.readTree(body).path("usage").path("prompt_tokens_details").path("cached_tokens").asLong(0);
            } catch (Exception ignored) {
                return 0;
            }
        }

        /**
         * Xiaomi's reply carries token counts and no cost, so the cost is worked out from its
         * published per-million-token prices (input, input served from cache, output). A model
         * missing from the table has no known cost and is counted as such.
         */
        Double xiaomiCostUsd(String body) {
            double[] price = XIAOMI_PRICES.get(wireModel(model));
            if (price == null) {
                return null;
            }
            try {
                JsonNode usage = MAPPER.readTree(body).path("usage");
                if (!usage.path("prompt_tokens").isNumber() || !usage.path("completion_tokens").isNumber()) {
                    return null;
                }
                long prompt = usage.path("prompt_tokens").asLong();
                long cached = Math.min(prompt, cachedTokens(body));
                long completion = usage.path("completion_tokens").asLong();
                return ((prompt - cached) * price[0] + cached * price[1] + completion * price[2]) / 1_000_000.0;
            } catch (Exception e) {
                return null;
            }
        }

        private static Double costUsd(String body) {
            try {
                JsonNode usage = MAPPER.readTree(body).path("usage");
                if (usage.path("cost").isNumber()) {
                    return usage.path("cost").asDouble();
                }
                if (usage.path("total_cost").isNumber()) {
                    return usage.path("total_cost").asDouble();
                }
            } catch (Exception ignored) {
                return null;
            }
            return null;
        }

        private CompletionResult post(String body, Duration deadline) {
            long startNanos = System.nanoTime();
            long activityId = LlmActivity.GLOBAL.begin(model);
            boolean succeeded = false;
            try {
                System.err.println("[http-client] Sending request to " + model + " via " + route.label + "...");
                System.err.flush();
                ExchangeResponse response = exchange.send(body, deadline);
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                System.err.println("[http-client] Got response HTTP " + response.statusCode() + " after " + elapsedMs + "ms");
                System.err.flush();
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException(
                            route.label + " HTTP " + response.statusCode()
                                    + " " + snippet(response.body()));
                }
                CompletionResult result = contentWithUsage(response.body());
                recordUsage(response.body(), result, elapsedMs);
                succeeded = true;
                return result;
            } catch (IllegalStateException e) {
                throw e;
            } catch (HttpTimeoutException e) {
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                System.err.println("[http-client] HTTP TIMEOUT after " + elapsedMs + "ms");
                System.err.flush();
                throw new IllegalStateException(route.label + " call timed out after " + elapsedMs + "ms", e);
            } catch (Exception e) {
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                System.err.println("[http-client] HTTP ERROR after " + elapsedMs + "ms: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                System.err.flush();
                throw new IllegalStateException(route.label + " call failed: " + e.getMessage(), e);
            } finally {
                if (!succeeded) {
                    LlmStats.GLOBAL.recordFailedCall(model, (System.nanoTime() - startNanos) / 1_000_000);
                }
                LlmActivity.GLOBAL.end(activityId, succeeded);
            }
        }

        static String requestBody(
                String model,
                String system,
                String user,
                ObjectNode responseFormat,
                int maxCompletionTokens)
                throws Exception {
            return requestBody(model, system, user, responseFormat, maxCompletionTokens, Route.OPENROUTER);
        }

        /** Xiaomi's USD per million tokens: input, input served from cache, output. */
        private static final Map<String, double[]> XIAOMI_PRICES = Map.of(
                "mimo-v2.6-flash", new double[] {0.14, 0.0028, 0.28},
                "mimo-v2.6-pro", new double[] {0.435, 0.0036, 0.87});

        /** Xiaomi's own name for a model: OpenRouter's id without the vendor prefix. */
        static String wireModel(String model) {
            return model.startsWith("xiaomi/") ? model.substring("xiaomi/".length()) : model;
        }

        static String requestBody(
                String model,
                String system,
                String user,
                ObjectNode responseFormat,
                int maxCompletionTokens,
                Route route)
                throws Exception {
            ObjectNode root = MAPPER.createObjectNode();
            if (route == Route.XIAOMI) {
                // Xiaomi's API takes its own model name and its own thinking switch, and has no
                // provider routing. Thinking is on by default and then ignores our temperature.
                root.put("model", wireModel(model));
                root.put("temperature", 0.05);
                root.put("max_completion_tokens", maxCompletionTokens);
                root.putObject("thinking").put("type", "disabled");
                // The Layer A json_schema is not offered here; the reply parser checks the values.
                root.putObject("response_format").put("type", "json_object");
                addMessages(root, system, user);
                return MAPPER.writeValueAsString(root);
            }
            root.put("model", model);
            root.put("temperature", 0.05);
            root.put("max_completion_tokens", maxCompletionTokens);
            ObjectNode reasoning = root.putObject("reasoning");
            ObjectNode provider = root.putObject("provider");
            if (isMimo(model)) {
                // MiMo spent hundreds of reasoning tokens on a 500-character answer and
                // sometimes left no content, so it is asked without reasoning here too.
                reasoning.put("enabled", false);
            } else {
                reasoning.put("effort", "low");
            }
            provider.put("data_collection", "deny");
            if (responseFormat != null) {
                root.set("response_format", responseFormat);
            } else {
                ObjectNode format = root.putObject("response_format");
                format.put("type", "json_object");
            }
            addMessages(root, system, user);
            return MAPPER.writeValueAsString(root);
        }

        private static void addMessages(ObjectNode root, String system, String user) {
            ArrayNode messages = root.putArray("messages");
            ObjectNode systemNode = messages.addObject();
            systemNode.put("role", "system");
            systemNode.put("content", system);
            ObjectNode userNode = messages.addObject();
            userNode.put("role", "user");
            userNode.put("content", user);
        }

        private static ObjectNode layerAResponseFormat(List<String> families) {
            ObjectNode format = MAPPER.createObjectNode();
            format.put("type", "json_schema");
            ObjectNode jsonSchema = format.putObject("json_schema");
            jsonSchema.put("name", "layer_a_judgment");
            jsonSchema.put("strict", true);
            ObjectNode schema = jsonSchema.putObject("schema");
            schema.put("type", "object");
            schema.put("additionalProperties", false);
            ObjectNode properties = schema.putObject("properties");
            enumProperty(properties, "scheduleFamily",
                    "one of the offered schedule families, or none",
                    familyEnum(families));
            enumProperty(properties, "triage", "main|scratch|orphan",
                    Triage.MAIN, Triage.SCRATCH, Triage.ORPHAN);
            enumProperty(properties, "relevance", "primary|supporting|noise",
                    Relevance.PRIMARY, Relevance.SUPPORTING, Relevance.NOISE);
            stringArrayProperty(properties, "rowLabels", "CORE row labels", LAYER_A_LABEL_MAX_ITEMS);
            stringArrayProperty(
                    properties, "columnHeaders", "CORE column headers", LAYER_A_LABEL_MAX_ITEMS);
            ObjectNode head = properties.putObject("packetDefaultHead");
            ArrayNode headType = head.putArray("type");
            headType.add("string");
            headType.add("null");
            ObjectNode suggested = properties.putObject("suggestedFamily");
            ArrayNode suggestedType = suggested.putArray("type");
            suggestedType.add("string");
            suggestedType.add("null");
            for (String key : new String[] {"statedScale", "scaleCell"}) {
                ObjectNode nullable = properties.putObject(key);
                ArrayNode nullableType = nullable.putArray("type");
                nullableType.add("string");
                nullableType.add("null");
            }
            objectProperty(properties, "about",
                    "Short paragraph (~4-8 sentences) grounded in CORE: identity, "
                            + "model function, row/column contents, and how amounts "
                            + "should be used (primary vs helper tear-out)");
            ArrayNode required = schema.putArray("required");
            required.add("scheduleFamily");
            required.add("suggestedFamily");
            required.add("triage");
            required.add("relevance");
            required.add("rowLabels");
            required.add("columnHeaders");
            required.add("packetDefaultHead");
            required.add("statedScale");
            required.add("scaleCell");
            required.add("about");
            return format;
        }

        private static String[] familyEnum(List<String> families) {
            List<String> offered = families == null || families.isEmpty()
                    ? ScheduleFamily.seeds()
                    : families;
            String[] values = new String[offered.size() + 1];
            for (int i = 0; i < offered.size(); i++) {
                values[i] = offered.get(i);
            }
            values[values.length - 1] = ScheduleFamily.NONE;
            return values;
        }

        private static void objectProperty(ObjectNode properties, String name, String description) {
            ObjectNode node = properties.putObject(name);
            node.put("type", "string");
            node.put("description", description);
        }

        private static void enumProperty(
                ObjectNode properties, String name, String description, String... values) {
            ObjectNode node = properties.putObject(name);
            node.put("type", "string");
            node.put("description", description);
            ArrayNode enums = node.putArray("enum");
            for (String value : values) {
                enums.add(value);
            }
        }

        private static void stringArrayProperty(
                ObjectNode properties, String name, String description, int maxItems) {
            ObjectNode node = properties.putObject(name);
            node.put("type", "array");
            node.put("description", description);
            node.put("maxItems", maxItems);
            node.putObject("items").put("type", "string");
        }

        private static CompletionResult contentWithUsage(String body) throws Exception {
            JsonNode root = MAPPER.readTree(body);
            JsonNode message = root.path("choices").path(0).path("message");
            String content = message.path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("OpenRouter returned empty message content");
            }
            String finish = root.path("choices").path(0).path("finish_reason").asText(null);
            boolean truncated = finish != null && finish.toLowerCase(Locale.ROOT).contains("length");
            JsonNode usage = root.path("usage");
            Integer promptTokens = integral(usage, "prompt_tokens");
            Integer completionTokens = integral(usage, "completion_tokens");
            Integer reasoningTokens = null;
            JsonNode details = usage.path("completion_tokens_details");
            if (details.path("reasoning_tokens").isIntegralNumber()) {
                reasoningTokens = details.path("reasoning_tokens").intValue();
            }
            return new CompletionResult(
                    content, promptTokens, completionTokens, reasoningTokens, finish, truncated);
        }

        private static Integer integral(JsonNode node, String field) {
            JsonNode value = node.path(field);
            return value.isIntegralNumber() ? value.intValue() : null;
        }

        private static String snippet(String body) {
            if (body == null) {
                return "";
            }
            String trimmed = body.trim().replace('\n', ' ');
            return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
        }
    }
}
