# RCA: RKA-BFP run 7 — missing 14 minutes, 34 failed calls, higher cost, region-count swing

- **Run:** `~/tev-runs/rka-bfp-run7` (`run.log`, `workspace.db`), workbook `Projection_RKA & BFP.xlsx`, 22 sheets, 25,898 cells
- **Date:** 7 Oct 2026, 13:09:46 to 13:48:17 IST
- **Settings:** mimo → luna → gemini chain, `Excel_Enrichment_Large_model_id=openai/gpt-6-luna` (luna first for region layout and Layer A batches), `--parallelism=6`, binder fix from `e5cb22f`, 150 s large-call limit from `797ce95`
- **Compared with:** run 6 (`~/tev-runs/rka-bfp-run6`, 6 Oct, parallelism 3, mimo first everywhere)
- **Evidence:** `run.log` for runs 3–7, `pmset -g log` (macOS power log), TEV source. Wi-Fi logs for the window had already rotated out.

## Summary

| # | Symptom | Root cause | Confidence |
|---|---|---|---|
| 1 | Total time (38:32) is 14½ min longer than classify's own figure (24:01). It was put down to slow ingest. | **The Mac went to idle sleep on battery (11% → 9%) during Layer A.** It slept about 14 minutes in total. Every timer in the pipeline uses `System.nanoTime()`, which stops while macOS sleeps, so the sleep showed up only as a gap between wall-clock time and reported time. Ingest and discover took 31 s. | Confirmed |
| 2 | Failed calls rose from 10 to 34. | **Mostly a local network outage plus one call killed by sleep.** At 13:43:38, 10 open connections to two different providers (Xiaomi API and OpenRouter) failed in the same second with `Operation timed out`. Four of those failed after 240 ms. A degraded network before and after the outage accounts for most of the rest. There were no 429s or 5xx errors. | High |
| 3 | Cost rose from $1.00 to $1.10. | **The fallback breaker sent work to the most expensive model.** After the outage it set mimo and luna aside for 300 s, so Layer B fell through to gemini: 59 calls cost $0.56, which is 51% of the run's cost. The breaker cannot tell a local outage from a provider fault. | Confirmed |
| 4 | Region layout drew 122 regions, against 256 in run 6 and 357 earlier. | **A configuration change, not the network.** Luna-first moved Stage 1 from mimo to luna, and Stage 1 had no fallback in run 7. Network failures *can* change the Stage 1 model (run 3), and the models' own randomness also varies the count, but neither explains run 7. | High |
| 5 | Run 6 vs run 7 speed-up can't be attributed. | **Three code and config changes plus two environmental factors in one run.** | Confirmed |

The suspicion of patchy internet holds for symptoms 2 and 3. Symptom 1 was caused by sleep, not the network, and symptom 4 by configuration.

---

## 1. The missing 14 minutes: machine sleep, not ingest

### What was claimed
The total time (`time` output 38:32) minus classify's `FINISHED - total 1441s` left 14½ minutes. It was attributed to ingest and discover, which run entirely on the machine and make no network calls, and the cause was marked "unknown".

### What the log shows
Ingest and discover finished 31 s after launch:

```
2026-10-07T07:39:46Z  (13:09:46 IST) JVM start
13:10:17 [classify] models in order: ...        <- classify begins
```

The gap is inside classify, in Layer A. The heartbeat prints every 30 s of `nanoTime`, but two of its intervals took far longer than that in wall-clock time:

| Heartbeat line (wall clock) | `elapsed` printed | Wall time since previous line |
|---|---|---|
| 13:13:57 | 02:00 | 128 s |
| 13:22:14 | 02:30 | **497 s** for a 30 s tick |
| 13:30:25 | 06:30 | **237 s** after the last log line |

### What the power log shows
`pmset -g log` for 7 Oct, filtered to the run window:

