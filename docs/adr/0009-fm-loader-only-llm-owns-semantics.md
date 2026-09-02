# FM Loader only; LLM owns semantics

**Superseded in part by [ADR 0012](0012-keep-ingest-and-redact-drop-llm-geometry.md):** the loader still does not interpret sheet meaning at ingest. LLM write-back of region geometry is not the next step.

We cut the heuristic semantic stack (region detection, cost-head rollup, formula skeletons, golden snapshots). The Java component is **FM Loader**: ingest workbooks into a cell graph, optionally build a reference graph, and export redacted sheets.

**Supersedes** ADR 0004 (golden region snapshots), ADR 0005 (golden semantic snapshots), and ADR 0006 (cost-head artifact separation). The conflicting paragraph in ADR 0008 is superseded by this decision.

## What FM Loader does

1. Read `.xlsx`, `.xls`, `.csv` with safety limits and provenance
2. Persist real cell values, formulas, errors, merges, and styles
3. Tokenize formulas and persist reference edges (on-demand or at ingest)
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

Migration V13 drops legacy tables (`region`, `cost_head*`, `cell_reference`, `cell_error_root`) and rebuilds `cell` / `worksheet` without parser-owned semantic columns. Ingest data (cell values, formulas, styles) is preserved across the upgrade.
