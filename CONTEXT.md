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
The LLM-facing payload for one Candidate: core cells plus appended context cells, kept distinct, with provenance intact. A Packet is built on demand from the Candidate and the cell graph; it is not stored as a second copy of amounts. Context may include cells from another worksheet only when a persisted reference edge supports the link. A large range on an edge is recorded as the range and the edge, not copied in as context cells. Packet selection: a Packet for every Candidate except the coverage parent; a coverage-parent Packet only when it is the sole Candidate on that worksheet or a child cannot stand alone through context closure. Classify number-redacts Packets at send time and persists Layer A on the Candidate; amounts stay on the cell graph.
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

**Layer A (Packet disposition)**:
LLM judgment on what kind of island a Packet is: schedule family, Scratch/Orphan triage, axis labels where present, and relevance (`primary` | `supporting` | `noise`). Not a nomenclature path.
_Avoid_: classification (alone), region type, cost head

**Layer B (nomenclature binding)**:
Binding of a money line to a controlled vocabulary path. Verbatim client text stays evidence; the leaf is the join key; synonyms are aliases of one leaf. Structure comes from the Cell graph, not from presentation: the hardcoded inputs are typed from their own row labels and the formulas carry that forward, so composition, sign and role are read from the formula. Cost roles require money. The LLM is asked once per group, never once per cell, and only where the graph proves the role but the catalog does not hold the name. Nothing incorrect is written: anything unproven stays unbound with an Unbound reason (ADR 0019).
_Avoid_: tagging, cost-head guess, dictionary entry (for the binding itself), bold/borders/blank rows as structure

