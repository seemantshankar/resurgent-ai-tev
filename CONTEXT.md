# TEV Automation — Project Glossary

## Domain terms

- **TEV**: Techno-Economic Viability — the appraisal process this platform automates.
- **FM**: Financial Model — the client-submitted spreadsheet containing project costs, projections, and schedules.
- **FM Loader**: Reads `.xlsx`, `.xls`, and `.csv` workbooks and stores every occupied cell in SQLite. No semantics, no reference graph, no review workflow — bytes in, cells in DB.
- **Cell graph**: Every occupied, hidden, merged, errored, and formula cell from a workbook, stored once per cell with provenance.
- **Parse run**: One execution of the FM Loader against a document.
- **Provenance**: Linkage from a stored cell back to source file, sheet, and coordinate.
- **Redacted sheet**: A throwaway copy of a client workbook tab where numeric literals are replaced by shape-preserving dummy values. Cell addresses, text labels, and formula text stay intact. Real amounts remain only on the cell graph in SQLite. Built for LLM input, not for edit-and-merge-back.

## Regions and enrichment

- **Region**: An AI-proposed bounding box on a worksheet that groups one distinct table — its title, unit banner, headers, and all cells that belong to it. One box per table; not a parent wrapping children.
  _Avoid_: Heuristic parser detection, tag (when meaning a box)

- **Region purpose**: Why a region exists in the appraisal — **Required** (live tables the appraisal should use), **Scratch** (unused working calculations nothing Required references), or **Orphan** (unused leftovers or stray comments belonging to no table). If a Required table’s formula uses another region, that region becomes Required too.
  _Avoid_: tag, region type

- **Region type**: A label from a single shared menu across all TEV files (e.g. Civil Cost, P&L). The AI reuses an existing type when meaning matches — “Civil”, “Civil Cost”, and “Civil Works” are the same type if one is already on the menu. New types are added to the menu immediately when nothing fits.
  _Avoid_: tag, display name, “Other”

- **Display name**: The heading-derived name for a region (e.g. “Civil Cost Breakup as per Quotation dt 12.04.24”). Distinct from region type.
  _Avoid_: type, tag

- **Annotation**: A cell role inside a Required region — banners, footnotes, and explanatory text within a table. Not a kind of region; floating comments with no table are Orphan regions instead.
  _Avoid_: tag (when meaning a box or region purpose)

- **Enrichment report**: An inspectable JSON artifact for one named tab — regions, cell roles, amount-cell labels, and a problems list. Validated by eyeballing beside the redacted sheet; not official write-back to the workbook or database.
  _Avoid_: Official labels, persisted enrichment

- **LLM enrichment** (planned): An external model reads one named tab via a **sparse grid**, **cell index** (NDJSON with real coordinates), and **island hints** (ADR 0011). v1 delivers an inspectable JSON report; write-back deferred.
  _Avoid_: Reconstructing amounts from model output

## Phase 1 (current scope — done)

- **FM Loader**: file adapters (xlsx / xls / csv), safety limits, SQLite persistence, ingest QA (cell count reconciliation). Cell contract: coord, typed values, formula text + cached value, merges, hidden flags — no styles, quantity parsing, or formula normalization.
- **Redacted export v1** (`tev-parse redact`): after a successful ingest, export one `.xlsx` tab from the original file with numeric literals redacted. Requires `--input`, `--db`, `--mandate-id`, `--sheet`, `--output-dir`. Output: `{output-dir}/{basename}-redacted.xlsx`. Tied to ingest so file hash and parse run stay in sync. `.xlsx` only; one named tab for testing; all tabs in production later.

## Planned (not in repo yet)

- **LLM enrichment v1**: enrichment report for one named tab — AI-proposed regions, region purpose and type, display names, cell roles, and amount-cell row/column labels. Shared auto-growing type menu with synonym matching. Command fails when the problems list is non-empty.
- Analyst accept/reject UI, all-tabs enrichment, nested parent/child regions, cost-head rollup (CIVIL, HVAC, …), and official cell write-back

## Out of scope — do not reintroduce without ADR

Heuristic or rule-based region detection at ingest, cost-head rollup, formula reference graph, error cascade tracing, golden snapshots, review CLI, or anything that interprets sheet meaning during FM Loader ingest.
_Avoid_: Conflating heuristic detection with AI-proposed regions — the latter is planned enrichment, not Phase 1 ingest.
