# Decision models for Layer B cell typing

Research note, 2026-10-04. Branch `experiment/layer-a-about` at `4b354b7`.

Question: why the decision model settled 101 of 600 cells (17%) in the latest `B  S` run, against 237 of 532 (45%) in the run before it, and what it would take to settle more than 90%.

The two runs compared:

| Run | Code | Workspace | Decision pool | Settled by decision model |
| --- | --- | --- | --- | --- |
| earlier | before `bdf7674` | `bs.db` (another session's scratchpad, 13:32) | 532 | 237 (45%) |
| latest | after `bdf7674` | `bs2.db` (same scratchpad, 14:48) | 600 | 101 (17%) |

Both runs use `liquid/d1`, threshold 0.90, concurrency 8, scope `B  S` plus 16 dependency sheets. The numbers come from `run_stat` in each DB and from the `[cell-decision]` lines in `bs.log` and `bs2.log`.

## Summary

- **Established.** The decision models on OpenRouter take a `state` plus a map of typed questions and return probabilities, not text. A `choice` answer has `choice`, `probabilities` and `confidence`. None of them has an abstain token. "Settling" is just our threshold on `confidence`. For TypeSafe-contract models, `confidence = (p_max − 1/n)/(1 − 1/n)`, so with our 6 options, 0.90 confidence means p_max ≥ 0.917 ([TypeSafe: Confidence](https://docs.typesafe.ai/confidence)).
- **Established.** After `bdf7674`, dependency-sheet cells reach the decision model with almost no structural context. In the latest pool, 6% of cells carry a `region` block, against 99% in the earlier run's pool. The region block is also the only place the **sheet name** and the **"amounts here are stated in lakhs"** sentence travel. 61% of the latest pool has a stated scale that the code knows but never sends.
- **Established.** Dropping regions also shut off the learned dictionary, which only answers inside a known region. About 190 cells that the dictionary typed as money/lakh in the earlier run fell into the decision pool instead. That is why the pool grew from 532 to 600.
- **Established.** The gate `min(kind_conf, scale_conf) ≥ 0.90` also enforces the scale question on percent and count cells, where scale means nothing. In earlier shadow data, scale confidence was the binding constraint for 178 of 1,156 cells. For percent cells it was below 0.90 in 150 of 173.
- **Established.** 66% of the latest pool (397/600) waits on another untyped pool cell. Yet all 600 are asked in one parallel sweep, and nothing re-propagates between the dictionary pass, the decision pass and the chat pass. A replay shows that re-propagating after the dictionary pass alone types 162 of the 600 with no model at all. Asking only "root" cells and re-propagating between rounds needs about 194 model answers to type 545 of the 600.
- **Established.** The highest settle rate measured with regions present was 42–45% (shadow CSVs and the earlier run). Restoring region descriptions brings back the earlier 45% at best. It cannot reach 90% alone.
- **Hypothesis, supported by the docs.** We ask the model to do what the vendors say it is weakest at: reading numbers, and following a formula's indirection (`'P  L '!D29/12*$D$12`) ([OpenRouter skill: decision-model limits](https://github.com/OpenRouterTeam/skills/blob/main/skills/openrouter-decisions/references/decision-model-limits.md), [Jev 1.13 jaggedness](https://docs.typesafe.ai/model-jaggedness/jev-1.13.md)). Telling it the precedents' kinds in plain words should help most for formula cells. Every cell in both pools is a formula cell.
- **Side finding (bug).** `ReadingArithmetic` trims a referenced sheet name, but `CellReadingWriter` keys `sheetIds` by the untrimmed name. Any reference to `'P  L '`, `'B  S '`, `' wORKING CAPITAL'` or `'details of fixed assets  '` therefore fails to propagate. Fixing it is a correctness fix more than a rate fix: the pool falls 600→591 in the replay.
- **Recommended path to >90%:** (1) re-propagate after each typing pass and ask roots first; (2) gate scale only when it matters, and skip it when a stated scale will override it; (3) send the precedents' kinds and labels, the sheet name and the stated scale outside the region block; (4) recalibrate the threshold per model with shadow mode. Estimate: (1) and (2) together leave roughly 14% of the pool for the chat model. Reaching under 10% needs the decision model to settle at least ~70% of root cells, which needs (3). That last part is unmeasured.
- **Not verifiable from primary sources:** Liquid's d1 benchmarks and calibration method (no Liquid blog post or model card found), Mercury Decide's behaviour beyond OpenRouter's catalog text, and whether any of the three models' `confidence` uses the TypeSafe formula. Perplexity says only "the model's own certainty estimate".

## Part 1 — How the decision models work

### The shared contract (OpenRouter Decisions API)

All three models are served behind one OpenRouter endpoint, `POST https://openrouter.ai/api/alpha/decisions`. The request has a `model` (string), a `state` (string, object or array) and a `questions` object keyed by your own IDs ([API reference](https://openrouter.ai/docs/api/api-reference/alphadecisions/submit-a-decisions-request.md); [skill reference](https://github.com/OpenRouterTeam/skills/blob/main/skills/openrouter-decisions/references/decisions-api.md)).

A question is one of three types:
- `noul`: yes/no.
- `choice`: `criteria` maps an option name to its description.
- `score`: `criteria` is an ordered array.

`instructions` and every criterion accept a string or a JSON structure. There is no temperature, no few-shot field, no threshold parameter and no abstain parameter.

Every catalog model lists `supported_parameters: []` ([live catalog](https://openrouter.ai/api/v1/models?output_modalities=decisions)). The `choice` response carries `choice`, an optional `probabilities` map that sums to 1, and an optional `confidence`. `usage` reports `input_tokens`, `output_tokens` and `cost`.

**How it "decides to abstain".** It doesn't. There is no abstain output. The documented mechanisms are:
- the caller sets a threshold on `confidence`;
- the caller adds a no-match option. "Give the full list of options, and add an `other` or `none of the above` option when the list might not cover every input" ([TypeSafe: Primitives](https://docs.typesafe.ai/primitives)). The OpenRouter skill says the same: "`choice` includes a no-match option (`none`, `not_stated`) when nothing may fit" ([skill](https://openrouter.ai/skills/openrouter-decisions)).

**Confidence.** TypeSafe defines it for `choice` as `(p_max − 1/n) / (1 − 1/n)`, which is 1 when all the probability is on one option and 0 for an even split ([TypeSafe: Confidence](https://docs.typesafe.ai/confidence)). Low confidence "often means the levels are ambiguous, multi-dimensional, or the state doesn't contain enough to go on" (same page). D1 and Mercury Decide use "the same /v1/systemone schema as Jev" (OpenRouter model descriptions, below). Whether they also use the same confidence formula is **not stated** anywhere I found.

**Questions are independent.** "Every answer is independent. One question's answer is not hidden context for another" ([TypeSafe: Primitives](https://docs.typesafe.ai/primitives)). Our `scale` question therefore does not know the `kind` answer. A percent cell still has to choose among six money scales.

**Batching.** Many questions over the same state go in one request. "Adding questions barely changes the response time and costs only the tokens for the extra questions" ([TypeSafe: Primitives](https://docs.typesafe.ai/primitives); [Liquid guide](https://docs.liquid.ai/guides/decision-model-guide)). No source describes batching many *items* (cells) into one state. The guidance runs the other way: "Accuracy drops as unrelated content grows" ([decision-model limits](https://github.com/OpenRouterTeam/skills/blob/main/skills/openrouter-decisions/references/decision-model-limits.md)). The one documented example parallelises one request per item with 8 workers ([Jev classification cookbook](https://openrouter.ai/docs/cookbook/evaluate-and-optimize/jev-classification.md)).

**Prompt structure and what improves accuracy.**
- **State:** "Use an object for most requests so each part of the state has a descriptive name" ([TypeSafe: State](https://docs.typesafe.ai/concepts/state)). "Include only what questions read … Every state field must be read by at least one question" ([skill](https://openrouter.ai/skills/openrouter-decisions)).
- **Instructions and criteria:** put the whole question in `instructions`. Question IDs are not sent to the model ([TypeSafe: Primitives](https://docs.typesafe.ai/primitives)).
  - Write criteria "as an extension of the instructions" ([limits](https://github.com/OpenRouterTeam/skills/blob/main/skills/openrouter-decisions/references/decision-model-limits.md)).
  - When options are easily confused, use structured criteria objects with `what`, `not_for` and `examples` ([TypeSafe: Choice](https://docs.typesafe.ai/primitives/choice)). This is the only documented form of label descriptions plus few-shot examples.
- **Phrasing:** ask about the fact itself, not about what the text "states" or "says". Such questions "suppress inference … and read low when the fact is plain but not spelled out" ([limits](https://github.com/OpenRouterTeam/skills/blob/main/skills/openrouter-decisions/references/decision-model-limits.md)).
- **Thresholds:** probe 100–200 labelled items and pick the band that meets your accuracy bar ([Jev classification cookbook](https://openrouter.ai/docs/cookbook/evaluate-and-optimize/jev-classification.md); that example measured 93% accuracy at ≥0.8 confidence and 50% at 0.5–0.8). "Thresholds do not carry across models or primitives" ([skill](https://openrouter.ai/skills/openrouter-decisions)). Pin the dated `canonical_slug`, not an alias ([models.md](https://github.com/OpenRouterTeam/skills/blob/main/skills/openrouter-decisions/references/models.md)).

**Documented failure modes that apply to us** ([decision-model limits](https://github.com/OpenRouterTeam/skills/blob/main/skills/openrouter-decisions/references/decision-model-limits.md), verified on Jev 1.13; [Jev 1.13 jaggedness](https://docs.typesafe.ai/model-jaggedness/jev-1.13.md)):
- **Math and numbers:** "Cannot add, compare magnitudes … Compute in code, then pass the result or a named bucket."
- **Indirection:** "property-of-a-property questions lose accuracy. Ask directly, in one hop, and name the state field." A formula like `E25*$D$27` is indirection the model cannot resolve.
- **Literal reading:** see the "states/says" note above.
- **Option order:** Jev "may be influenced by the order of a Choice's options".
- **Generation:** "Not trained to produce text or values." This is why D1 cannot name a unit or currency; `CellDecisionClient.java:3-6` already says so.

### Per model

| | `liquid/d1` | `perplexity/pplx-decider-v1-27b` | `inception/mercury-decide:free` |
| --- | --- | --- | --- |
| Pinned build | `liquid/d1-20260930` | `perplexity/pplx-decider-v1-27b-20261001` | `inception/mercury-decide-20260930` |
| Context | 65,536 | 262,144 | 32,768 |
| Price (OpenRouter) | $0.04/M input, $0 output | $0.04/M input, $0 output | free |
| Input | text | text + image (OpenRouter: text/JSON only for now) | text |
| Base model | not disclosed | Qwen3.8-27B, Apache-2.0 weights | not disclosed |
| Confidence | "probability taken directly from the model" | "the model's own certainty estimate" | "calibrated probability … reports how certain it is" |
| Limits stated | none beyond context | 1–128 questions/request, 1–255 options, 10 req/s per org (Perplexity API) | free tier: 20 req/min, 50 or 1,000 req/day |

Catalog figures come from [the live catalog](https://openrouter.ai/api/v1/models?output_modalities=decisions) and the per-model endpoints listings (for example [`/api/v1/models/liquid/d1-20260930/endpoints`](https://openrouter.ai/api/v1/models/liquid/d1-20260930/endpoints)), fetched 2026-10-04. Each listing shows one provider, `uptime_last_1d` 100, and no latency figure.

**Liquid D1.**
- OpenRouter: "D1 is Liquid AI's structured decision model, served as a System One endpoint … returns a choice, a score, or a yes/no answer, each with a probability taken directly from the model rather than written out as text" ([openrouter.ai/liquid/d1](https://openrouter.ai/liquid/d1)).
- Liquid's docs: decision models return "calibrated probabilities across a fixed set of outcomes in a single call with zero generated tokens". Liquid shows thresholding examples (`p > 0.8` block, `< 0.2` allow, otherwise human review) and says "if the answer is one of N known options, use a decision model" ([Liquid: Decision Model Guide](https://docs.liquid.ai/guides/decision-model-guide), [Liquid: Decision Models](https://docs.liquid.ai/lfm/models/decision-models)).
- **Not found in primary sources:** context limits on Liquid's own API, option limits, rate limits, latency, how calibration was done, and the benchmark claims (beating Jev on a "Decision Index"). Those appear only in press coverage, which I have not relied on.
- Our measured latency: 572 ms per call on average across 599 calls, about 596 input tokens per call, $0.0143 per run (`llm_usage`, `bs2.db`).

**Perplexity Decider V1 27B.**
- OpenRouter: it "returns typed, probabilistic answers … A request can carry up to 128 questions about the same content. On OpenRouter it currently accepts text and JSON `state`" ([openrouter.ai/perplexity/pplx-decider-v1-27b](https://openrouter.ai/perplexity/pplx-decider-v1-27b)).
- Perplexity's API docs give these limits ([Perplexity: Decisions quickstart](https://docs.perplexity.ai/docs/decisions/quickstart)). They apply to Perplexity's own endpoint; OpenRouter's limits for it are not documented.
  - 1–128 questions, 1–255 options, 1–10 score levels.
  - Under 262,144 input tokens and a 32 MiB body.
  - 10 requests/s per organisation.
  - Latency "under 2 seconds" for hundreds of tokens, up to about 23 s near the limit.
  - "Confidence on choice and score answers is the model's own certainty estimate". This is a different wording from TypeSafe's formula, so thresholds will not transfer.
- The model card says it is fine-tuned from Qwen3.8-27B and reports 85.71% across 11 benchmarks, FinancialPhraseBank and TabFact among them ([Hugging Face](https://huggingface.co/perplexity-ai/pplx-decider-v1-27b)). It says nothing about few-shot support, abstention or numeric reasoning.

**Inception Mercury Decide (free).**
- OpenRouter: "returns a choice, a score, or a yes/no answer, each with a calibrated probability … makes up to 14 decisions per second and reports how certain it is, so a decision system can run it on every case and escalate the unsure ones to a human. Mercury Decide uses the same /v1/systemone schema as Jev" ([openrouter.ai/inception/mercury-decide:free](https://openrouter.ai/inception/mercury-decide:free)).
- **No Inception primary documentation found.** Neither [docs.inceptionlabs.ai](https://docs.inceptionlabs.ai/get-started/models) nor the Inception blog mentions it.
- The `:free` variant is bound by OpenRouter's free-model limits: 20 requests/minute, and 50 requests/day (under 10 credits purchased) or 1,000/day ([OpenRouter: Limits](https://openrouter.ai/docs/api_reference/limits)). At one request per cell, a 600-cell pool exceeds the per-minute limit at our concurrency of 8 (about 12 cells/s), and more than one run a day exceeds the daily cap. Treat it as unusable for this workload unless a paid `inception/mercury-decide` endpoint is used.

**Where they differ.** Context (32K to 262K) only matters if we put many cells into one state. Today's states are about 600 tokens, mostly question text. The confidence semantics differ (formula vs "own estimate"), so each model needs its own threshold. Perplexity alone documents per-request question limits (128). Mercury's free tier is rate-limited out of contention.

## Part 2 — How our code uses them

### Request shape

`OpenRouterDecisionClient` (`src/main/java/com/resurgent/tev/parser/classify/OpenRouterDecisionClient.java`):
- **Request:** POSTs to `/api/alpha/decisions` (`:18`) with a 30 s deadline (`:20`). The body is `{model, state, questions}` (`:84-92`), with two `choice` questions:
  - `kind`, with 6 options: money, quantity, rate, percent, count, ratio (`:26-31`). Its instructions: "Judge from region.about, row_label, column_label, part_of and row_note, not from the size of the number" (`:89`).
  - `scale`, with 6 options: unit, thousand, lakh, million, crore, billion (`:32-37`). Its instructions: "A region.about, row_label or column_label may say Lacs …; if none does, the scale is unit" (`:90`).
- **Criteria** are one-line strings. There is no `none`/`not_stated` option and no `not_for`/`examples` structure.
- **State** is sent as a JSON object when it parses as one (`:95-107`).
- **Response:** the parser reads `choice` and `confidence` for each question and **discards `probabilities`** (`:134-146`).

### Per-cell context

`CellTypeClassifierLlm.formatDecisionState` (`CellTypeClassifierLlm.java:385-416`) builds the state:
- `region {family, head, sheet, about}`, **only when** the cell's region has a schedule family or an about (`:387-393`);
- `cell {coord, display, formula}`;
- `row_label`, `column_label`, `part_of`, `row_note` from `CellContextBlock.Parts` (`:398-405`);
- `neighbours`: the up/down/left/right numeric cells with their display and current kind (`:406-414`, built at `:709-733`).

**Not sent:**
- the sheet name outside the region block;
- the stated scale outside `region.about`, because `ResolverCellContext.region` appends the "Amounts here are stated in …" sentence to `about` (`ResolverCellContext.java:103-107`);
- the precedents of a formula, their kinds and their labels;
- the number format.

### Batching and the settle rule

- **Batching:** one cell per request, 8 in flight, asked in slices of max(8×8, 50) (`CellTypeClassifierLlm.java:356-378`; concurrency from `LlmEnvironment.java:134-148`).
- **"Settled":**
  - The call must succeed and `Decision.confidence() = min(kindConfidence, scaleConfidence)` (`CellDecisionClient.java:12-14`) must reach `decisionMinConfidence`. The default is 0.90 (`CellTypeClassifierLlm.java:39`); override with `Excel_Enrichment_Cell_decision_min_confidence` (`LlmEnvironment.java:117-131`). The gate is at `CellTypeClassifierLlm.java:326`.
  - The answer must also survive `applyResponse`: an unknown scale leaves the cell untypable and defers it (`:336-340`).
  - For money, a scale stated by the sheet or region **overrides** the model's scale anyway (`:517-523`). The gate still requires the scale confidence that the override then throws away.
- **"Fell through":** a failed call, low confidence or a rejected answer sends the cell to the chat model (`:314-341`).
- **No abstain token is used.**
- **Order in Layer B:**
  - pass 1 sends inputs; pass 2 sends formulas (`CellReadingWriter.java:155-177`);
  - inside each pass: static and learned dictionary, then the decision model on everything left, then chat (`CellTypeClassifierLlm.java:170-211`);
  - formulas re-propagate only once, after pass 1 (`CellReadingWriter.java:164-174`), and never after the dictionary or decision answers of pass 2.

### Configuration

- `decisionModelId` reads `Excel_Enrichment_Cell_decision_model_id`, then `Excel_Structured_Decision_Model_id` (`LlmEnvironment.java:91-99`).
- The `.env` also sets `Excel_Structured_Decision_Model2_id=inception/mercury-decide:free`. **No code reads it.**
- The tests pin this behaviour:
  - `LlmEnvironmentDecisionModelTest:23-40`;
  - the state shape with a known region (`CellDecisionModelTest:72-100`);
  - the 0.90 default (`CellDecisionModelTest:159-180`).
- No test covers a cell with no region.

### What `bdf7674` removed

`ClassifyService` now keeps `structureWorksheetIds` (only the named sheets) apart from `scopeWorksheetIds`, which adds the dependency sheets (`ClassifyService.java:60-66`, `:140`). Region layout, Layer A, header geometry and binding run only on `structureWorksheetIds` (`:109`). The typing scope still includes the dependency sheets (`:105`).

In `bs2.db`, only `B  S` has `packet_disposition` rows (7) and `region_header_geometry` rows (2). The 16 dependency sheets have none. In `bs.db` they had them: for example `P  L` had 11 dispositions and 5 geometries, `Interest` 6 and 1.

What that removes from the decision state, measured on the exact pools:
- `region` is absent, which takes `family`, `head`, `sheet` and `about` with it, including the stated-scale sentence;
- `part_of` and `row_note` are absent, because they need header geometry (`ResolverCellContext.java:72-89`);
- the learned dictionary is off, because `tryLearnedTyping` returns null when the region is unknown (`CellTypeClassifierLlm.java:865-869`). That moves cells into the decision pool.

**Method.** The pools were rebuilt offline by replaying `CellReadingWriter`'s Layer B on copies of both DBs. A recording `CellDecisionClient` returned confidence 0, and a chat stub failed every call, so no network was used. The replay reproduces the latest run exactly: 1,301 cells typed by the dictionary, 600 sent to the decision model, every coord in the same order. The earlier run replays to 435 cells instead of 532, because the learned dictionary on disk has grown since; treat its numbers as approximate.

| Field in decision state | Earlier pool (replay, n=435) | Latest pool (exact, n=600) |
| --- | --- | --- |
| `region` block (also carries sheet name) | 99% | **6%** |
| stated-scale sentence in `region.about` | 57% | **6%** |
| stated scale known to code (`StatedScales.of`) | 58% | 61% |
| `row_label` | 30% | 50% |
| `column_label` | 84% | 86% |
| `part_of` / `row_note` | 1% / 4% | 0% / 0% |
| `formula` | 100% | 100% |
| a neighbour with a known kind | 25% | 30% |
| cross-sheet formula | 7% | 14% |

So the suspected cause is **confirmed as a loss of context**: the region, the sheet name and the stated scale disappear for 94% of the pool. Row labels did not get worse; without geometry the fallback resolver finds more of them. Whether this loss **caused** the 45%→17% drop is a **strong hypothesis, not measured**: no per-cell decision outcomes are persisted, only totals. The `bs.db` average input of 714 tokens per call against 596 in `bs2.db` matches the region block (about 120 tokens) going missing.

### The pool also changed composition

410 cells are in both pools. The latest pool adds 190 cells, mostly on `P  L` (106), `SALESPROJECTION` (33), `wORKING CAPITAL` (22) and `Details` (17). In the earlier run, 169 of those 190 were stored as money/lakh, `derived`: in this flow that is the learned dictionary, which needs a known region and a stated scale. So the latest pool has more money-scale cells, which are exactly the cells whose scale the model can no longer see.

### Why pool cells are untypable in the first place

All 600 are formula cells. Pass 1 had nothing to send. Classifying their precedents as they stood just before the decision call:
- **563** wait on an untyped numeric precedent;
- **34** reference a sheet whose name has leading or trailing spaces;
- 2 are refused arithmetic (`G18+5%`, `D63*10%`);
- 1 has no numeric precedent.

397 of the 600 have a precedent that is itself in the pool, and chains run up to 121 deep (an `Interest` running balance). Many precedents were typed by the dictionary seconds before the decision call, but nothing re-propagated.

**The sheet-name bug.** `CellReadingWriter.java:85` stores `sheetName().toLowerCase()`, but `ReadingArithmetic.java:380` looks up `name.trim().toLowerCase()`. `'P  L '!D29` becomes `p  l` and finds no sheet.

### Earlier shadow data (whole workbook, regions present)

The four `reports/d1_compare_*.csv` files from 2026-10-03 have 1,084–1,156 cells each, all compared with the chat model. At ≥0.90 on `min(kind, scale)`, 42% settle with 99.6% agreement. The scale question is often the limiting factor:

| Gate (latest CSV, n=1,156) | Settled | Agreement with chat (kind, plus scale for money) |
| --- | --- | --- |
| min(kind, scale) ≥ 0.90 (today) | 42% | 99.6% |
| min ≥ 0.80 | 54% | 98.2% |
| kind ≥ 0.90, scale gated only when D1 says money | 51% | 99.1% |
| kind only ≥ 0.90 (upper bound when stated scale overrides) | 58% | 99.3% (kind) |
| kind only ≥ 0.80 | 71% | 98.1% (kind) |

Below 0.90, scale alone was the limit for 178 cells, kind alone for 142, both for 346. When D1 said percent, scale confidence was below 0.90 for 150 of 173 cells. Among money cells with low scale confidence, the commonest pair was D1 `unit` against chat `lakh` (161). This is the case the instruction "if none does, the scale is unit" pushes D1 into when the scale sentence is missing. The commonest low-kind-confidence pair was money/money (259): D1 was right but unsure.

## Part 3 — Gap analysis and ranked recommendations

Each item says what it fixes, the evidence, the expected effect and how to measure it. **E** marks established facts, **H** marks hypotheses.

**1. Re-propagate between passes and ask roots first.** (code, no model change)
- **Change:**
  - After the dictionary pass and after each decision round, rerun `propagate` on the untyped formulas.
  - Ask the decision model only about pool cells whose numeric precedents are all typed (roots).
  - Apply the answers, propagate, repeat a few rounds, then send what is left (deep serial chains) in one sweep.
- **Evidence (E):**
  - Replay on `bs2.db`: re-propagating right after the dictionary types 162 of 600.
  - With root-only rounds, using the latest run's stored answers as the model's answers, 194 cells need a model answer and 545 of 600 end up typed.
  - Most of the 55 rounds are a one-cell chain, so cap the rounds.
  - For cells that propagation typed instead of the model, the kind matched the latest run's model answer in 352 of 399 (88%). The 47 disagreements need a look before trusting either side.
- **Expected effect:** 60–70% fewer cells reach any model. It does not change D1's rate per cell asked, but it changes what share of the pool needs chat.
- **Measure:**
  - Add `cells_typed_repropagation` and per-round counts to `LlmStats`.
  - Report "pool cells needing chat / pool".
- **Caveat:** this redefines the metric. The decision model's own rate on the cells it is asked stays as a separate stat.

**2. Gate per question, and skip a scale the code will override.** (code)
- **Change:**
  - Settle on `kindConfidence ≥ T` when the kind is not money (scale is "unit" by definition).
  - For money, require `scaleConfidence ≥ T` only when `ctx.statedScale(cell)` is null. When a stated scale exists, `applyResponse` replaces the model's scale anyway (`CellTypeClassifierLlm.java:517-523`).
  - Better: don't ask the scale question for cells with a stated scale.
- **Evidence (E):**
  - Shadow CSV: 42%→51% at 0.90 even before using stated scales; kind-only upper bound 58%, agreement ≥99%.
  - 61% of the latest pool has a stated scale.
  - The docs back per-question gating: "Thresholds do not carry across … primitives" ([skill](https://openrouter.ai/skills/openrouter-decisions)); "One judgment per question, combined in code" ([limits](https://github.com/OpenRouterTeam/skills/blob/main/skills/openrouter-decisions/references/decision-model-limits.md)).
- **Expected effect:** +10 to +15 points on the decision-model rate.
- **Measure:** shadow mode with stated scale added to the CSV; agreement per gate rule.

**3. Send the facts the model needs as named fields, outside `region`.** (prompt/state)
- **(E) Always send** `sheet` and `stated_scale` (for example `"lakh"`, from `StatedScales`) as top-level state fields, whether or not a region is known.
- **(H) Send `inputs`:** for each precedent of a formula cell, `{ref, kind, scale, row_label}` as computed in code.
  - This is the "compute in code, then pass … a named bucket" and "ask directly, in one hop" pattern ([limits](https://github.com/OpenRouterTeam/skills/blob/main/skills/openrouter-decisions/references/decision-model-limits.md)).
  - In the latest pool, 451 of 600 cells end with every precedent typed, and 203 had every precedent typed at decision time.
  - A cell like `P  L!D2 = Pages!D28` with no labels currently reaches the model as only `{coord, display, formula}`.
- **(E) Fix the instructions.** Stop naming fields that may be absent. Drop the "says … if none does, the scale is unit" wording, which the limits page flags as suppressing inference.
  - For scale, state the rule as a property: "The scale the amounts in this cell's sheet are expressed in."
  - When `stated_scale` is present the question is moot (see 2).
- **Expected effect:** this is what lifts the per-cell rate from about 45–58% towards 70%+. **Unmeasured.**
- **Measure:** an A/B in shadow mode on the same pool (`Excel_Enrichment_Cell_decision_compare=true`). Compare the confidence distribution and the agreement per state variant. Add `sheet`, `has_region` and `stated_scale` columns to the CSV first (`DecisionComparison.java:102-109`).

**4. Bring back a light region description for dependency sheets, without full Layer A.** (pipeline)
- **Change, three options:**
  - reuse `packet_disposition` rows from an earlier full run of the same workbook (same file hash);
  - or run Layer A only, without region layout and binding, on dependency sheets that reach the decision pool. That is 11 sheets here, and Layer A for 7 candidates took 4 s;
  - or send a sheet-level summary (sheet name plus title rows) as `sheet_about`.
- **Evidence:** (E) the region block was present for 99%→6% of the pool. (H) the 45%→17% drop follows from it. The learned dictionary also needs a known region; 190 extra cells reached the pool.
- **Expected effect:** back to roughly the earlier 45%, and a pool about 190 cells smaller. Not enough on its own.
- **Measure:** rerun the `B  S` scope and compare `cells_to_decision_model` and `cells_settled_decision_model` with `bs.db` and `bs2.db`.

**5. Recalibrate the threshold per model on our own data.** (config/process)
- **Evidence (E):**
  - Confidence semantics differ by vendor: TypeSafe's formula against Perplexity's "own certainty estimate".
  - "Thresholds do not carry across models".
  - The 0.90 default was measured once, on OM Arham with regions present (`CellTypeClassifierLlm.java:38`).
  - At 0.80 the shadow data still agrees 98%.
- **Change:** keep a threshold per pinned `canonical_slug`, and log the response `model` with each run.
- **Measure:** `DecisionComparison` bands, per model and per gate rule from item 2.

**6. Use the documented criteria format, and test a no-match option.** (prompt, H)
- **Change:** criteria objects with `what`, `not_for` and `examples` ([TypeSafe: Choice](https://docs.typesafe.ai/primitives/choice)) for the pairs D1 confuses: percent↔money (45), count↔money (32), money↔rate (7).
- **Caution:** a `none` option ([TypeSafe: Primitives](https://docs.typesafe.ai/primitives)) changes `n` in the confidence formula. Its value is that a "none" answer is an honest fall-through rather than a low-confidence guess. Measure before adopting.
- **Also test** option order (money is listed first) per the Jev jaggedness note.
- **Measure:** shadow A/B on the confusion pairs.

**7. Read `probabilities`, not just `choice`/`confidence`.** (code, small)
- **Change:** keep the parsed distribution (`OpenRouterDecisionClient.java:134-146`). The top-2 margin and the mass on money-like options support rules such as "kind ∈ {money, rate} with combined p ≥ 0.95 and labels say per-unit → rate".
- **Expected effect:** small, but it makes the CSV more diagnostic.

**8. Model choice.** (config)
- Do not use `inception/mercury-decide:free` at this volume (20 rpm and daily caps, see Part 1).
- `perplexity/pplx-decider-v1-27b` is the only model documenting 128 questions per request and a 262K context. That makes a multi-cell state with one question per cell (`instructions` naming `cells.E12`) testable. It is untested and runs against the large-state warning, so it is an experiment, not a recommendation.
- Wire `Excel_Structured_Decision_Model2_id` or remove it from `.env`; today it is silently ignored.

**9. Fix the sheet-name key bug.** (correctness)
- **Change:** trim the keys in `CellReadingWriter.java:85`, or stop trimming at `ReadingArithmetic.java:380`.
- **Evidence (E):** cross-sheet references to 4 padded sheet names never propagate. In the replay with trimmed keys, the pool falls 600→591 and 125 cells move from the dictionary to propagation.
- **Expected effect** on the decision rate is small. The fix matters because formula types should come from the formula (ADR 0025 §3), not from a label guess.

**Rough path to >90% "pool cells settled without the chat model"** (estimate):
- items 1 and 9 cut what the decision model is asked to about 190 of 600 cells;
- items 2 and 5 should take D1's rate on those to 55–70%;
- that leaves 55–85 cells for chat, roughly 9–14% of the pool, before item 3.

Reaching the goal reliably needs item 3 to push D1's rate on root cells to 70% or more. If ">90%" means the decision model's own rate on the cells it is asked, no evidence here suggests these models reach it at ~99% agreement. Even with regions, the best measured kind-only rate is 58% at 0.90.

## Open questions

- Which cells did D1 settle in the latest run? Per-cell decisions are not persisted. Running the same scope once in shadow mode, with the CSV extended (item 3), would settle the hypothesis in item 4 at about $0.015 of D1 cost.
- Does the latest run's `count` reading for 140 pool cells reflect the truth? The earlier replayed pool had 106. All 140 are on dependency sheets, 101 of them on `Interest`. If the chat model is also degraded without context, agreement with chat is a weaker yardstick for calibration.
- Do D1 and Mercury compute `confidence` with TypeSafe's formula? Neither vendor documents it. Checking the formula against returned `probabilities` on a few responses would answer it.
- What are OpenRouter's own rate limits for paid decision models? None are documented ([Limits](https://openrouter.ai/docs/api_reference/limits) covers free variants only).
- Should the 47 cells where propagation and the stored model answer disagree on kind be reviewed? It would show whether propagation or the model is wrong before item 1 makes propagation authoritative for them.
