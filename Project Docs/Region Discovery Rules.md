# Deterministic Region Discovery Rules

**Status:** Working rules; product decisions locked 2026-09-04 (see PRD §14, `CONTEXT.md`, ADRs 0014–0017)  
**Scope:** Generic discovery of Candidates in client financial models  
**Owner of semantic classification:** LLM, followed by analyst review (a later component; this pipeline does not call the LLM)

## DB-only constraint

Region discovery has access only to the ingested workspace database. It must not reopen, reread, or inspect the original Excel file. It must operate on the persisted worksheet, cell, style, provenance, and formula-reference records described below.

This constraint is part of the region-discovery interface. A rule is valid only if its required evidence is available in the database.

### Evidence currently available to region discovery

- worksheet identity, name, index, state, dimensions, and populated bounds;
- cell coordinates, row/column numbers, raw values, normalized values, and value types;
- formulas, normalized formulas, cached values, formula/cache states, and errors;
- persisted shared style attributes and style identity;
- merged-range membership and anchor/participant status;
- hidden row, column, and worksheet flags;
- source-file, parse-run, and cell-level provenance;
- resolved and unresolved formula-reference edges;
- parse and ingestion QA metrics where persisted.

### Evidence not currently available unless separately added to the DB contract

Region discovery must not assume access to:

- the original workbook bytes or Excel object model;
- visual rendering or screenshots;
- column widths, row heights, or page layout;
- cell alignment, indentation, font family/size, font color, or most visual properties;
- comments, notes, drawings, charts, images, or shapes;
- workbook recalculation or values not persisted during ingestion.

Rules may use a signal from this second list only after that signal has been explicitly persisted in the database and added to the existing ingestion contract. Adding such a signal is a separate evidence-contract decision, not part of region discovery implementation.

## 0. Existing implementation: do not reimplement

The workbook evidence map required by Section 5.1 is already implemented by the FM Loader and persisted in the workspace database. Future work on region discovery should consume this existing evidence graph rather than add another workbook-ingestion or cell-capture layer.

The current ingestion implementation already captures:

- worksheet name, sheet index, sheet state, dimensions, and populated-cell bounds;
- cell coordinates and row/column numbers;
- raw values and normalized value types;
- text, numeric, boolean, date, display, and error values where available;
- formula text, normalized formula text, formula state, cached values, and cache state;
- shared cell styles, including bold, number format, fill foreground/pattern, and border styles/colors;
- merged-range geometry, merged anchors, and merged participants;
- hidden row, column, and worksheet state;
- source-file identity, file hash, parser version, and parse-run identity;
- cell-level provenance such as `SheetName!A1` and the original raw excerpt;
- formula reference edges, including resolved and unresolved references where detectable.

This baseline has been exercised by the existing ingestion tests and by the ingested workbook examples. The current test suite passes.

### Existing implementation limits

These limits are intentional and should not be mistaken for missing evidence-map work:

- completely blank, unformatted cells are not stored individually;
- style capture covers the current shared-style contract, not every Excel visual property;
- CSV inputs cannot provide workbook formulas, styles, merged ranges, or hidden-state metadata;
- formula references may remain unresolved when the source formula cannot be parsed or the target is unavailable;
- the loader does not assign semantic meaning, detect business regions, classify cells, or generate Project Summary/discrepancy outputs.

Region discovery begins after this existing ingestion contract.

## 1. Purpose

The deterministic system must identify Candidates in arbitrary client financial models so that Packets can later be built for LLM analysis.

The system must work across financial models with different layouts, sheet sizes, labels, formatting conventions, and spacing patterns. Any inspected workbook or worksheet is validation evidence only; it is not a template and must not become an implementation-specific rule. Integration proof is the working client FM under `Project Docs/`; Section 8 coordinates must not be hard-coded.

The deterministic system identifies structural Candidates. It does not decide that a Candidate is a Project Summary, Means of Finance, Scratch, Orphan, or any other business category. Semantic classification belongs to the LLM.

## 2. Core design principles

1. Coordinates and labels are observations, not rules.
2. Blank-row count is a weak signal, not a separator rule.
3. Structural discovery must be recall-first: omitted information is more dangerous than temporarily included information.
4. The system must not make an irreversible semantic partition of a worksheet.
5. Uncertainty must be represented through parent candidates, child candidates, or overlapping candidates.
6. Every persisted cell on a worksheet must belong to that sheet’s coverage parent. Narrower Candidates may omit a cell; the coverage parent may not.
7. Raw workbook evidence must remain available regardless of region classification.
8. No cell should be silently discarded as Scratch or Orphan by deterministic logic alone.
9. Candidates must preserve exact cell membership and source provenance, not only an outer bbox. Members never span worksheets.
10. A bbox is an envelope calculated from region membership; it is not, by itself, a semantic interpretation.
11. Text labels are opaque values to the deterministic system; matching or positioning text does not mean understanding its business meaning.

## 3. Responsibilities of the deterministic system

The deterministic system may:

- consume the persisted workbook and cell evidence from the database;
- identify persisted cells;
- detect structural clusters;
- detect layout signatures and discontinuities;
- identify coverage parent, child, parallel, overlap, and related Candidates;
- preserve blank layout cells inside a candidate envelope as internal whitespace;
- preserve hidden, formula-bearing, merged, styled, and error cells;
- attach structural explanations and confidence notes (confidence never drops a Candidate);
- guarantee coverage of every persisted cell via the coverage parent.

