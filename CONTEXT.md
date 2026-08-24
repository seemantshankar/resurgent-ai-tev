# TEV Automation — Project Glossary

## Domain terms

- **TEV**: Techno-Economic Viability — the appraisal process this platform automates.
- **FM**: Financial Model — the client-submitted spreadsheet containing project costs, projections, and schedules.
- **Spreadsheet Parser**: The Phase 1 component that ingests `.xlsx`, `.xls`, and `.csv` client financial models into a queryable cell graph.
- **Cell graph**: The canonical representation of every occupied, hidden, merged, errored, and formula cell in a workbook, stored once per cell with provenance.
- **Region**: A connected component of cells forming a table, block, statement, or scratch island. Regions have a bounding box and a type.
- **Cost head**: A project cost category such as `CIVIL`, `PLANT_MACHINERY`, or `ELECTRICAL`. The locked vocabulary is defined in the parser strategy.
- **Canonical cost head**: The single cost head for one mandate and locked cost-head code. It may consolidate several source regions; a source region is not itself a cost head.
- **Cost-head mapping**: The association of a source region with a canonical cost head. Accepting the mapping does not accept any calculated amount.
- **Cost-head contribution**: One source region's or source cell's proposed contribution to a canonical cost head, retaining its amount, basis, unit, currency, confidence, reasons, and provenance.
- **Manual contribution**: An analyst-authored cost-head contribution carrying its actor, reason, amount, unit, currency, and provenance. It supplements or corrects calculated contributions without overwriting them.
- **Explicit total anchor**: A text-labelled total cell whose amount-column alignment and formula connection establish that it summarizes the region's line items.
- **Eligible leaf**: An amount-role cell that does not aggregate other included monetary cells and is not disqualified by error, scratch, duplicate ambiguity, period mismatch, or inconsistent unit or currency.
- **Leaf coverage set**: The eligible source cells represented by one cost-head contribution. Disjoint sets may be added, a strict superset supersedes its subsets, identical sets are duplicates, and partial overlap requires review.
- **Structural total**: An unlabeled `SUM` formula whose same-region dependency set exactly covers the eligible leaves and whose fresh cached amount agrees with their independent sum at the source number-format precision.
- **Candidate total**: A calculated cost-head total that remains non-actionable until deterministic trust checks pass or an analyst accepts it.
- **Candidate fingerprint**: The stable identity of a candidate total derived from its source file, cost-head code, contributions, leaf coverage, amounts, units, currencies, bases, and accepted manual contributions. Parser versions, configuration hashes, confidence, and explanatory reasons do not affect it.
- **Trusted cost-head total**: A candidate total approved for downstream decision-making by deterministic high-confidence checks or explicit analyst acceptance.
- **Trust state**: The lifecycle of a candidate total: `candidate`, `trusted`, or `stale`. A trusted total also records whether trust came from deterministic checks or analyst acceptance.
- **Mapping acceptance**: An analyst decision confirming a cost-head mapping for one source file and stable region key. It survives parser or configuration changes while those source identities remain unchanged.
- **Total acceptance**: An analyst decision approving one particular candidate total. It is independent of mapping acceptance and becomes stale when the candidate's amount, basis, unit, currency, or contributions change.
- **Column role**: One semantic purpose assigned to a region column from the locked set `serial`, `description`, `quantity`, `rate`, `amount`, `period`, or `other`, with confidence and reasons.
- **Parse run**: One execution of the parser against a document, producing metrics, warnings, errors, and review-queue entries.
- **Provenance**: The linkage from a parsed value back to its source document, sheet, cell, page, or line.
- **Merged anchor**: The top-left cell of a merged range that owns the value.
- **Merged participant**: A non-anchor cell inside a merged range; it carries no aggregable value, only display and provenance.
- **Scratch**: Unlabeled formula islands, orphan constants, or disabled lines kept in the graph but flagged so downstream consumers can ignore them.
- **Support**: A cell or region that may look like scratch in isolation but materially feeds a non-scratch part of the live model. Dependency evidence promotes scratch candidates to support.
- **Orphan**: An unlabeled cell with no meaningful region role, precedents, or dependents. Any cell referenced by the live model is support rather than orphan.
- **Duplicate proposal**: Structured evidence that two comparable regions or row sets may represent the same contribution. It never removes data and remains reversible as `Duplicate` or `Distinct` through analyst review.
- **Fuzzy mapping proposal**: A deterministic, ranked suggestion that a region belongs to a canonical cost head despite lacking an exact alias match. It never becomes an accepted mapping without analyst review in Sprint 3b.
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

- **Component**: A maximally connected group of semantically occupied cells on one sheet, before any boundary scoring is applied. Every component becomes exactly one region, however small.
- **Banner**: A row consisting solely of a merged title. A banner spanning several blocks joins them into one component by adjacency alone, so it is split off as its own region when the geometry beneath it proves the blocks are separate.
- **Break score**: The weighted sum of evidence that a component should be cut at a given row. Cuts are horizontal; vertical separation comes from connectivity, not scoring.
- **Region confidence**: How clearly one region type won over the next best, not how much evidence the winner had. A region matching two types almost equally is low-confidence even when both matched strongly, because ambiguity is what needs an analyst.
- **Region key**: A region's identity across parse runs, derived from its sheet and its top-left occupied cell rather than its bounding box, so that boundary tuning does not orphan an analyst's decision about it.
- **Detection reason**: One piece of structured evidence for a region's boundary or type — a stable code, a weight, and numeric parameters. Never prose, and never workbook text.
- **Region coverage**: The invariant that every occupied cell belongs to exactly one region. Region bounding boxes may overlap, because a box is a summary of a ragged shape; membership is the cell's own region, and only coverage is guaranteed.
- **Golden region snapshot**: The committed, scrubbed record of what region detection currently believes about the reference workbook, diffed on every run so that a tuning change is visible as a reviewable diff rather than a silent shift.
- **Golden semantic snapshot**: The committed, scrubbed record of Sprint 3b's semantic structure and trust decisions. It may contain roles, codes, bases, states, and structured reasons, but never workbook amounts, labels, raw values, or value-derived fingerprints.
- **Worksheet role**: A confidence-scored summary of a worksheet's classified regions, used to explain and organize parser output. It never independently includes or excludes cost-head contributions.

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
