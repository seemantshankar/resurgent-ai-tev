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

**Candidate**:
A structural grouping of exact member cells on one worksheet. Members never span worksheets. Kinds are coverage parent, child, parallel, overlap, and related. A kind is geometry and relationship, not a business classification. A Candidate is kept with its parse run. It stores member identity, not a second copy of cell values. A new parse run gets a new Candidate set; discovery does not rewrite an earlier parse run.
_Avoid_: table, region

**Coverage universe**:
Every persisted cell on the worksheet. Discovery does not apply a second occupancy filter. Narrower Candidates may omit a cell; the coverage parent may not.
_Avoid_: evidence-bearing subset, populated-only coverage

**Coverage parent**:
The worksheet-level Candidate that exists so every cell in that sheet’s coverage universe has a home. It is a backstop, not a claim that the sheet is one business object.
_Avoid_: sheet region, worksheet table

**Internal whitespace**:
Coordinates inside a Candidate’s envelope that have no persisted cell. They are not members.
_Avoid_: blank member, omitted evidence

**Packet**:
The LLM-facing payload for one Candidate: core cells plus appended context cells, kept distinct, with provenance intact. A Packet is built on demand from the Candidate and the cell graph; it is not stored as a second copy of amounts. Context may include cells from another worksheet only when a persisted reference edge supports the link. A large range on an edge is recorded as the range and the edge, not copied in as context cells. Packet selection: a Packet for every Candidate except the coverage parent; a coverage-parent Packet only when it is the sole Candidate on that worksheet or a child cannot stand alone through context closure. This increment does not call the LLM or store classifications.
_Avoid_: prompt, region payload, analysis table

**Core cell**:
A Packet cell that is a member of that Packet’s Candidate.

**Context cell**:
A Packet cell appended so the Candidate is interpretable in isolation. It is not a member of that Candidate and does not enlarge that Candidate’s envelope.
_Avoid_: inherited member, merged-in header

**Isolated hidden worksheet**:
A hidden worksheet with no reference edge to or from any visible worksheet in the same parse run. Region discovery still covers it and still emits a coverage parent. It is flagged as hidden and isolated. It is not skipped.
_Avoid_: ignored hidden sheet, auto-scratch sheet

**Structural confidence**:
A note on a Candidate that explains how strongly the layout evidence supports it. It does not omit, merge, or delete a Candidate. When two groupings are plausible, both remain.
_Avoid_: confidence threshold, score cutoff

**Region discovery**:
The feature name for the deterministic pass that emits Candidates and Packets from the cell graph. Not a type name and not a persisted entity.
_Avoid_: using “region” for Candidate, Packet, or any code identifier

## Phase 1 (current scope)

- **FM Loader**: file adapters (xlsx / xls / csv), safety limits, SQLite persistence, ingest QA (cell / reference / formula reconciliation). Cell contract: coord, typed values, formula text + `formula_normalized` + cached value, merges, hidden flags, `style_id` → shared cell style (bold, `number_format`, fill FG + pattern, per-side borders), and reference edges at ingest — no quantity parsing, header labels, font name/size, or fill background (ADR 0013).
- **Redacted export v1** (`tev-parse redact`): after a successful ingest, export one `.xlsx` tab from the original file with numeric literals redacted. Requires `--input`, `--db`, `--mandate-id`, `--sheet`, `--output-dir`. Output: `{output-dir}/{basename}-redacted.xlsx`. Tied to ingest so file hash and parse run stay in sync. `.xlsx` only; one named tab for testing; all tabs in production later.
- **Region discovery** (`tev-parse discover --db --parse-run`): DB-only pass that writes Candidates for an ingested parse run — always a coverage parent per worksheet (isolated hidden sheets flagged, not skipped), plus local child/parallel/overlap Candidates, formula-reference related links, and on-demand Packets (core vs context; amounts stay on the cell graph). Re-run replaces that parse run’s Candidates. [#90](https://github.com/seemantshankar/resurgent-ai-tev/issues/90)–[#93](https://github.com/seemantshankar/resurgent-ai-tev/issues/93).

## Planned (not in repo yet)

- LLM classification of Packets, discrepancy engine, and analyst review

## Out of scope — do not reintroduce without ADR

LLM-proposed region geometry, the old heuristic stack (cost-head rollup, worksheet-role scoring, trusted totals, golden snapshots), quantity parsing or header labels at ingest, review CLI, anything that interprets sheet meaning during FM Loader ingest, running region discovery inside ingest, matching similar schedule families across worksheets by resemblance (formula-reference edges may still record a cross-sheet relationship), dropping Candidates by a confidence cutoff, or rewriting Candidates of an earlier parse run.