The deterministic system must not:

- assign business meaning to a region;
- assume fixed rows, columns, sheet names, or labels;
- classify a cell as Scratch because it is isolated;
- classify a cell as Orphan because it was not assigned to the first candidate;
- discard hidden rows, hidden worksheets, formula errors, or apparently unused cells;
- silently merge alternatives or quotation values;
- use a fixed number of blank rows as a universal separator;
- skip an isolated hidden worksheet;
- use a confidence cutoff to omit a Candidate.

## 4. What counts as evidence

The **coverage universe** is every persisted cell on the worksheet. Discovery does not apply a second occupancy filter that can leave a stored cell in no Candidate.

The loader already omits completely blank, unformatted cells. Those coordinates may appear only as **internal whitespace** inside a Candidate envelope.

The list below describes typical persisted evidence. It is not a predicate that may drop a persisted cell from the coverage parent:

- text;
- numeric value;
- date;
- boolean value;
- formula;
- formula cached value;
- error value;
- membership in a merged range;
- meaningful formatting on an otherwise blank cell;
- hidden content, whether or not it is adjacent to visible content.

Merged participants and border-only cells are not independent business facts, but their geometry and formatting may help define a narrower Candidate. They still belong to the coverage parent if they were persisted.

## 5. Candidate discovery process

### 5.1 Build an evidence map — implemented baseline

The following is already provided by the FM Loader. Region discovery should read and use it:

- sheet name and coordinate;
- raw and normalized value information;
- formula and cached-value state;
- style information;
- merged-range information;
- hidden row, column, and sheet state;
- source-file and parse-run provenance;
- formula reference edges where available.

### 5.2 Create initial structural clusters

Group nearby evidence-bearing cells into initial clusters using format-agnostic structural relationships:

- same worksheet;
- nearby rows or columns;
- overlapping column bands;
- overlapping row bands;
- merged-range membership;
- compatible formatting;
- repeated row or column shapes;
- formula-reference relationships.

These clusters are provisional. They are not final regions.

### 5.3 Detect structural anchors

Give additional structural weight to cells or ranges that appear to anchor a block, including:

- merged cells;
- prominent or repeated styled text;
- underlined or bold text;
- text spanning several columns;
- rows with a high concentration of text;
- repeated header-like patterns;
- borders that appear to begin or end a block;
- formula-linked totals or summary cells.

The system should record the anchor evidence without assigning meaning to the anchor text.

### 5.4 Detect layout signatures

For rows and columns, derive structural signatures from features such as:

- occupied column positions or bands;
- value types by column;
- text-to-number relationships as structural positions, not meanings;
- recurring separators such as punctuation cells;
- alignment and indentation patterns only if they are persisted in the database (not currently available);
- style families;
- border continuity;
- formula density and reference relationships;
- merged-cell geometry.

A layout signature describes shape and repetition. It must not depend on knowing what any label means, including labels such as “Project Cost,” “Term Loan,” “Opening Balance,” or “Closing Balance.”

### 5.5 Treat blank bands as soft separators

A blank row or group of blank rows should not, by itself, terminate a region.

When a blank band occurs, compare the structures immediately before and after it. Keep the areas in the same candidate when the following remain compatible:

- column bands;
- row shape;
- formatting;
- alignment;
- border continuity;
- formula relationships;
- surrounding anchors.

Consider a split only when multiple structural signals change together. The number of blank rows is not sufficient evidence for a split.

### 5.6 Expand candidates through compatible whitespace

Starting from an anchor or populated cluster, expand the candidate while there is structural support from:

- compatible column or row bands;
- repeated layouts;
- shared formatting;
- continuation rows;
- related formulas;
- related hidden cells;
- a nearby heading or enclosing layout.

Internal blank rows may remain inside the candidate envelope even though they are not evidence-bearing members.

### 5.7 Detect parallel and nested structures

A worksheet may contain multiple tables side by side or one summary above another. The system should support:

- a parent candidate covering related content;
- child candidates for distinct layout patterns;
- parallel candidates with different column bands;
- overlapping candidates when a boundary is uncertain.

A worksheet-level coverage parent is always emitted. Child, parallel, and overlapping Candidates are additional structure, not a substitute for that parent.

### 5.8 Include hidden related content

Hidden rows, columns, and cells that are persisted on the worksheet belong to the coverage parent. Narrower Candidates should include them when they are structurally related through one or more of:

- location inside the candidate envelope;
- compatible formatting;
- alignment with visible rows or columns;
- formula links;
- continuation of a repeated layout;
- adjacency to related evidence.

Hidden content should be surfaced in Packets with its hidden status preserved. An isolated hidden worksheet (no reference edge to or from a visible worksheet) is still discovered and covered; it is flagged, not skipped.

### 5.9 Stop at structural discontinuity

Candidate expansion may stop when several signals indicate a discontinuity, such as:

- a changed column layout;
- a changed row pattern;
- unrelated formatting;
- no overlap with established structural bands;
- a new independent anchor;
- a new dense table pattern;
- a formula-reference cluster disconnected from the current candidate.

No single signal should be treated as universally decisive.

## 6. Coverage and uncertainty invariants

Before Candidates are emitted, the system should verify:

