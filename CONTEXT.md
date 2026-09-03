# TEV Automation — Project Glossary

## Domain terms

- **TEV**: Techno-Economic Viability — the appraisal process this platform automates.
- **FM**: Financial Model — the client-submitted spreadsheet containing project costs, projections, and schedules.
- **FM Loader**: Reads `.xlsx`, `.xls`, and `.csv` workbooks and stores every occupied cell plus reference edges in SQLite. No sheet-meaning interpretation, no review workflow — workbook facts in, cell graph and edges in DB.
- **Cell graph**: Every occupied, hidden, merged, errored, and formula cell from a workbook, stored once per cell with provenance.
- **Cell style**: A shared appearance record (`cell_style`) referenced by cells via `style_id`. Holds bold, number format, fill foreground colour + pattern, and per-side border style + colour. Distinct paint is stored once; cells reuse it. Null `style_id` means appearance was unavailable from the source format.
- **Reference edge**: One reference token inside a formula, recorded as its own row linking the formula cell to what it points at. Ranges are recorded as a single edge, unexpanded; membership or expansion happens at query time. A reference to a blank coordinate resolves to nothing rather than bringing a cell into existence.
- **Normalised formula**: A deterministic cleaned form of a cell’s formula text (`formula_normalized`) for stable comparison and tokenization. Whitespace and case are cleaned; quoted string literals are never altered. Not a skeleton and not an evaluation.
- **Parse run**: One execution of the FM Loader against a document.
- **Provenance**: Linkage from a stored cell back to source file, sheet, and coordinate.
- **Redacted sheet**: A throwaway copy of a client workbook tab where numeric literals are replaced by shape-preserving dummy values. Cell addresses, text labels, and formula text stay intact. Real amounts remain only on the cell graph in SQLite. For inspection and later tools, not for edit-and-merge-back.

## Phase 1 (current scope)

- **FM Loader**: file adapters (xlsx / xls / csv), safety limits, SQLite persistence, ingest QA (cell / reference / formula reconciliation). Cell contract: coord, typed values, formula text + `formula_normalized` + cached value, merges, hidden flags, `style_id` → shared cell style (bold, `number_format`, fill FG + pattern, per-side borders), and reference edges at ingest — no quantity parsing, header labels, font name/size, or fill background (ADR 0013).
- **Redacted export v1** (`tev-parse redact`): after a successful ingest, export one `.xlsx` tab from the original file with numeric literals redacted. Requires `--input`, `--db`, `--mandate-id`, `--sheet`, `--output-dir`. Output: `{output-dir}/{basename}-redacted.xlsx`. Tied to ingest so file hash and parse run stay in sync. `.xlsx` only; one named tab for testing; all tabs in production later.

## Planned (not in repo yet)

- A small **deterministic** (in-code) pass to find tables on a sheet. Hypotheses are proven by hand on real sheets first, then written as a Java-followable rule, then coded. Not LLM-proposed boxes, and not the pre-LLM heuristic semantic stack.
- Discrepancy engine and analyst review

## Out of scope — do not reintroduce without ADR

LLM-proposed region geometry, the old heuristic stack (cost-head rollup, worksheet-role scoring, trusted totals, golden snapshots), quantity parsing or header labels at ingest, review CLI, or anything that interprets sheet meaning during FM Loader ingest.
