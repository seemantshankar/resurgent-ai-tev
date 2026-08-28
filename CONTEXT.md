# TEV Automation — Project Glossary

## Domain terms

- **TEV**: Techno-Economic Viability — the appraisal process this platform automates.
- **FM**: Financial Model — the client-submitted spreadsheet containing project costs, projections, and schedules.
- **FM Loader**: Reads `.xlsx`, `.xls`, and `.csv` workbooks and stores every occupied cell in SQLite. No semantics, no reference graph, no review workflow — bytes in, cells in DB.
- **Cell graph**: Every occupied, hidden, merged, errored, and formula cell from a workbook, stored once per cell with provenance.
- **Parse run**: One execution of the FM Loader against a document.
- **Provenance**: Linkage from a stored cell back to source file, sheet, and coordinate.

## Phase 1 (current scope)

- **FM Loader only**: file adapters (xlsx / xls / csv), safety limits, SQLite persistence, ingest QA (cell count reconciliation).

## Planned (not in repo yet)

- Redacted sheet export for external models
- LLM enrichment and write-back
- Discrepancy engine and analyst review

## Out of scope — do not reintroduce without ADR

Region detection, cost-head rollup, formula reference graph, error cascade tracing, golden snapshots, review CLI, or anything that interprets sheet meaning at ingest time.