1. Every persisted cell on the worksheet appears in that sheet’s coverage parent.
2. Hidden and formula-error cells have not been excluded from coverage.
3. Merged ranges are represented consistently.
4. Internal whitespace is distinguished from omitted persisted cells (there must be none of the latter).
5. Any uncertain split has either a parent Candidate or overlapping Candidates. Structural confidence does not drop either one.
6. Every Candidate has an explanation of the structural signals that produced it.
7. Every Candidate cell remains traceable to the original sheet and coordinate. Members never span worksheets.

If these checks cannot be satisfied, the result should be marked incomplete or uncertain rather than silently cleaned.

## 7. Candidate output

Each Candidate should expose at least:

```text
Candidate
  - candidate identity (stable within the parse run)
  - parse run and worksheet identity
  - outer bbox
  - exact member cells (cell identities; never two worksheets)
  - internal whitespace ranges
  - kind: coverage parent, child, parallel, overlap, or related
  - parent candidate, if any
  - child / overlapping / related candidates, if any
  - structural anchors
  - layout signatures
  - hidden cells and ranges
  - isolated-hidden-worksheet flag, when applicable
  - formula/reference relationships
  - source provenance
  - structural confidence (explanation only; never a drop switch)
  - discovery explanation
```

Candidates are persisted with the parse run. Packets are built on demand from a Candidate plus the cell graph; they are not a second copy of amounts.

The Packet contains the Candidate’s core cells together with explicit context cells. Required context is inherited same-sheet title/header/unit/shared-axis structure, plus formula targets that exist as persisted cells when the edge is a cell or a small range (cap: 64 persisted cells in the expanded target). A larger range is recorded as range address and edge identity, not inlined. Other-sheet cells appear only as context, and only when a persisted reference edge supports the link. There is no extra geometric ±N-row halo around the bbox.

### Candidate versus Packet

A physically contiguous candidate region and the information sent to the LLM are different concepts.

For example, `U17:Z17` on `P L` is a valid physical candidate because it is a contiguous populated block. However, it should not be analyzed as six isolated cells. Its LLM analysis packet should include:

- the core cells in `U17:Z17`;
- the structurally associated same-sheet row band that explains its position (`A17:M17` in the observation — inherited structure, not a generic halo);
- formula-reference edges to the main P&L row and referenced worksheets;
- the relevant source and target coordinates, inlining persisted targets only when the expanded range has at most 64 persisted cells;
- any nearby headings or units that are inherited title/header/unit context;
- an explicit indication that the block is physically disconnected from the main table.

The deterministic system may therefore emit:

```text
Physical candidate: P L!U17:Z17
Related context: P L!A17:M17
Dependency context: referenced cells on SALESPROJECTION
Semantic status: unclassified
```

This preserves precise physical geometry while giving the LLM enough context to make a concrete judgement. Related context must not silently change the candidate’s bbox or exact member-cell set.

### Context closure for child candidates

A child candidate must not be sent to the LLM in a way that makes it semantically incomplete.

When several child candidates share a header, title, unit, repeated column-axis structure, label column, or other interpretive context, that context must be inherited into each child’s LLM analysis packet. The deterministic system identifies the shared structure; the LLM interprets its meaning.

For example, the following `P L` child candidates share the year header in `A8:M8`:

```text
A10:M18  revenue assumptions and gross revenue
A20:M29  projected sales
A31:M53  revenue expenses and operating costs
A55:M73  profitability summaries
```

The physical child regions remain distinct, but each analysis packet must include at least:

```text
Shared context: A8:M8
Relevant title/unit context: C4:J6
Core candidate: one child block above
Related formula/reference context: where applicable
```

This is called **context closure**: every LLM packet must contain the minimum inherited structural context required to interpret the candidate’s values. A child candidate that cannot be made self-describing through inherited context should be analyzed together with its parent candidate instead.

Context closure does not alter physical membership. `A8:M8` remains a separate physical header candidate or parent member; it is additionally included as inherited context for its child candidates.

### Context materialization

When a Packet is built for a child Candidate, the deterministic system should materialize a context-complete payload by appending inherited context rows or columns. This is on-demand Packet construction, not an LLM send step.

For example, an isolated block from `P L!A20:M29` should be sent with the shared header row `A8:M8` appended above it in the analysis representation. The payload should also retain explicit metadata identifying:

```text
Physical core: P L!A20:M29
Appended context: P L!A8:M8
Original source coordinates: preserved for every cell
Context relationship: shared repeated column/header structure
```

Appending context is a presentation operation for LLM input only. It must not:

- mutate the workbook;
- change the candidate’s physical bbox;
- duplicate cells in the evidence graph or persist a second copy of amounts;
- remove the distinction between core cells and inherited context;
- weaken source traceability.

The same mechanism applies to inherited titles, units, label columns, section headings, and other context required to interpret an isolated candidate. The deterministic system copies these values as context; it does not classify or interpret them.

## 8. Non-normative validation observations

This section records observations from example worksheets used to test the generic rules. These entries are not parser instructions. Sheet names, coordinates, labels, and exact layouts in this section must not be hard-coded into the implementation.

### 8.1 Validation observation: form-like information block

The observed candidate envelope is approximately `B3:J37`.

Structural reasons for treating it as one parent candidate include:

- a prominent merged title anchor;
- repeated label/value row shapes;
- a recurring separator position;
- compatible formatting across the form;
- continuation rows for multi-line values;
- finance fields continuing through a hidden row;
- formula-linked values related to other schedules.

The blank rows between individual numbered items are internal whitespace because the surrounding layout signature remains compatible.

