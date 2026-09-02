# TEV Automation — Project Glossary

## Domain terms

- **TEV**: Techno-Economic Viability — the appraisal process this platform automates.
- **FM**: Financial Model — the client-submitted spreadsheet containing project costs, projections, and schedules.
- **FM Loader**: Reads `.xlsx`, `.xls`, and `.csv` workbooks and stores every occupied cell in SQLite. No semantics, no reference graph, no review workflow — bytes in, cells in DB.
- **Cell graph**: Every occupied, hidden, merged, errored, and formula cell from a workbook, stored once per cell with provenance.
- **Parse run**: One execution of the FM Loader against a document.
- **Provenance**: Linkage from a stored cell back to source file, sheet, and coordinate.
- **Redacted sheet**: A throwaway copy of a client workbook tab where numeric literals are replaced by shape-preserving dummy values. Cell addresses, text labels, and formula text stay intact. Real amounts remain only on the cell graph in SQLite. For inspection and later tools, not for edit-and-merge-back.

## Phase 1 (current scope)

- **FM Loader**: file adapters (xlsx / xls / csv), safety limits, SQLite persistence, ingest QA (cell count reconciliation). Cell contract: coord, typed values, formula text + cached value, merges, hidden flags — no styles, quantity parsing, or formula normalization.
- **Redacted export v1** (`tev-parse redact`): after a successful ingest, export one `.xlsx` tab from the original file with numeric literals redacted. Requires `--input`, `--db`, `--mandate-id`, `--sheet`, `--output-dir`. Output: `{output-dir}/{basename}-redacted.xlsx`. Tied to ingest so file hash and parse run stay in sync. `.xlsx` only; one named tab for testing; all tabs in production later.

## Planned (not in repo yet)

- A small **deterministic** (in-code) pass to find tables on a sheet. Not LLM-proposed boxes, and not the pre-LLM heuristic semantic stack.
- Discrepancy engine and analyst review

## Out of scope — do not reintroduce without ADR

LLM-proposed region geometry, the old heuristic stack (cost-head rollup, worksheet-role scoring, trusted totals, golden snapshots), formula reference graph at ingest, review CLI, or anything that interprets sheet meaning during FM Loader ingest.