**Cell type**:
What one numeric cell turned out to be for a parse run: a kind (`money`, `quantity`, `rate`, `percent`, `count`, `ratio`), a scale (`unit`, `thousand`, `lakh`, `million`, `crore`, `billion`), and whether that came from the cell's own label or from propagation through its formula. Derived by dimensional arithmetic to a fixpoint — quantity times rate is money, money over money is a ratio, money divided by 100,000 is money in lakhs. A conflict is refused, never averaged.
_Avoid_: value type (that is the spreadsheet's own), amount, unit (alone)

**Aggregation**:
A head cell whose formula sums or nets its members, with membership read from the formula rather than guessed from layout. Sign comes from the operator, so a subtracted term is a `deduct` without any string matching. A head is what makes a cell a rollup, replacing the old formula role gate. Unit and scale are resolved once per aggregation from its members; a group whose members disagree resolves to nothing, and a non-money group can never carry a cost role. The R1C1-relative signature is what collapses a repeated row-series into one group.
_Avoid_: total row, section, cost-head rollup, trusted total

**Unbound reason**:
Why a numeric cell carries no binding: `untypable`, `external_dependency`, `broken_dependency`, `no_label`, `ambiguous_label`, `kind_conflict`, `scale_conflict`, `non_money_group`, `driver_only`, `cycle`, `llm_declined`, `llm_unavailable`. Enumerated in code and pinned by a unit test, not by a database CHECK. A principled boundary, not an accumulating pile of exceptions.
_Avoid_: error, failure, skipped

**Binding source**:
How a binding was produced: `input` and `derived` from the graph's typing, `aggregation_head` for a group's own total, `llm_label` for one group-level answer reused across every cell sharing a label, and `llm_line` for the per-cell answer. A role the graph proved beats a per-cell answer; where the graph only defaulted a role, the per-cell answer stands.
_Avoid_: confidence, provenance (alone)

**Amount role**:
How a bound money amount participates economically: `add`, `deduct`, `total`, or `helper`. Lives on the Layer B line binding, not on Layer A. Default leaf rollups include `add` only; `deduct`, `total`, and `helper` are excluded so totals and anti-double-count tear-outs do not distort `SUM(path)`.
_Avoid_: sign, polarity, debit/credit

**Numeric cell**:
Any Packet cell with a number or a formula that caches a number. Includes money, quantities, rates, and percents. Not every numeric cell is a money line.
_Avoid_: amount cell (alone — ambiguous)

**Money cell**:
A numeric cell the application classifies as currency/cost (labels, column headers, currency display cues). Only money cells may take Layer B roles `add`, `deduct`, or `total`.
_Avoid_: treating every number as a cost

**Quantity / rate cell**:
Numeric supporting drivers (units, area, capacity, price per unit, percents). They may appear in the Layer B prompt for interpretation and may bind only as `helper` when intentionally supported — never as cost `add`/`deduct`/`total`.
_Avoid_: amount, cost line

**Line peer**:
An optional link from one Layer B binding to other amount cells that represent the same economic leaf in a different role (for example a Civil “Less: AC” deduct peer of the P&M Air Conditioning add). The deduct line’s nomenclature path is the **economic leaf** (same path as the add), not the geometric section that drew the row. Peers should share that path; mismatched paths stay stored but are not treated as a resolved peer pair. Peers may sit outside the current Packet when they resolve to real cells. Packet context prefers existing formula-driven closure; the LLM may still name a real sheet-qualified peer coord that was not in the dump. `peer_reason` starts as `anti_double_count` only until a real FM forces another value. Peers are never required to store a binding. When both sides are seen, peers are written on both bindings.
_Avoid_: related Candidate, formula edge (alone), cross-reference note, Packet-level contra flag

**Cell interpretation**:
A replaceable machine snapshot, keyed by `(parse_run_id, cell_id)`, of what one persisted cell means after classify: value origin vs result/cache/error facts, Layer B coverage status, a lean path/role snapshot when bound, ordered header/comparison-context evidence with resolution states, and (for formulas) ordered dependency annotations with bounded range expansion. Built from real cell-graph facts (not redacted Packets). Coverage is every persisted cell. Classification replaces the snapshot atomically; successful rediscovery invalidates it. Not an amount store, not analyst approval, and not version history of approved edits.
_Avoid_: second cell graph, approved input, discrepancy finding

**Interpretation evidence**:
Ordered source-backed cues on a Cell interpretation — row/column headers plus period, basis, currency, scale, and unit — each with `resolved` / `missing` / `ambiguous` state and optional source-cell lineage. Headers resolve deterministically inside Candidate/merge scope; relative periods and scale are preserved without inventing calendar years, INR, or multiplying `resulting_value`.
_Avoid_: denormalized authoritative header columns, coordinate strings as labels, LLM header fallback (slice 1)

**Formula dependency annotation**:
Ordered annotated references on a formula Cell interpretation: original expression stays authoritative for operators/functions/constants; each reference carries coordinates plus interpretation context when the target is interpreted; bounded range expansion reports `complete` / `incomplete` / `truncated` / `unresolved` / `external` without inventing blank cells. Shared leaf/ancestor across members is descriptive only — never a valid economic rollup or an instruction to sum. Lifecycle follows interpretation invalidation/replacement.
_Avoid_: second reference graph, economic `SUM(path)` from shared head

**Formula gloss**:
Optional LLM explanatory prose for a formula Cell interpretation, stored separately from formula facts and deterministic dependency annotations. Built from number-redacted formula text, annotations, header evidence, schedule family, and binding context (ADR 0008 — no cached amounts). Explanation only — not computation, validation, or a modelling rule. Lifecycle follows annotation presence. Best-effort per classify run (bounded eligible Candidates / call cap); annotated formulas beyond the budget stay annotated without gloss.
_Avoid_: second amount store, modelling rule, validation verdict

**Nomenclature status**:
Layer B coverage marker on a Cell interpretation: `bound`, `unbound`, or `not_applicable`. Independent of header evidence quality and of ProjectFact bindings. Not proof of correctness or approval.
_Avoid_: validated, approved, money vs non-money (alone)

**Nomenclature spine**:
The frozen global bank mid-levels (CapEx + Means of Finance + working-capital margin hard; thin P&L/BS/CF). Industry packs add leaves on top; a mandate overlay holds soft leaves and aliases under known parents. Classify sends an ontology slice: spine + selected pack + overlay. Missing industry infers a stub and asks for one confirm; it does not block the slice.
_Avoid_: cost-head table, per-FM dictionary, Unmapped parking lot

## Phase 1 (current scope)

- **FM Loader**: file adapters (xlsx / xls / csv), safety limits, SQLite persistence, ingest QA (cell / reference / formula reconciliation). Cell contract: coord, typed values, formula text + `formula_normalized` + cached value, merges, hidden flags, `style_id` → shared cell style (bold, `number_format`, fill FG + pattern, per-side borders), and reference edges at ingest — no quantity parsing, header labels, font name/size, or fill background (ADR 0013).
- **Redacted export v1** (`tev-parse redact`): after a successful ingest, export one `.xlsx` tab from the original file with numeric literals redacted. Requires `--input`, `--db`, `--mandate-id`, `--sheet`, `--output-dir`. Output: `{output-dir}/{basename}-redacted.xlsx`. Tied to ingest so file hash and parse run stay in sync. `.xlsx` only; one named tab for testing; all tabs in production later.
- **Region discovery** (`tev-parse discover --db --parse-run`): DB-only pass that writes Candidates for an ingested parse run — always a coverage parent per worksheet (isolated hidden sheets flagged, not skipped), plus local child/parallel/overlap Candidates, formula-reference related links, and on-demand Packets (core vs context; amounts stay on the cell graph). Re-run replaces that parse run’s Candidates and clears Cell interpretations. [#90](https://github.com/seemantshankar/resurgent-ai-tev/issues/90)–[#93](https://github.com/seemantshankar/resurgent-ai-tev/issues/93).
- **Nomenclature catalog**: frozen global spine plus industry leaf packs and a mandate soft-leaf overlay, assembled as the ontology slice classify will send. Missing industry uses a non-blocking infer/confirm stub. [#105](https://github.com/seemantshankar/resurgent-ai-tev/issues/105).
- **Packet classification (Layer A)**: `tev-parse classify --db --parse-run` consumes derived Packets, sends number-redacted payloads plus the ontology slice through a narrow LLM port, and persists schedule family / triage / relevance / axes per Candidate. Coverage parents get a cheap pass first; children see that parent disposition. Re-classify replaces that parse run’s Layer A rows only. [#106](https://github.com/seemantshankar/resurgent-ai-tev/issues/106).
- **Packet classification (Layer B)**: the same classify pass binds from the cell graph first, then asks the LLM once per qualified label (never once per cell). Bindings carry verbatim evidence, `amount_role`, and provenance. Soft leaves attach under known mid-levels; coverage parents still get no line lists; default `SUM(path)` includes `add` only. Line peers and ProjectFacts persist alongside bindings. [#107](https://github.com/seemantshankar/resurgent-ai-tev/issues/107)–[#109](https://github.com/seemantshankar/resurgent-ai-tev/issues/109).
- **Cell meaning query**: read path for one sheet-qualified cell → graph facts, Candidates, Layer A/B, peers, ProjectFacts, and optional Cell interpretation. [#110](https://github.com/seemantshankar/resurgent-ai-tev/issues/110), [#116](https://github.com/seemantshankar/resurgent-ai-tev/issues/116).
- **Cell interpretation (coverage + evidence + formula annotation + gloss)**: after classify, one interpretation per persisted cell with value/result facts, nomenclature status, Candidate-scoped header/comparison-context evidence, formula dependency annotations, and optional number-redacted LLM formula gloss readable via cell-meaning; reclassify replaces; rediscover invalidates. [#116](https://github.com/seemantshankar/resurgent-ai-tev/issues/116)–[#119](https://github.com/seemantshankar/resurgent-ai-tev/issues/119).
- **Layer B leaf preference**: when evidence uniquely supports an existing hard catalog leaf, prefer it over inventing/keeping a soft generic; soft-kept and ambiguous outcomes stay explicit in binding stats. [#120](https://github.com/seemantshankar/resurgent-ai-tev/issues/120).
- **Layer B from the cell graph**: classify builds a per-run dependency graph from formulas and reference edges, types the hardcoded inputs from their labels, propagates by dimensional arithmetic to a fixpoint, and binds where the graph proves the role and the label names one hard catalog leaf. Names the graph cannot supply are asked once per qualified label, number-redacted, one Candidate per packet. Cell types, aggregations and unbound reasons persist as the evidence behind every binding; the mandate soft overlay is purged of orphans at classify start so a bad run's invented leaves are not offered back (ADR 0019).

## Planned (not in repo yet)

- Discrepancy engine and analyst review (triage soft; no per-finding review queue for dictionary growth)

## Out of scope — do not reintroduce without ADR

Inferring Layer B structure from presentation (bold as section header, double border as total, blank row as section end, column position as period band, header wording as column kind) — prototyped across all 46 tabs and abandoned; see ADR 0019. LLM-proposed region geometry, the old heuristic stack (cost-head rollup, worksheet-role scoring, trusted totals, golden snapshots), quantity parsing or header labels at ingest, review CLI, anything that interprets sheet meaning during FM Loader ingest, running region discovery inside ingest, matching similar schedule families across worksheets by resemblance (formula-reference edges may still record a cross-sheet relationship), dropping Candidates by a confidence cutoff, or rewriting Candidates of an earlier parse run.