Possible child candidates may exist within the parent, but the deterministic system should not decide their business classification.

### 8.2 Validation observation: parallel and vertically separated tables

The observed worksheet contains several structural candidates:

- title and heading area;
- left-side project-cost table;
- right-side operating-metrics table;
- capital-expenditure summary;
- means-of-finance table;
- isolated metadata-like content.

This worksheet demonstrates why the system must support parent/child and parallel candidates. A single outer bbox may cover related content, while separate child candidates preserve the distinct table structures.

Side-by-side tables should be separated based on changed layout signatures and column bands, not merely because blank columns appear between them.

### 8.3 Validation observation: nested sections in a period table

The observed worksheet contains one dominant tabular parent candidate, approximately `B4:N34`, with a populated table core around `B9:N33`.

It demonstrates that a single candidate may contain nested structural sections, including:

- a title and annexure heading;
- a repeated period-column header;
- current-assets rows;
- current-liabilities rows;
- working-capital rows;
- total and subtotal rows;
- formula-bearing values and model errors.

Blank rows inside the table do not terminate the parent candidate because the period columns, row labels, formatting, and table geometry continue across them. The table should be represented as a parent candidate with possible child candidates for its repeated sections.

The `#VALUE!` cells are evidence-bearing model outputs and must not be treated as Scratch. Border-only and styled blank cells are table-layout evidence, not independent facts.

The isolated numeric content near the top of the sheet and style-only cells extending beyond the populated table should remain available for later classification; deterministic discovery must not discard them solely because they are isolated or empty.

This example adds the following working rule:

> Repeated column geometry and table-wide layout continuity may bridge multiple blank-row separators and support a parent candidate containing nested sections.

### 8.4 Validation observation: disconnected formula-driven blocks

The visible `P L` worksheet contains a large projected-profitability table, approximately `A4:M73`, together with additional sparse formula-driven areas that are physically separated from the main table. The worksheet-level envelope is approximately `A2:Z170`.

Observed structural patterns include:

- a title and unit heading;
- a repeated year-column header;
- revenue rows and a projected-sales subsection;
- revenue-expense rows with assumption, basis, and projected-value columns;
- summary rows such as PBIT, PBDIT, PAT, cash profit, and reserves;
- sparse calculation areas far to the right of the main table;
- repeated summary/calculation blocks far below the main table;
- formula errors such as `#VALUE!` and `#REF!`;
- formula references from the sparse areas back to the main table.

This confirms that geometry and adjacency alone are insufficient. A physically distant cell or block may belong to the same analytical dependency cluster through formula references.

The deterministic system should therefore produce both:

- a main structural candidate for the contiguous projected-profitability table; and
- related auxiliary candidates for disconnected formula-linked or structurally repeated blocks.

These auxiliary candidates should not automatically be merged into the main table’s bbox, and they should not automatically be classified as Scratch. They should be linked to the main candidate through formula/reference relationships and made available as related context to the LLM.

This example adds the following working rule:

> Formula-reference relationships can establish a relationship between non-adjacent candidate regions; physical distance must not be treated as proof that a region is unrelated.

It also reinforces that formula errors are evidence-bearing and may be especially important for Phase 1/Phase 2 analysis.

### 8.5 Validation observation: unlabeled calculation rows

The visible `B S` worksheet contains a projected balance-sheet table, approximately `B4:N46`, with a shared construction/year header and nested asset and liability sections. Its worksheet-level envelope extends farther right, approximately through column `Y`, because of additional helper calculations.

Observed structural patterns include:

- title, annexure, and unit headings;
- a repeated construction-year and projected-year axis;
- asset sections and total-assets rows;
- liability and capital sections and total-liabilities rows;
- reconciliation/difference rows;
- formula-only or number-only rows without a descriptive label;
- right-side helper blocks aligned to selected rows;
- formula errors and unresolved-reference outputs.

This confirms that an evidence-bearing row does not require a text label. Numeric or formula-only cells must remain candidates when they align with a table’s period axis, formatting, row band, or formula dependencies.

The main balance-sheet table and the right-side helper blocks should be represented as separate physical candidates, connected through shared row/column geometry and formula/reference relationships. Unlabeled reconciliation rows should remain attached to the main candidate or emitted as related validation candidates, not be discarded as Orphan or Scratch.

This example adds the following working rule:

> Formula-only and number-only rows can be structurally meaningful when they align with an established table axis or participate in a related calculation; descriptive text is not required for evidence coverage.

### 8.6 Validation observation: period-based roll-forward table

The observed worksheet contains a period-based cash-flow table with a shared construction/projected-period axis and multiple vertically ordered sections. The sections include inflows, outflows, totals, opening balance, annual surplus/deficit, and closing balance.

This pattern is structurally coherent even though section headings and spacer rows interrupt the repeated detail rows. The opening/closing balance sequence and formula relationships provide additional evidence of table membership.

The worksheet also contains a sparse calculation row below the main table with no descriptive label. Its period-aligned values and formula relationships keep it evidence-bearing and related to the main candidate.

This observation reinforces the generic rules that:

- a shared repeated column-axis structure may be inherited by all child sections;
- row-to-row formula/dependency patterns across that axis are structural evidence;
- adjacent rows with compatible geometry may form one parent candidate even when their labels are not interpreted;
- disconnected formula-only rows should remain related candidates when their dependencies support that relationship.

