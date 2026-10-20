# M3 real-document benchmark (on demand)

`RealDocumentBenchmarkTest` (tagged `live-extract`) runs the whole pipeline —
ingest → layout → classify → live LlamaCloud extract → bind → M3 assess with the
live document LLM — over real client document sets that live in a local folder
**outside git**. It is never part of the default suite: with no configuration the
class skips cleanly (one skipped test in every `mvn test` run).

## Pointing it at the local folder

```bash
mvn test -Dtest=RealDocumentBenchmarkTest \
    -Dtev.live.extract=true \
    -Dtev.benchmark.dirs="/absolute/path/to/Bhagyanager Solar Ltd. Client Docs" \
    -Dtev.benchmark.packdir="/absolute/path/to/an/earlier/run/packs/general"
```

- `-Dtev.benchmark.dirs` — comma-separated client document folders; **each folder
  is one mandate** and gets its own `workspace.db`.
- `-Dtev.benchmark.packdir` — pack store of an earlier run (the `packs/` folder
  containing `registry.json`, or one of its industry subfolders). When the
  mandate's own store is empty it is copied to `{db-parent}/packs`, so classify,
  extract, bind and assess all resolve induced packs exactly like production.
  Without a matching pack a document stays `unknown` — a pack is never invented.
  The benchmark ingests unlabeled folders, so it classifies with an empty role
  (no role filter) and uses the same live LLM tie-break as the CLI.
- `-Dtev.benchmark.out` — optional output root (default `target/benchmark/`,
  gitignored; the folder name is sanitized to one subdirectory per mandate).
  If the repo sits inside a cloud-synced folder (macOS iCloud Drive under
  `~/Documents`), pass an output root **outside** it, e.g.
  `-Dtev.benchmark.out=/tmp/tev-benchmark`: the sync daemon evicts and copies
  concurrent SQLite writes, which fails commits with `SQLITE_IOERR_DELETE_NOENT`
  and leaves `X 2` conflict folders behind.
- `-Dtev.benchmark.assert=false` — print-only mode (skips the Bhagyanager
  expectation checks).

Required credentials (process env or the repo `.env`):
`LLAMA_CLOUD_API_KEY` (or `llamaparse_api_key`) for LlamaParse/LlamaExtract and
`OPENROUTER_API_KEY` plus `DOC_LLM_MODEL` (or `Excel_Enrichment_Model_id`) for
the document LLM. Anything missing → the test skips with the reason.

## What it prints

Per mandate: every document's classified pack and fact counts, the five assess
stage statuses, country/industries, entities by role, sites with their document
evidence, passages and clause counts, checklist results grouped by status,
conflicts grouped by settlement tier (`rule`/`ai`/`open`), versions-differ rows,
recorded comparison skips, and the LLM cost rollup from `llm_usage` (calls,
tokens and cost per stage plus a total). LlamaCloud bills credits, not USD, so
every `llm_usage` row carries a `cost_unit` (`credits` or `usd`) telling a reader
what its `cost_usd` value counts. The rollup goes through `LlmUsageCosts`, which
prices `credits` rows at `llama.credits_usd_rate` (default 1,000 credits = $1.20)
and passes `usd` rows through, so the total is USD by construction (#152).

## Bhagyanager expectations (issue #149 AC2)

When the folder name contains "bhagyan", the run additionally checks and prints
PASS/FAIL lines: eight sites found; site documents cite a PPA and PVsyst; cost
quotations `provided`; borrower and promoters identified; country India; no
hotel items asked. With assertions on (the default) unmet expectations fail the
run — that is the benchmark reporting a real gap, not a flake.

## Never commit client documents

The document folder lives under `Project Docs/`, which is gitignored. Outputs
are written under `target/`. Nothing from either is ever staged.