```
13:10:07  Entering Sleep state due to 'Idle Sleep' ... Using Batt (Charge:11%)    (then many short sleep/dark-wake cycles)
13:15:02  Entering Sleep state due to 'Maintenance Sleep' ... Using Batt (Charge:11%) 426 secs
13:22:08  Wake from Deep Idle ... RTP.keyboard/UserActivity                       (keyboard press woke it)
13:26:34  Entering Sleep state due to 'Idle Sleep' ... Using Batt (Charge:11%) 221 secs
13:30:15  Wake ... RTP.keyboard/UserActivity
13:46:37  Entering Sleep state due to 'Idle Sleep' ... Using Batt (Charge:9%) 19 secs
```

### Reconciliation per stage

| Stage | Wall clock | Reported (`nanoTime`) | Gap | Sleep logged in window |
|---|---|---|---|---|
| Ingest + discover | 31 s | n/a | n/a | ~0 |
| Stage 1 region layout | 61 s | 60 s | 1 s | short dark-wake cycles only |
| **Layer A** | **1,163 s (19:23)** | **344 s** | **819 s** | **843 s** |
| Layer B | 917 s | 877 s | 40 s | 19 s |
| Classify total | 2,280 s (38:00) | 1,441 s (24:01) | **839 s** | 900 s |

The gap and the logged sleep agree to within the dark-wake slop. The 14 minutes is sleep inside Layer A.

### Why it looked like an ingest problem
- `Heartbeat.java:22-24` and `ClassifyService.java:101,223,230` compute elapsed time with `System.nanoTime()`. On macOS this clock does not advance while the machine sleeps.
- Every reported duration (`elapsed`, `STAGE … took`, `FINISHED - total`) is therefore **awake time**, while `time` reports **wall time**. Subtracting one from the other leaves a gap that looks like it belongs before classify.
- The same thing happened in run 6. Its wall-clock classify window was 3,443 s against 3,151 s reported, a 292 s gap, and `pmset` logs 335 s of sleep on battery (22% → 18%) in that window. The "0.5 to 5 minutes before classify" seen in earlier runs was sleep, not ingest.

### Knock-on effect: sleep also wasted awake time
The HTTP deadlines also count awake time. A luna Layer A batch was sent at about 13:11:33, before the 426 s sleep, and its connection died while the machine slept. After waking, the client waited out the rest of its 180 s budget on the dead socket:

```
13:24:17 [status] ... 1 LLM call in flight (longest wait 164s on openai/gpt-6-luna)
13:24:33 [llm-fallback] model openai/gpt-6-luna failed: OpenRouter call timed out after 180021ms - re-sending this request to xiaomi/mimo-v2.6-flash
```

That one call took 13 minutes of wall time and 180 s of awake time. The mimo retry then straddled the 221 s sleep. So Layer A's reported 344 s includes about 3 minutes or more of waiting on a dead call. Without the sleep, Layer A would probably have been shorter still.

---

## 2. Failed calls: 10 → 34

### Breakdown (`LLM_COST` lines, run 7)

| Stage / model | Calls | Failed |
|---|---|---|
| layer-a / luna | 14 | 1 (the sleep-killed call above) |
| layer-b / liquid d1 | 2,113 | 9 |
| layer-b / mimo | 107 | 12 |
| layer-b / luna | 6 | 6 |
| layer-b / gemini | 59 | 6 |
| **Total** | **2,529** | **34** |

### Timeline of the Layer B failures

| Time | Event | Reading |
|---|---|---|
| 13:34:07 | 1 d1 decision call fails | isolated |
| 13:36:35 | 1 mimo 45 s timeout | isolated |
| 13:39:25–33 | **8 d1 calls fail together** (8 in flight, longest wait 11 s) | first network blip. The log gives no error text (see §6). |
| 13:41:51, 13:42:20, 13:43:15, 13:43:20 | 4 mimo 45 s timeouts | the network is degrading |
| **13:43:38** | **10 `IOException: Operation timed out` in one second**, on both Xiaomi and OpenRouter connections. Four came after only 239–240 ms. mimo and luna are each set aside for 300 s. | **local connectivity loss** |
| 13:44:28 → 13:46:28 | 6 gemini calls sent and hang the full 120 s; gemini is then set aside too | network still degraded |
| 13:46:37–56 | Mac sleeps 19 s | |
| 13:47:36–13:48:03 | 3 mimo 45 s timeouts | calls straddling the short sleep, or residual degradation |