### 8.7 Validation observation: long multi-section itemized detail sheet

The observed worksheet contains a long vertical sequence of sections. Each section may contain a heading, explanatory or narrative rows, itemized detail rows, numeric comparison columns, subtotals, and a final section total. Different sections use different local column layouts while remaining part of the same worksheet-level evidence set.

This pattern demonstrates that a parent candidate must not require one uniform row signature from top to bottom. Instead, the parent may contain multiple locally coherent child candidates whose row signatures differ at section boundaries.

Itemized rows may be sparse, may place descriptive text and numeric values in different column bands, and may include continuation text or calculation rows with little or no text. Totals may appear outside the dominant detail-column band.

This observation reinforces the generic rules that:

- section anchors are structural evidence even when their text is treated as opaque;
- local row/column signatures may change within a larger parent candidate;
- child candidates should be formed from local structural coherence, not a requirement that the entire parent share one shape;
- sparse detail and total rows must remain covered;
- a parent candidate may preserve relationships among heterogeneous child candidates.

This is an additive rule. It does not override the existing rules for shared inherited context, parallel candidates, disconnected formula-linked candidates, or coverage of sparse and unlabeled evidence.

### 8.8 Validation observation: compact detail list with auxiliary numeric clusters

The observed worksheet contains a compact descriptive-and-amount list with a title, unit information, detail rows, and a total row. It also contains small numeric clusters in separate column bands aligned with only some of the detail rows.

The compact list is a local candidate because its rows repeat a descriptive-text plus amount shape. The sparse numeric clusters should be retained as separate candidates unless DB evidence—such as formula references, shared geometry, compatible styles, or enclosing structure—supports a stronger relationship. Formula-linked cells may belong to one auxiliary candidate even when blank rows occur between them; the candidate bbox may include those blank rows as internal whitespace.

Row alignment alone may establish a structural relationship or context link, but it must not be treated as proof of semantic association. The deterministic system should preserve the clusters and expose the relationship evidence to the LLM.

This observation adds the following generic rule:

> A compact detail table and nearby sparse numeric clusters may be separate physical candidates; retain both, and represent any relationship with evidence and confidence rather than assuming that visual or row alignment establishes meaning.

### Sparse aligned-cell parent candidates

Sparse numeric or formula-bearing cells separated by blank rows may be represented by a sparse parent candidate when they share a stable column or row band and occupy the same local structural envelope. This does not require direct formula references between every member.

The sparse parent must preserve:

- exact evidence-bearing member cells;
- internal whitespace ranges;
- individual formula/reference relationships;
- style and value-type differences;
- confidence that the cells belong to one structural cluster.

Where the grouping evidence is weak, the system should emit both the individual candidates and the sparse parent candidate, allowing the LLM to determine whether the cells share meaning.

### 8.9 Validation observation: long recurring row sequence with periodic variants

The observed worksheet contains a long sequence of rows sharing a repeated column pattern. Some recurring rows contain additional populated columns, while separate sparse blocks appear beside the sequence and below its main populated range.

This pattern does not require the deterministic system to understand the text in the row or column headers. It can be detected from repeated DB-observable row signatures, recurring positions, column occupancy, value types, styles, and formula/reference relationships.

The repeated sequence should remain a parent candidate rather than being split whenever a recurring row variant appears. Periodic variants may form child or related candidates when their local structure differs materially. Shared header and unit cells should be inherited into each child’s context-complete LLM packet.

Sparse side blocks and lower blocks should remain separate physical candidates unless persisted structural or dependency evidence supports grouping. Their physical distance alone is not grounds for discarding them.

This observation adds the following generic rule:

> Repeated row signatures with periodic structural variants may form one parent candidate with child or related candidates; detect recurrence from persisted structure, not from interpreting labels or headers.

### 8.10 Validation observation: repeated schedule families and stacked summaries

The observed depreciation worksheets contain repeated local schedule shapes stacked vertically, including recurring three-row patterns, shared year-column structures, section anchors, totals, and later summary tables. They also contain sparse side calculations that are physically separated from the main schedules.

The repeated local structures should be emitted as separate sibling candidates under a larger worksheet parent unless persisted DB evidence supports a stronger relationship. Similarity of row shape, text, or values is not sufficient reason to merge them. Formula/reference relationships, shared enclosing structure, and exact positional continuity may establish relatedness while preserving separate physical candidates.

This pattern also occurs across separate worksheets. Similar structure across worksheets must not merge cells into one physical Candidate. **Cross-sheet similarity relationships are deferred.** Cross-sheet relationships may still be recorded when supported by formula-reference edges (related Candidates and/or Packet context). Those other-sheet cells remain members of their own worksheet’s Candidates.

This observation adds the following generic rule:

> Repeated or near-isomorphic schedule structures remain separate sibling candidates by default; similarity on the same worksheet may be recorded as a Candidate relationship, not physical identity. Cross-sheet similarity relationships are deferred. Cross-sheet relationships may still be recorded when supported by formula-reference edges.

### 8.11 Text-heavy structured assumptions

Some meaningful regions contain mostly text and very few or no numeric/formula cells. A repeated multi-column row shape, a local header-like row, and short single-cell section anchors can still define a candidate region. Numeric density must therefore never be a prerequisite for discovery.

The deterministic layer may detect the repeated structural pattern and preserve the complete text values, but it must not infer what an assumption means. The LLM receives the region and classifies the records.

