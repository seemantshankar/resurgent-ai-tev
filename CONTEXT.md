# TEV Automation — Project Glossary

## Domain terms

- **TEV**: Techno-Economic Viability — the appraisal process this platform automates.
- **FM**: Financial Model — the client-submitted spreadsheet containing project costs, projections, and schedules.
- **Spreadsheet Parser**: The Phase 1 component that ingests `.xlsx`, `.xls`, and `.csv` client financial models into a queryable cell graph.
- **Cell graph**: The canonical representation of every occupied, hidden, merged, errored, and formula cell in a workbook, stored once per cell with provenance.
- **Region**: A connected component of cells forming a table, block, statement, or scratch island. Regions have a bounding box and a type.
- **Cost head**: A project cost category such as `CIVIL`, `PLANT_MACHINERY`, or `ELECTRICAL`. The locked vocabulary is defined in the parser strategy.
- **Parse run**: One execution of the parser against a document, producing metrics, warnings, errors, and review-queue entries.
- **Provenance**: The linkage from a parsed value back to its source document, sheet, cell, page, or line.
- **Merged anchor**: The top-left cell of a merged range that owns the value.
- **Merged participant**: A non-anchor cell inside a merged range; it carries no aggregable value, only display and provenance.
- **Scratch**: Unlabeled formula islands, orphan constants, or disabled lines kept in the graph but flagged so downstream consumers can ignore them.
- **Error cascade**: The propagation of an Excel error through the formula dependency graph to dependent cells.
- **Error descendant**: A cell whose evaluation chain passes through an error root but is not itself an error.
- **Cache state**: The freshness of a formula cell’s cached value: `fresh`, `stale`, `missing`, or `not_formula`.
- **Formula skeleton**: An abstract, position-insensitive representation of a formula pattern used for coherence scoring.
- **Region-free skeleton**: A formula skeleton computed without knowledge of regions, in which every absolute reference is the generic token `$ABS$`. It is a pure function of the parsed formula. Refining `$ABS$` into `$H$` (a header or assumption reference) requires regions and happens later.
- **Reference edge**: One reference token inside a formula, recorded as its own row linking the formula cell to what it points at. Ranges are recorded as a single edge, unexpanded; a reference to a blank coordinate resolves to nothing rather than bringing a cell into existence.
- **Unresolved reason**: Why a reference edge points at nothing — the sheet is absent, the external workbook index has no matching link, or the defined name is unknown. Distinct from a reference that resolves to a blank coordinate, which is not a failure.
- **Error barrier**: A function that consumes an error instead of propagating it, ending an error cascade at that cell. A cell behind a barrier is not an error descendant.
- **Circular group**: A set of cells that reference each other in a cycle. A cycle is a defect only when the workbook does not declare iterative calculation; otherwise it is deliberate.
- **Constant-formula**: A formula built entirely from numeric literals and arithmetic operators, referencing nothing. It carries a value that can be recovered without evaluating the workbook.

## Phase 1 roles

- **Spreadsheet Parser**: Owns ingestion, cell graph construction, region detection, cost-head tagging, and DB load.
- **Quote parser**: Consumes parser output to ingest vendor quotations.
- **Discrepancy engine**: Compares FM values against supporting documents using parser output.
- **Project summary builder**: Compiles the summary tab from parser output.

## Review states

- **Pending**: Awaiting analyst review.
- **Accepted**: Analyst confirmed the finding or value.
- **Rejected**: Analyst dismissed the finding.
- **Unsupported**: No supporting document backs the FM value.
- **Missing**: A supporting document value has no corresponding FM line item.
- **Insufficient evidence**: Not enough data to validate.
- **Unable to validate**: The check could not be executed.