### Why this is the local network, not rate limiting or a provider fault
1. **Two independent providers failed in the same second.** Xiaomi's API and OpenRouter share nothing upstream except this machine's internet link.
2. **A 240 ms `Operation timed out` is a kernel `ETIMEDOUT`** on existing sockets. A server's rate limit returns an HTTP 429, and none appeared in the run.
3. **Parallelism 6 is not the cause.** Run 4 ran at parallelism 3 and had 50 failed calls, with the same signature: 12 `Operation timed out` arriving in bursts of 3 (the parallelism) at 18:06:46, 18:07:50, 18:13:43 and 18:33:46. There was no sleep near those times. Run 5, also at parallelism 3, had 5 failures. Failure counts follow the network on the day, not the parallelism setting.
4. **Xiaomi's limit is 100 requests a minute.** Run 7's mimo traffic was 107 calls over the whole of Layer B.

**Contributing:** the run took place at a location with patchy internet, and the user flagged this before any analysis. The Wi-Fi logs for 13:42–13:44 had rotated out, so there is no direct radio-level evidence. The conclusion rests on the cross-provider pattern above.

---

## 3. Cost: $1.00 → $1.10

- Gemini is last in the chain and the most expensive per call. In run 7 it handled 59 Layer B calls for **$0.559**, against $0.144 for 107 mimo calls.
- **Mechanism:** `FallbackCompletionsClient` sets a model aside for 300 s once 3 of its last 10 calls fail (`FallbackCompletionsClient.java:146`). At 13:43:38 the local outage tripped this for mimo and luna at once. For the next few minutes, every Layer B call that would have gone to mimo went to gemini.
- **Design gap:** the breaker assumes failures belong to the provider. When the failure is local, setting providers aside does nothing for reliability. It only moves traffic to a pricier model, which also fails, as gemini's 120 s timeouts at 13:46:28 show.
- **Excluding the outage:** without the gemini share, run 7 would have cost about $0.55–0.60, against run 6's $1.00. Luna-first is cheaper on input-heavy Layer A, as the session's corrected estimate predicted.

---

## 4. Region count: 122 vs 256 vs 357

- **Run 7's Stage 1 ran on luna by configuration.** Its banner reads `big calls (region layout, Layer A batches): openai/gpt-6-luna first`. All 4 Stage 1 calls went to luna and none failed. Runs 3–6 sent Stage 1 to mimo first.
- **The network can change the Stage 1 model.** In run 3 (6 Oct), both mimo region-layout calls timed out at 180 s (17:15:56) and luna answered. So patchy internet *can* add variance to the region layout, but it did not do so in run 7.
- **The model's own randomness is a third source.** OM Arham visible runs 1 and 2 used the same model and produced 87 and 57 regions.
- **Conclusion:** the drop to 122 regions comes from luna drawing coarser regions than mimo. It is reproducible only to the extent that luna is stable from run to run, which a second run on a stable network would show. The fewer regions also explain part of the speed-up (118 Layer A candidates against 248).

---

## 5. Why the run-6/run-7 comparison is confounded

Five things differed between the runs:

| Factor | Kind | Effect on run 7 |
|---|---|---|
| Luna-first for big calls | config | fewer regions, faster Stage 1 and Layer A, cheaper Layer A |
| Parallelism 3 → 6 | config | faster Layer B |
| Binder fix (`e5cb22f`) | code | unbound cells in kept regions fell from 2,031 to 508 |
| ~14 min machine sleep | environment | inflated wall time; ~3+ min awake time wasted on a dead call |
| Local network outage | environment | +24 failures, +$0.56 gemini, ~2–4 min of timeouts |

**Still valid:** classify **awake time** of 24:01 against 52:31 is a real improvement, and the network outage made run 7 *slower*, not faster. Coverage (24,816 cells bound, 508 unbound in kept regions) comes from the code, and the network can't produce it.

**Not valid:** the wall-clock figure (38:32 against 57:53). Both runs include sleep: about 14 min in run 7 and about 5 min in run 6.

**Unknown:** how much of the gain each config change gave, and whether 122 regions holds.

---

## 6. Observability gaps found