### 8.12 Grouped rows with blank continuation labels

A structured table may place a group label only on the first row and leave the corresponding label cell blank on continuation rows. A blank label cell is not evidence that the row is independent, scratch, or orphaned. Rows sharing the same local column pattern, styles, and surrounding table envelope should remain covered by the same candidate unless stronger structural evidence creates a child candidate.

This is a structural continuity rule only. The deterministic layer does not resolve the implied group or carry out semantic label inheritance; it preserves the rows and their exact coordinates for downstream analysis.

### 8.13 Multiple tables with different column signatures

A worksheet may contain several meaningful tables separated by variable whitespace, where each table has a different occupied-column signature or field pattern. Treat the worksheet as a possible parent and create local child candidates when a new header-like structure, changed column signature, or strong layout discontinuity appears. Do not require a single worksheet-wide schema.

Each child candidate must retain its own local header/title/unit context. A long-text field is evidence-bearing even when the other columns are short labels or numbers.

### 8.14 Merged headings and sparse calculation blocks

A calculation-oriented sheet may combine merged section headings, compact tabular blocks, and sparse numeric/formula clusters. Merged ranges are structural evidence and must be preserved, but they do not by themselves define the region boundary. Sparse cells remain candidate evidence when they share a stable row/column band, local envelope, style pattern, or dependency relationship with nearby content.

Where the evidence supports more than one interpretation, emit the narrow candidate and a wider parent or related candidate rather than forcing a single partition.

### 8.15 Long heterogeneous collections of detail tables

A worksheet may be a document-like collection of estimates, quotations, schedules, or itemized details. It can contain many local sections with different column counts, repeated or revised headers, category rows, line items, subtotals, totals, and long blank gaps. Discover a worksheet-level parent for coverage and local child candidates anchored by structural changes; do not collapse the entire sheet into one semantically uniform region.

When a new local header-like row changes the column signature or restarts a repeated table pattern, re-anchor the child candidate there. For LLM analysis, append that child’s local header/title/unit context to its detail rows while preserving the distinction between core cells and appended context cells. Totals, subtotal rows, category rows, and formula-linked or sparse cells remain evidence-bearing until classification.

These rules are compatible with the earlier rules for repeated schedules, nested sections, sparse aligned cells, and context materialization. Similarity between distant detail tables creates a relation or sibling candidates; it does not merge their physical regions automatically.

## 9. Classification lifecycle

Deterministic discovery and semantic classification should remain separate:

```text
Discovered
  → Unclassified candidate
  → LLM classification
  → Analyst review
  → Approved semantic classification
  → Cleanup or downstream analysis
```

Suggested semantic labels such as `Scratch` and `Orphan` are LLM- or analyst-owned classifications. They are not deterministic discard decisions.

## 10. Current working hypothesis

The deterministic system should optimize for:

```text
high coverage
+ explainable structural grouping
+ explicit uncertainty
+ exact provenance
```

It should not optimize for a perfect one-pass semantic partition of arbitrary financial models. The LLM should receive enough overlapping or contextual evidence to make the semantic decision safely.

## 11. Open questions for later conflict review

Locked 2026-09-04 (do not re-open without a conflict review and PRD update):

- Packet context is inherited same-sheet title/header/unit/shared-axis, plus small formula targets (cap 64 persisted cells); large ranges stay as edge metadata; no geometric halo.
- Coverage-parent Packet only when it is the sole Candidate or a child cannot stand alone; otherwise Packets are for narrower Candidates.
- Large sheets are split only by coverage parent plus local children; no extra chunker.
- Structural confidence never requires a discover-time analyst gate and never drops a Candidate.
- Re-ingest is a new parse run and a new Candidate set. Re-running discover on the same parse run replaces that run’s Candidates.
- LLM-requested expansion or re-segmentation is out of this foundation (no LLM call).

Still judged per worksheet using §5.9 (not a product fork):

- What combination of signals is a sufficiently strong structural discontinuity for a *child* boundary. No single signal is universally decisive. Coverage parent still covers the whole sheet.

## 12. Rule conflict-review protocol

Every new worksheet example must be used to test the existing rules before introducing a new rule.

For each example, record:

1. The observed worksheet structure.
2. Which existing rules explain the observation.
3. Which existing rules fail, become ambiguous, or produce competing candidates.
4. Whether the issue is a true rule conflict, an incomplete rule, or an example-specific exception.
5. The proposed resolution.
6. Any new invariant or open question created by the resolution.

When rules conflict, apply the following precedence:

1. Preserve raw evidence and provenance.
2. Preserve coverage of every persisted cell (coverage parent).
3. Prefer overlapping or parent candidates over omission.
4. Prefer general structural signals over workbook-specific coordinates or labels.
5. Prefer overlapping or parent Candidates and confidence notes over irreversible decisions. Confidence must not omit a Candidate.
6. Defer semantic disputes to the LLM and analyst review.

A new rule must not be added if it solves one workbook by invalidating a previously supported workbook pattern. Instead, revise the more specific rule into a more general structural principle, or record the case as an unresolved hypothesis.

### Regression check for new rules

Before accepting a rule derived from a new validation example, re-evaluate it against every previously recorded generic pattern. In particular, verify that the new rule does not:

