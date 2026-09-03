# FM Loader only; LLM owns semantics

**Superseded in part by [ADR 0012](0012-keep-ingest-and-redact-drop-llm-geometry.md):** the loader still does not interpret sheet meaning at ingest. LLM write-back of region geometry is not the next step.

**Superseded in part by [ADR 0013](0013-restore-lean-trimmed-ingest-signals.md):** shared `cell_style` appearance (bold, `number_format`, fill FG + pattern, per-side borders), `formula_normalized`, and reference edges are again part of the ingest contract (always at ingest, not on-demand). The V13 drop of `cell_reference` and the V14 lean cell trim were temporary; sheet-meaning rules below still hold.

We cut the heuristic semantic stack (region detection, cost-head rollup, formula skeletons, golden snapshots). The Java component is **FM Loader**: ingest workbooks into a cell graph, persist reference edges at ingest, and export redacted sheets.

**Supersedes** ADR 0004 (golden region snapshots), ADR 0005 (golden semantic snapshots), and ADR 0006 (cost-head artifact separation). The conflicting paragraph in ADR 0008 is superseded by this decision.

## What FM Loader does

1. Read `.xlsx`, `.xls`, `.csv` with safety limits and provenance
2. Persist real cell values, formulas, errors, merges, and styles
3. Tokenize formulas and persist reference edges at ingest (ADR 0013)
4. Run ingestion QA gates (cell, reference, and formula reconciliation)
5. Export number-redacted sheet views (ADR 0008)

## What FM Loader does not do

- Detect or classify regions heuristically
- Assign cost heads, trust totals, or leaf coverage
- Score formula skeletons or worksheet roles
- Commit golden snapshots of detector output
- Call an LLM or write region geometry onto cell rows

## Semantic ownership

FM Loader ingest does not interpret sheet meaning. LLM-proposed region geometry was tried and dropped (ADR 0012). Amounts never leave the database.

## Database note

Migration V13 dropped legacy semantic tables (`region`, `cost_head*`, `cell_error_root`) and, at the time, `cell_reference`, rebuilding `cell` / `worksheet` without parser-owned semantic columns. Ingest data (cell values, formulas, styles) was preserved across that upgrade. V14 later lean-trimmed styles and `formula_normalized` from `cell`. **ADR 0013** restores shared `cell_style` (+ `cell.style_id`), `formula_normalized`, and `cell_reference` as ingest contract; region/cost-head tables stay gone.