1. **No wall-clock or sleep awareness in run output.** `elapsed` and stage durations are awake time only, and nothing in the log says the machine slept. That is why sleep was misread as slow ingest.
2. **d1 failures log no reason.** The 8 failures at 13:39 appear only as a counter (`8 failed`) with no exception text, so their cause can only be inferred.
3. **The power source and battery level are not recorded.** Both runs 6 and 7 ran on battery with idle sleep enabled.
4. **The Stage 1 model is visible only in `http-client` lines.** Nothing in the run summary or the database records which model drew the regions, so region-count changes are hard to trace afterwards.

---

## Corrections to earlier session statements

| Earlier statement | Correction |
|---|---|
| "Ingest and discover … something else is going on there. I don't know what." | Ingest and discover took 31 s. The 14 min was machine sleep during Layer A. |
| "Total minus classify is 14½ minutes this run, against 0.5 to 5 minutes earlier." | It was sleep in every run. Run 6 slept ~5 min on battery. |
| "Layer A: 344 s, against 1,369 s." | Both figures are awake time. Run 7's Layer A took 19:23 of wall time. |
| "Failures came in two bursts (24 at 13:43 and 12 at 13:46)." | Close. The precise counts: 12 fallbacks and 10 socket errors at 13:43, 6 gemini 120 s timeouts at 13:46:28, 3 mimo timeouts at 13:47–13:48, and 9 d1 failures earlier at 13:34 and 13:39. |
| "Patchy internet can also add to the variance in region layout." | True in general (run 3), but not the cause in run 7. The 122 regions come from the luna-first config. |

---

## Recommendations

### Before the next benchmark run (no code)
1. **Keep the machine awake and on AC power.** Wrap the run in `caffeinate`, which holds sleep off for as long as the command runs:
   ```bash
   caffeinate -is <usual run command>
   ```
   `-i` prevents idle sleep and `-s` prevents system sleep on AC power. Plugging in also removes the battery-saver behaviour seen at 9–11% charge.
2. **Run benchmarks only on a stable network.** On patchy links, use a phone hotspot or postpone. Today's decision not to re-run was correct.
3. **Change one variable per run.** Repeat run 7's exact config once on a stable network before changing anything else, so the 122-region layout and the timing can be confirmed.

### Code changes (proposed, not built)
| # | Change | Fixes |
|---|---|---|
| C1 | Print wall-clock and awake time side by side in `Heartbeat` and the stage DONE lines. When the two drift by more than 30 s between ticks, log `[system] machine was asleep for ~Ns`. | §1 misdiagnosis; §6.1 |
| C2 | On detecting a wake, cancel and immediately resend in-flight LLM calls instead of letting them use up their deadline on dead sockets. | §1 knock-on (180 s lost) |
| C3 | Make the fallback breaker outage-aware. If 2 or more *different providers* fail within the same ~5 s window, treat it as a local outage: back off and probe connectivity, then retry the *same* model, rather than setting providers aside and falling to gemini. | §3 cost; part of §2 |
| C4 | Log the exception class and message for d1 decision-call failures. | §6.2 |
| C5 | Record the Stage 1 model, power source and any sleep detected in the run summary and `parse_run` metadata. | §4 traceability; §6.3–6.4 |

C1 and C4 are small and low-risk. C3 has the highest value, because it is the only one that changes cost and failure behaviour on a bad network. It needs tests for the single-provider case, where the existing set-aside behaviour must keep working.

---

## Appendix: commands used

```bash
# ingest/discover duration and stage boundaries
grep -nE "STAGE [123]/3 (START|DONE)|FINISHED" ~/tev-runs/rka-bfp-run7/run.log
# wall-clock gaps in the log
grep -E '^[0-9]{2}:[0-9]{2}:[0-9]{2}' run.log | awk '{split($1,t,":"); s=t[1]*3600+t[2]*60+t[3]; if (p!="" && s-p>=20) print s-p, $0; p=s}'
# sleep/wake during the run
pmset -g log | awk '/^2026-10-07 13:(09|1[0-9]|[2-4][0-9])/' | grep -E "Entering Sleep|Wake from"
# failure timeline
grep -nE "llm-fallback|HTTP ERROR|skipping it for" run.log
```