- force previously distinct parallel candidates into one region;
- split previously coherent parent candidates solely because local row signatures differ;
- remove shared headers or other inherited context from child analysis packets;
- discard physically distant formula-linked candidates;
- discard formula-only, number-only, hidden, sparse, or error-bearing cells;
- introduce dependence on a sheet name, coordinate, label, workbook template, or fixed spacing;
- turn an LLM-owned semantic decision into deterministic classification.

New rules should be additive or should explicitly supersede an earlier rule. An additive rule expands the cases supported by the system without changing the expected result for earlier patterns. A superseding rule requires a recorded conflict, rationale, and re-evaluation of the affected examples.

### Current consistency audit

The rules currently contain no direct contradictions. The following apparent tensions are intentional distinctions:

- A parent candidate may contain heterogeneous child candidates; the parent preserves related coverage while children preserve local structure.
- A physical candidate’s exact member cells and bbox are different from the LLM analysis packet, which may include appended inherited context.
- Stopping candidate expansion means not merging the next structure into the current physical candidate; it does not mean discarding that structure or its dependency relationship.
- Blank cells may be omitted from the populated-cell set while still being represented as internal whitespace or layout ranges within a candidate envelope.
- A formula-linked distant block may remain a separate physical candidate while being related to another candidate through dependency context.
- Example-specific observations may mention coordinates and labels, but only the generic decisions in the normative sections are implementation rules.

The document should maintain a distinction between:

- **Invariant:** expected to hold across all supported workbooks;
- **Working rule:** currently useful but still subject to validation;
- **Observed pattern:** seen in one or more examples but not yet generalized;
- **Conflict:** two rules or interpretations produce incompatible results;
- **Resolution:** the agreed change to the rule set;
- **Open hypothesis:** a proposed behavior requiring further examples.

Before implementation, the accumulated rules should be reviewed as a single system for:

- circular or mutually cancelling rules;
- rules that cause evidence loss;
- rules that force semantic classification into deterministic logic;
- rules that assume fixed spacing, coordinates, labels, or sheet names;
- rules that make parent/child or overlapping candidates impossible;
- rules that use confidence as a cutoff.

The last pre-implementation item (unspecified confidence behavior) is closed: confidence is explanatory only (ADR 0017).

Each future sheet review should add a short entry to the change history below.

## 13. Rule review history

This is a generic pattern history. Example worksheet names and coordinates belong only in the non-normative observations above; the decisions below are the reusable rules.

| Validation pattern | Finding | Generic decision |
| --- | --- | --- |
| Variable internal spacing | Blank-row counts can vary within one structurally coherent block; hidden related content can occur inside its envelope. | Treat blank bands as soft separators; retain hidden related cells; use parent candidates where appropriate. |
| Parallel and separated tables | One worksheet can contain multiple side-by-side or vertically separated table structures. | Support parent, child, parallel, and overlapping candidates; do not force one flat region per sheet. |
| Nested period table | One table can contain repeated period columns, nested sections, totals, blank separators, and formula errors. | Preserve a table-wide parent candidate; allow child candidates; use column geometry and layout continuity across blank rows. |
| Disconnected formula blocks | Formula-driven helper or summary blocks can be physically distant from the main table and connected through references. | Add dependency-linked related candidates; do not classify distant blocks as Scratch solely from geometry or adjacency. |
| Unlabeled calculation rows | Meaningful rows may contain only formulas or numbers, without descriptive text. | Preserve aligned formula/number rows when supported by table axes, styles, or dependencies. |
| Repeated column-axis with row dependencies | Multiple vertically ordered row groups may share a repeated column structure despite headings and spacer rows; some rows may be linked by formulas. | Preserve a shared-axis parent candidate; allow child candidates; use row/column geometry and formula relationships as structural evidence without interpreting labels. |
| Heterogeneous multi-section detail | A larger worksheet can contain multiple locally coherent itemized sections with different row/column shapes, sparse detail, narrative continuation rows, and totals. | Allow heterogeneous child candidates under a parent; detect local structural coherence; preserve all detail and total evidence without requiring one global row signature. |
| Compact detail plus sparse numeric clusters | A small descriptive/amount list may have separate unlabeled numeric clusters aligned with selected rows. | Retain separate candidates; link them only through persisted structural evidence and confidence; do not infer semantic association from alignment alone. |
| Sparse aligned cells | Formula or numeric cells may share a stable row/column band while being separated by blank rows and lacking direct references between every cell. | Allow a sparse parent candidate with exact members and internal gaps; retain individual child candidates when grouping confidence is weak. |
| Recurring row sequence with variants | A long row sequence may repeat one shape while selected rows contain additional columns or related sparse blocks. | Preserve a recurring-sequence parent; allow local child/related candidates; inherit shared context; use persisted structural recurrence without interpreting text. |
| Repeated schedule families | Similar local schedules may be stacked within one worksheet or repeated across worksheets. | Preserve separate sibling candidates on the same sheet; do not merge. Cross-sheet similarity is deferred. Cross-sheet relationships may still be recorded when a formula-reference edge supports the link. |
| Text-heavy structured assumptions | A meaningful region may contain repeated multi-column narrative records with little or no numeric content. | Do not require numeric density; preserve the structural region and all text evidence, leaving meaning to the LLM. |
| Grouped rows with blank continuation labels | A group label may appear only on the first row while subsequent detail rows leave that label position blank. | Treat blank continuation labels as potentially continuous table rows; preserve them and avoid deterministic group interpretation. |
| Multiple tables with different column signatures | One worksheet may contain several local tables whose occupied columns and field patterns differ. | Detect local child candidates under a worksheet parent and re-anchor context at each structural restart; do not impose one global schema. |
| Merged headings with sparse calculations | Merged headings may coexist with compact tables and sparse numeric/formula clusters. | Preserve merged and sparse evidence; use them as structural signals only; emit wider/narrower candidates when grouping is uncertain. |
| Long heterogeneous detail collection | A document-like worksheet may contain many estimate/quotation sections with local headers, category rows, line items, subtotals, totals, and changing schemas. | Preserve a coverage parent plus locally anchored child candidates; append each child’s own context to its Packet; never discard totals or detail rows before classification. |
| Coverage universe and isolated hidden sheets | Persisted leftover hidden cells and isolated hidden worksheets can look like junk. | Coverage parent includes every persisted cell; isolated hidden worksheets are flagged, not skipped. |
| Packet vs Candidate persistence | Packets copy cell values that already live on the cell graph. | Persist Candidates; build Packets on demand; do not snapshot amounts. |

