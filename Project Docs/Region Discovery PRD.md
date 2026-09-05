# Region Discovery and LLM Analysis Packets — PRD

Status: Decisions locked for implementation (2026-09-04)  
Scope: Phase 1 foundation — Candidates and Packets; no LLM call  
Related documents: [Region Discovery Rules.md](Region%20Discovery%20Rules.md), [TEV Automation BRD.md](TEV%20Automation%20BRD.md), root `CONTEXT.md`, ADRs 0009 / 0012 / 0013 / 0014 / 0015 / 0016 / 0017, spec issue [#89](https://github.com/seemantshankar/resurgent-ai-tev/issues/89)

Canonical names: **Candidate** (physical grouping of member cells on one worksheet) and **Packet** (LLM-facing payload: core cells plus appended context). “Region discovery” is the feature name only. Do not use **table** as the product noun, or bare **region** as a type or code identifier. Glossary: `CONTEXT.md`.

## 1. Problem

Financial models do not follow one reliable layout. A worksheet can contain forms, tables, schedules, sparse calculation blocks, repeated sections, quotation-style detail, narrative assumptions, and unrelated material on the same sheet. The system must identify meaningful structural regions before analysis, while preserving enough context for an LLM to make a defensible classification and extraction decision.

The region-discovery pass must be deterministic and DB-only. It must not infer business meaning from sheet names or labels, and it must not depend on fixed coordinates, fixed spacing, or one client workbook’s template.

## 2. Outcome

For every ingested worksheet, the system produces an explainable set of structural Candidates and can build context-complete Packets on demand. This is the **Phase 1 structural foundation**, not the complete Phase 1 business outcome. Quotation extraction and cost-head classification, Project Summary generation, the discrepancy register, and reconciliation against supporting documents are later work that consumes Packets. This foundation does not call an LLM or store classifications.

The system optimizes for coverage, traceability, and recoverable uncertainty. It does not promise one perfect semantic partition on the first pass.

## 3. Goals

- Discover Candidates from the persisted cell graph, without reopening the workbook.
- Cover every persisted cell on a worksheet in that sheet’s coverage parent (the coverage universe). Narrower Candidates may omit a cell; the coverage parent may not.
- Support coverage parent, child, parallel, overlapping, and related Candidates.
- Handle variable blank spacing, hidden cells, merged headings, formula-only rows, sparse cells, errors, long text, repeated schedules, and changing local schemas.
- Preserve exact source references: file, parse run, worksheet, coordinate/range, and provenance context.
- Prepare a Packet that can be understood in isolation by appending structurally inherited context, without storing a second copy of cell amounts.
- Keep deterministic discovery facts separate from LLM classification and downstream analytical conclusions.
- Make uncertainty visible through overlapping or parent/child Candidates and structural confidence notes, never by dropping a Candidate.

## 4. Non-goals

- Semantic classification during ingestion or deterministic region discovery.
- Folding discovery into ingest, or a second product/jar (ADR 0014: `tev-parse discover` is DB-only — read-only for the source workbook, read/write for Candidates in the workspace database).
- Hard-coded rules for known sheet names, labels, coordinates, row counts, column counts, or spacing counts.
- Reopening the source workbook during discovery.
- Automatically assigning `Scratch` or `Orphan` as an irreversible cleanup decision.
- Replacing the existing FM Loader or evidence-map persistence.
- Calling an LLM, storing classifications, redacting Packets, or writing Packet files.
- Matching similar schedule families across worksheets by resemblance. Cross-sheet **similarity** is deferred. Cross-sheet **relationships** may still be recorded when a persisted formula-reference edge supports the link (related Candidates and/or Packet context). Members still never span worksheets.
- A geometric ±N-row halo around a Candidate bbox (context is inherited structure plus qualifying reference edges only).
- The complete Phase 1 business outcome: quotation extraction and cost-head classification; Project Summary; discrepancy register; reconciliation against quotations, audited accounts, DPRs, net-worth certificates, sanction letters, or other submitted evidence.
- Quantity parsing or analyst review UI in this component.

Those downstream capabilities consume the packets produced here and are governed by separate requirements.

## 5. Existing inputs and boundary

The FM Loader is the source of truth for workbook facts. It already persists, subject to its documented limitations:

- source-file and parse-run provenance;
- worksheet identity and visibility;
- cell coordinates, row/column positions, raw and normalized values;
- formula text, normalized formula, cached value, and formula state;
- shared style records and cell style references;
- merged-range information;
- hidden row and column state;
- formula reference edges, including unresolved or range references as represented by the loader.

Region discovery may use only these persisted fields and derived structural features. If a useful signal is not present in the DB—such as workbook visual dimensions, font metadata, comments, drawings, or chart geometry—it must be recorded as unavailable. Adding such a signal is a separate ingestion-contract change.

## 6. Users and consumers

The operator runs `tev-parse ingest`, then `tev-parse discover`, then (later, separately) `tev-parse redact` and an LLM send. The primary consumer of Packets is the later LLM analysis stage. The analyst is the eventual reviewer of classifications, extracted facts, reconciliations, and discrepancies. The coding agent implements the deterministic pipeline and its tests.

Discover always reads real cell values from SQLite. Amounts stay on the cell graph. Redact does not rewrite the DB. Packet number-redaction happens at LLM send time (ADR 0008), not inside discover.

Region discovery must expose machine-readable Candidates (and Packets on demand) plus human-readable explanations sufficient to answer:

1. Why was this candidate created?
2. Which exact cells belong to it?
3. Which cells were added only as context?
4. What evidence supports relationships between candidates?
5. What uncertainty remains?

## 7. Functional requirements

### FR-1: Load the evidence map

The system shall read one parse run from the DB and process each of its worksheets in turn, including hidden worksheets. It shall not require access to the original workbook. An isolated hidden worksheet (hidden, and no reference edge to or from any visible worksheet) is still discovered and covered; it is flagged, not skipped.

### FR-2: Derive structural signatures

The system shall derive reproducible signatures from occupancy, value/formula/error types, style identity, merged geometry, repeated positions, persisted border continuity, and formula-reference edges where available.

Text values may participate as opaque values in structural comparisons. The system shall not use a business glossary or label meaning to decide a Candidate.

### FR-3: Detect structural anchors

The system shall identify candidate anchors such as structurally distinctive rows, local headers, section restarts, totals, and title-like rows using observable layout and repetition changes. “Header-like” is an evidence-backed structural observation, not a semantic assertion.

### FR-4: Generate coverage candidates

The system shall always emit one worksheet-level coverage parent. That Candidate is a coverage backstop, not a claim that the sheet is one business object. It contains every persisted cell on the worksheet. It may contain internal blank gaps and shall retain hidden, sparse, formula-only, error, total, and long-text cells.

### FR-5: Generate local candidates

The system shall generate local child candidates around locally coherent structures and structural discontinuities. It shall support different column signatures, variable blank gaps, nested sections, repeated schedules, grouped continuation rows, merged headings, sparse aligned cells, and long heterogeneous detail collections.

### FR-6: Preserve alternative structures

The system shall retain multiple plausible candidates and relationships when the evidence does not justify a single partition. It shall support parent/child, sibling/parallel, overlapping, and dependency-related candidates.

### FR-7: Maintain exact membership

Every candidate shall contain exact member cells, not only an outer bounding box. Internal blank coordinates, hidden members, merged ranges, and source provenance shall remain distinguishable.

### FR-8: Close context for analysis

For each child candidate, the system shall identify structurally associated title/header/unit/shared-axis/dependency context. Context may cross blank rows or columns and may be inherited from a parent.

### FR-9: Materialize packets without source mutation

The system shall build a Packet on demand from a Candidate plus the cell graph. The Packet distinguishes `core` cells from `context` cells. Context materialization shall not modify the workbook, shall not duplicate source cells in the evidence map, and shall not persist a second copy of amounts.

**Packet selection policy** (must match spec issue #89):

1. Build a Packet for every Candidate **except** the coverage parent.
2. Build a coverage-parent Packet **only when** it is the sole Candidate on that worksheet, **or** a child cannot be made self-describing through context closure (then the child is analyzed with the parent Packet).
3. The Packet builder must still be able to materialize a Packet for any Candidate if a later caller asks.

Same-sheet inherited title/header/unit/shared-axis structure is required context. Other-sheet cells may be context only when a persisted reference edge supports the link; they never become members of the Candidate. A formula target range expands to persisted cells only, up to 64 cells; above that the Packet stores the range address and edge identity instead of inlining the body. There is no extra geometric halo around the bbox.

### FR-10: Preserve downstream traceability

LLM classifications and analytical findings (a later component) shall reference one or more Candidate ids and Packets. Deterministic Candidate evidence shall not be overwritten by LLM output. This component does not store LLM results.

### FR-11: Validate invariants

The system shall run coverage, provenance, context-closure, and regression checks before discover returns. Structural confidence is an explanation on the Candidate; it shall not omit, merge, or delete a Candidate. When two groupings are plausible, both remain.

## 8. Candidate contract

The implementation shall persist each Candidate with the parse run (ADR 0016). Members are cell identities, not a copied amount snapshot. A new parse run gets a new Candidate set; re-running discover on the same parse run replaces that run’s Candidates only.

Each Candidate shall include, at minimum:

| Field | Requirement |
| --- | --- |
| `candidate_id` | Stable within the parse run; suitable for downstream references. |
| `parse_run_id` | Identifies the source ingestion run. |
| `worksheet_id` | Identifies the source worksheet. Members never span worksheets (ADR 0015). |
| `outer_bbox` | Smallest known coordinate envelope for the candidate. |
| `member_cells` | Exact persisted cell identities included in the candidate. |
| `internal_whitespace` | Coordinates inside the envelope with no persisted cell. Not members. |
| `candidate_kind` | Coverage parent, child, parallel, overlap, or related. This is not business classification. |
| `parent_candidate_id` | Optional structural parent. |
| `related_candidate_ids` | Optional sibling, parallel, or dependency-related candidates. |
| `anchors` | Structural evidence used to start, stop, or re-anchor the candidate. |
| `structural_signatures` | Derived row/column/window signatures and comparison evidence. |
| `hidden_cells` | Hidden rows/columns or hidden member references retained in the candidate. |
| `isolated_hidden_worksheet` | Flag when the worksheet is hidden and has no reference edge to or from any visible worksheet. |
| `reference_edges` | Formula/dependency evidence associated with the candidate. |
| `provenance` | Source file, sheet, coordinates, and parse-run references. |
| `confidence` | Structural confidence with a machine-readable rationale. Never a delete switch (ADR 0017). |
| `explanation` | Concise evidence-based reason for the candidate and its boundaries. |

## 9. LLM analysis packet contract

A Packet is derived at call time from a Candidate plus the cell graph (ADR 0016). It is not a persisted snapshot of amounts. Each Packet shall include:

- candidate identity and structural relationship metadata;
- the core candidate cells with exact coordinates;
- appended context cells, explicitly marked as context;
- raw value, normalized value, formula, cached value, and formula state where available;
- style, merge, hidden-state, reference-edge, and provenance information where available;
- local row/column ordering and sufficient structural layout information;
- uncertainty and limitations in the deterministic evidence;
- a stable link back to the candidate and source cells.

The packet must not silently flatten context into core data. The LLM must be able to distinguish what lies inside the Candidate from what was appended to make it understandable. Cross-sheet context does not enlarge the Candidate’s bbox or member set.

**Packet selection policy:** default discover output is a Packet for every non-coverage-parent Candidate. A coverage-parent Packet is created only when that parent is the sole Candidate on the worksheet, or a child cannot stand alone through context closure. The builder remains available for any Candidate.

## 10. Processing workflow

The implementation workflow is defined in Section 14 of [Region Discovery Rules.md](Region%20Discovery%20Rules.md). It is a **linear named pipeline, once through, no refine loop**:

1. load the evidence map;
2. derive structural signatures;
3. find structural anchors;
4. create the coverage parent;
5. create local child and related candidates;
6. preserve alternatives;
7. re-anchor and close context;
8. make the Packet builder available (on demand);
9. run coverage and regression checks.

Handing Packets to an LLM and retaining classifications is a **later component**, not this workflow.

## 11. Invariants and safety rules

- Every persisted cell on a worksheet belongs to that sheet’s coverage parent.
- Candidate members never span worksheets. Similarity across worksheets is not recorded in this foundation.
- No candidate boundary may depend on a fixed sheet name, coordinate, label, row count, column count, or blank-row count.
- Blank rows are soft separators, not automatic Candidate boundaries.
- A blank label cell may be a continuation row and must not be discarded.
- Formula-only, numeric-only, error, hidden, sparse, total, and long-text cells remain in the coverage universe.
- Distant formula-linked blocks may be related without being physically merged.
- Isolated hidden worksheets are discovered and flagged, not skipped.
- Candidate discovery never performs semantic classification.
- Candidate discovery never irreversibly assigns cleanup states.
- Structural confidence never drops a Candidate.
- Packets preserve core/context distinction and exact provenance, and are not a second store of amounts.
- New rules are additive unless a documented conflict review explicitly supersedes an earlier rule.

## 12. Acceptance criteria

The implementation is ready for Phase 1 integration when all of the following are true:

1. A DB-only `discover` run can process every worksheet in an ingested parse run, including hidden worksheets, without reading the source workbook.
2. The output contains Candidates with exact cell membership, bounding envelopes, structural evidence, confidence, provenance, and isolated-hidden flags where applicable.
3. Coverage validation demonstrates that every persisted cell on each worksheet appears in that sheet’s coverage parent.
4. At least one Candidate hierarchy can represent each validated pattern in the rules document: forms, variable spacing, parallel tables, nested sections, formula-linked blocks, formula-only rows, period schedules, heterogeneous detail, sparse aligned cells, recurring sequences, repeated schedule families, text-heavy assumptions, blank continuation labels, multiple schemas, merged headings, and long detail collections.
5. Child Packets include their local header/title/unit context or explicitly inherit a parent Packet context.
6. Packets distinguish core cells from appended context cells and retain source references. They are built on demand, not snapshotted as a second amount store.
7. The implementation has no semantic label dictionary, fixed workbook coordinates, fixed sheet-name branch, or geometry-only separator rule.
8. Regression tests verify that adding a new structural pattern does not reduce coverage, remove context, or prevent parent/child/overlapping Candidates.
9. Candidate ids are stable within the parse run so a later LLM component can cite them without overwriting deterministic evidence. Storing LLM results is not part of this foundation.
10. Unavailable DB signals are surfaced as limitations rather than guessed or obtained by reopening the workbook.

## 13. Verification strategy

**Integration proof is the working client FM** `Project Docs/OM Arham Ventures.xlsx`. Ingest it, run discover, assert invariants and coverage. Skip when the file is absent (same convention as the existing real-workbook ingest IT). Do not copy it into `fixtures/`. Do not gold-file rules Section 8 coordinates, sheet names, or labels as expected bboxes.

**Unit tests** may use synthetic workbooks (temp files in tests, not `fixtures/`) to pin a condition OM Arham does not isolate, and for CLI wiring. Synthetics are not the proof that discovery works on a client FM.

Tests assert observable structure and invariants, not semantic classifications and not real financial amounts as expected values. Each new unit pattern must include a regression review against earlier patterns and must not reduce OM Arham coverage.

The pattern catalog to cover (on OM Arham where present; on a synthetic unit test only when that condition cannot be isolated in OM Arham) is:

- one coherent form with variable internal spacing;
- multiple separated or parallel tables;
- a table with repeated periods and nested sections;
- distant formula-linked helpers;
- formula-only and error-containing rows;
- sparse aligned cells separated by blank rows;
- repeated schedules with local variants;
- text-heavy multi-column assumptions;
- grouped continuation rows with blank labels;
- multiple tables with different schemas;
- merged headings with sparse calculations;
- a long heterogeneous quotation/detail worksheet.

## 14. Locked decisions (2026-09-04)

These replace the previous open list. Rebuild from these plus `CONTEXT.md` and ADRs 0014–0017.

| Decision | Resolution |
| --- | --- |
| Vocabulary | **Candidate** = physical members on one worksheet. **Packet** = core + context for a later LLM. Feature name remains “region discovery.” Ban **table** as the product noun and bare **region** in code. |
| Coverage universe | Every persisted cell on the worksheet. No second occupancy filter. |
| Coverage parent | Always emit one per worksheet. It is a backstop, not a business object. |
| Product shape | Same `tev-parse` binary; `discover` reads SQLite only; not inside ingest (ADR 0014). |
| This foundation’s output | Candidates persisted; Packets built on demand. No LLM call, no classifications. |
| Worksheet locality | Members never span worksheets. **Cross-sheet similarity relationships are deferred.** Cross-sheet relationships may still be recorded when supported by formula-reference edges (related Candidates and/or Packet context). |
| Persistence | Candidates stored with the parse run. Packets are not a second copy of amounts (ADR 0016). CLI prints counts, not Packet JSON. |
| Packet selection | Packet for every Candidate except the coverage parent. Coverage-parent Packet only when it is the sole Candidate on that worksheet, or a child cannot stand alone through context closure. |
| Range-edge context | Inline persisted target cells up to 64; above that, store range address and edge id. |
| Hidden worksheets | Process all, including hidden. Isolated hidden worksheets are flagged, not skipped. |
| Pipeline | Linear named steps, once through. No refine loop. |
| Redact vs discover | Discover uses real SQLite values. Redact is a later separate verb. LLM send redacts Packet numbers (ADR 0008). |
| Re-ingest | New parse run → new Candidate set. Discover does not rewrite an earlier run. Re-running discover on the same run replaces that run’s Candidates. |
| Structural confidence | Explanation only. Never a cutoff that drops a Candidate (ADR 0017). |
| Packet neighbourhood | Inherited same-sheet title/header/unit/axis plus qualifying edges. No ±N geometric halo. |
| Analyst inspection UI | Not this foundation. |
| Additional ingest signals | None in this foundation. Missing signals stay unavailable. |
| Verification | OM Arham is the integration proof. Synthetics are unit-only for conditions it cannot isolate. |

Deferred to later components (not undecided for this foundation): LLM classification, Packet redaction at send time, quotation extraction and cost-head classification, Project Summary, discrepancy register, reconciliation against supporting documents, analyst review, cross-sheet **similarity** matching (formula-reference edges remain in scope).

## 15. Relationship to Phase 1 outputs

This PRD and issue #89 implement the **structural foundation only**, not the complete Phase 1 business outcome. They do **not** implement:

- quotation extraction and cost-head classification;
- Project Summary generation;
- the discrepancy register;
- reconciliation against quotations, audited accounts, DPRs, net-worth certificates, sanction letters, or other submitted evidence.

Packets must nevertheless preserve the evidence those later components will need. The LLM and downstream reconciliation components are responsible for interpretation, extraction, classification, and discrepancy judgments. They must cite Candidate ids, Packets, and the underlying source references.

