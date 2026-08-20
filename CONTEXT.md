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
