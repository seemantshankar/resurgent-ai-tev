#!/usr/bin/env python3
"""Replay captured live classify metrics and fail if estimated TAT is too high.

Symptom: Om Arham live classify on ASSETS+CAPITAL COST took ~35 minutes.
This loop is red-capable without calling the LLM: it reconstructs a lower-bound
wall clock from the metric log (shared pool of Layer A and B).

Usage:
  python3 scripts/classify-tat-from-metrics.py target/om-arham-live-layer-ab.txt
  CLASSIFY_TAT_BUDGET_S=300 python3 scripts/classify-tat-from-metrics.py <report>
Exit 1 = RED (over budget). Exit 0 = GREEN.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path


def _int_field(value: str) -> int:
    if value in ("", "null", "?", "None"):
        return 0
    return int(value)


def parse_metrics(path: Path):
    a, b = [], []
    classify_ms = None
    for line in path.read_text().splitlines():
        if line.startswith("liveLayerA=") or ("classifyMs=" in line and classify_ms is None):
            for part in line.replace(",", " ").split():
                if part.startswith("classifyMs="):
                    classify_ms = int(part.split("=", 1)[1])
        if not line.startswith("metric\t"):
            continue
        parts = line.split("\t")
        # Old: metric layer promptBytes promptTok completionTok durationMs attempts
        # New: metric layer promptBytes promptTok completionTok reasoningTok
        #      visibleChars finish truncated durationMs parseAttempts httpAttempts
        layer = parts[1]
        prompt_tok = _int_field(parts[3])
        completion_tok = _int_field(parts[4])
        if len(parts) >= 12:
            duration_ms = _int_field(parts[9])
            reasoning_tok = _int_field(parts[5])
            truncated = parts[8].lower() == "true"
        else:
            duration_ms = _int_field(parts[5])
            reasoning_tok = 0
            truncated = False
        row = {
            "duration_ms": duration_ms,
            "prompt_tok": prompt_tok,
            "completion_tok": completion_tok,
            "reasoning_tok": reasoning_tok,
            "truncated": truncated,
        }
        (a if layer == "A" else b).append(row)
    return a, b, classify_ms


def pool_makespan(durations_ms: list[int], workers: int) -> int:
    """Greedy lower-bound makespan for a shared worker pool."""
    if not durations_ms:
        return 0
    workers = max(1, workers)
    slots = [0] * workers
    for d in sorted(durations_ms, reverse=True):
        i = slots.index(min(slots))
        slots[i] += d
    return max(slots)


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: classify-tat-from-metrics.py <metrics-report>", file=sys.stderr)
        return 2
    path = Path(sys.argv[1])
    if not path.is_file():
        print(f"missing report: {path}", file=sys.stderr)
        return 2

    budget_s = float(os.environ.get("CLASSIFY_TAT_BUDGET_S", "300"))
    workers = int(os.environ.get("CLASSIFY_LLM_PARALLELISM", os.environ.get("LAYER_B_PARALLELISM", "8")))
    max_a_completion = int(os.environ.get("CLASSIFY_MAX_COMPLETION_TOK_A", "512"))

    a, b, classify_ms = parse_metrics(path)
    a_sum = sum(r["duration_ms"] for r in a)
    b_sum = sum(r["duration_ms"] for r in b)
    pooled_span = pool_makespan([r["duration_ms"] for r in a + b], workers)
    observed_wall_ms = classify_ms if classify_ms is not None else pooled_span

    a_max_c = max((r["completion_tok"] for r in a), default=0)
    b_max_c = max((r["completion_tok"] for r in b), default=0)
    a_over = [r for r in a if r["completion_tok"] > max_a_completion]
    truncated = [r for r in a + b if r["truncated"]]

    print(f"report={path}")
    print(f"layerA_calls={len(a)} layerA_sum_s={a_sum/1000:.1f}")
    print(f"layerB_calls={len(b)} layerB_sum_s={b_sum/1000:.1f} pooled_makespan_s={pooled_span/1000:.1f} workers={workers}")
    print(f"estimated_wall_s={pooled_span/1000:.1f} observed_classify_s={(observed_wall_ms or 0)/1000:.1f}")
    print(f"budget_s={budget_s}")
    print(f"max_completion_tok_A={a_max_c} max_completion_tok_B={b_max_c} A_threshold={max_a_completion}")
    print(f"oversize_A={len(a_over)} truncated={len(truncated)}")
    if a:
        top = sorted(a, key=lambda r: r["duration_ms"], reverse=True)[:3]
        print("top_A_s=" + ", ".join(f"{r['duration_ms']/1000:.1f}(cTok={r['completion_tok']})" for r in top))

    red_reasons = []
    if observed_wall_ms / 1000.0 > budget_s:
        red_reasons.append(
            f"observed classify wall {observed_wall_ms/1000:.1f}s > budget {budget_s:.0f}s"
        )
    if a_max_c > max_a_completion:
        red_reasons.append(
            f"Layer A completion tokens blew past {max_a_completion} (A max {a_max_c})"
        )

    if red_reasons:
        print("VERDICT=RED")
        for r in red_reasons:
            print(f"  - {r}")
        return 1

    print("VERDICT=GREEN")
    return 0


if __name__ == "__main__":
    sys.exit(main())