## 14. Implementation workflow for the AI coding agent

Implement the workflow below as a DB-only Candidate-discovery pipeline. The pipeline writes Candidates and can build Packets on demand; it does not assign business meaning, delete evidence, call an LLM, or open the workbook. Steps run **once, in order**. There is no refine loop.

| Step | Workflow action | Required DB evidence and behavior | Completion criterion |
| --- | --- | --- | --- |
| 1. Load the evidence map | Read one parse run from the persisted cell graph and process each worksheet in turn, including hidden worksheets. | Use persisted cells, values, formulas, cached/formula state, styles, merges, hidden state, provenance, and formula-reference edges. Treat labels and coordinates as opaque observations. Flag isolated hidden worksheets; do not skip them. | Every persisted cell is available to the discovery pass with provenance. |
| 2. Build structural signatures | Derive row, column, cell, and local-window signatures. | Use occupancy, value/formula/error type, style identity, merged geometry, repeated positions, border continuity when persisted, and dependency edges. Do not use semantic label dictionaries or fixed coordinates. | Each populated row/column/window has a reproducible signature and supporting evidence. |
| 3. Find structural anchors | Detect possible titles, headers, section restarts, totals, and table boundaries as structural anchors. | Identify changes in repetition, occupied-column pattern, styles, merges, formula density, or row/column roles. “Header-like” means structurally distinctive, not semantically understood. | Candidate anchors are recorded with evidence and confidence notes, without committing to meaning. |
| 4. Generate a coverage parent | Always create one worksheet-level coverage parent. | Include every persisted cell in the parent, including internal blank gaps as whitespace, hidden cells, totals, formula-only rows, sparse clusters, and long-text cells. | No persisted cell is left outside the coverage parent. |
| 5. Generate local child candidates | Partition around structural discontinuities and locally coherent repeated patterns. | Support variable blank gaps, different column signatures, nested sections, repeated schedules, grouped continuation rows, merged headings, and long detail collections. | Each child has exact member cells and a bounding envelope; no child is created only from a sheet name, coordinate range, label, or fixed row spacing. |
| 6. Preserve alternatives | Keep parent, child, parallel, overlapping, and related Candidates when evidence supports multiple plausible groupings. | Use explanations instead of destructive tie-breaking. Link distant formula/helper blocks as related Candidates where persisted dependencies support the link, including across worksheets. Cross-sheet similarity relationships are deferred. | Ambiguous structure remains representable and no plausible grouping causes evidence loss. |
| 7. Re-anchor context | Determine the local context required for each child. | Attach the nearest structurally associated title/header/unit rows or columns, shared-axis context, and qualifying dependency context. Context may cross blank rows or be inherited from a parent. | Every child Packet is understandable in isolation, or the Packet explicitly includes its parent context. |
| 8. Packet builder | Produce a Packet on demand without changing the workbook or duplicating source cells in the graph. | Mark each cell as `core` or `context`; retain original sheet, coordinate/range, raw/normalized values, formula state, styles, merges, hidden state, references, and provenance. Inline formula targets up to 64 persisted cells; larger ranges as metadata. **Packet selection:** a Packet for every non-coverage-parent Candidate; a coverage-parent Packet only when it is the sole Candidate on that worksheet or a child cannot stand alone through context closure. | The Packet contains the complete core plus sufficient context, with exact source references intact, and is not persisted as an amount snapshot. |
| 9. Run coverage and regression checks | Validate the Candidate set against invariants and prior patterns before discover returns. | Check cell coverage, provenance, no premature Scratch/Orphan assignment, no semantic rule usage, no fixed-layout dependency, and compatibility with parent/child/overlap behavior. | The Candidate set passes automated checks and any uncertainty is explicitly reported. |

Sending Packets to an LLM and retaining classifications is a **later component**, not a tenth step of this pipeline.

### Implementation guardrails

- Treat this workflow as additive: a new pattern may add a Candidate kind or structural signal, but must not silently weaken coverage or context closure.
- Before merging an implementation change, rerun the regression checklist in Section 12 against all previously recorded patterns and against OM Arham coverage.
- If a required signal is not present in the DB, record it as unavailable rather than reading the workbook or guessing it. Extending ingestion is a separate change to the evidence-map contract.
- `Scratch` and `Orphan` are downstream classification or cleanup states, never irreversible outputs of region discovery.
- Do not open Excel. Do not write Packet files. Do not gold-file Section 8 coordinates.
