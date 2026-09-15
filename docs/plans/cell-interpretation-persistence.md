# Cell interpretation persistence

Status: revised; decisions accepted 2026-09-15; #116 coverage spine implemented; evidence/annotation/gloss/leaf follow-ups remain.

## Purpose and success criterion

The [TEV Automation BRD](../../Project%20Docs/TEV%20Automation%20BRD.md) identifies FM preparation and report drafting as approximately 75–80% of analyst effort. Phase 1 needs traceable FM ↔ document comparisons (FR-1.2, FR-1.3); Phase 2 audits the analyst-reworked FM; Phase 6 populates templates from validated inputs and rules.

**Persist enough cell meaning** to know what a source value represents, where that judgment came from, and what remains unresolved. Example: F & B Sales / Year 2 / INR lakh must stay distinguishable from historical sales in rupees.

This is a **foundation for comparison and model generation**, not a complete discrepancy engine or FM generator. It is **not** validated input, analyst approval, or an instruction to copy client formulas into Resurgent models. Persisting H11’s `Projected Profitability` records Layer B as-is; it does **not** rebind to `F & B Sales`.

**Success (slice 1):** After successful classification, every persisted source cell has an interpretation available through the **cell-meaning** read path. Known context is queryable with source-cell lineage. Unsupported context stays unresolved. No source workbook is modified.

## Accepted scope (slice 1)

- Interpretation persistence + **reliable header and comparison-context evidence**.
- Preserve period, basis, currency, scale, unit **evidence** now; normalize only when a rule explicitly supports it.
- Resolve headers **deterministically and conservatively** first; measure gaps before any LLM header fallback.
- Layer B leaf-selection quality is a **separate slice (4)** — not a gate on slice 1.
- Formula **dependency annotation** (2) and LLM **gloss** (3) sequenced after 1.
- Test seam: synthetic workbook → ingest → discover → classify (fake LLM) → cell-meaning lookup; focused resolver unit tests for geometry.

## Ownership and lifecycle (ADR 0018)

See [ADR 0018](../adr/0018-persist-cell-interpretation-after-classify.md) (to be written on implementation).

1. **Ingest** owns workbook facts. **Classify** owns Layer A/B, ProjectFacts, peers.
2. Interpretation is a **replaceable snapshot** keyed by `(parse_run_id, cell_id)`.
3. **Coverage** = every **persisted** cell in the parse run — including hidden cells, styled blanks, and merged participants. No second occupancy filter; do not invent cells for blank reference targets.
4. A **merged participant** does not become an extra economic amount because it carries the anchor’s value. Preserve ingest merge provenance (`value_source`); resolve header evidence to the **anchor** where appropriate.
5. Build interpretations from **real persisted cell facts**, never from number-redacted Packets.
6. Insert interpretations **after** new Layer A/B/fact/peer rows, **before** the classify transaction commits. Failure **rolls back** the complete replacement (prior snapshot intact).
7. Successful **rediscovery** deletes interpretations in the **same transaction** that replaces Candidates. **Failed rediscovery** preserves the previous state.
8. Keep **LLM calls outside the write transaction**. Soft-leaf catalog materialization already happens before commit; this feature does **not** make all catalog side-effects atomic with interpretation.
9. Parse runs stay **isolated**. Reclassification replaces **machine** interpretation for one run — it is **not** analyst-version history. Source-model version selection and future approved edits are separate concerns.

## Persistence contract (`V21`)

Two tables: one assembled interpretation + ordered evidence. **Avoid** separate tables (or denorm columns) per context dimension, and **avoid** authoritative duplicate header fields on the parent row.

### `cell_interpretation`

| Group | Fields and behaviour |
| --- | --- |
| Identity | `parse_run_id`, `cell_id`, unique together + FKs. Enforce the cell belongs to that parse run. |
| Origin and result | `value_origin` = `literal` \| `formula` (from formula **identity**, even when formula text unavailable). Carry typed result, `formula_text`, `formula_state`, `cache_state`, error fields as needed for the read contract. |
| Presentation | `resulting_value` nullable TEXT; `result_source` = `literal` \| `formula_cache` \| `missing_cache`. Error state is **separate** (literals and formulas can both error). Preserve zero, negative, empty, missing, text, boolean, date distinctly. Not `ABS()`. |
| Nomenclature snapshot | Nullable `nomenclature_path`, `amount_role`, `soft_leaf`, `via_alias` copied from Layer B. Null metadata = **no binding**, not a hard leaf. |
| Nomenclature status | `nomenclature_status` = `bound` \| `unbound` \| `not_applicable` — **Layer B coverage only**, not approval or correctness. |
| Provenance | `created_at`. Binding Candidate, verbatim, confidence remain on the **binding / cell-meaning read path** — do not copy every binding field onto interpretation. |

`result_source` ≠ ingest `cell.value_source` (cell vs merged_anchor). Keep ingest merge provenance available on read.

TEXT avoids extra conversion during snapshotting; it does not recover precision already lost at ingest/SQLite. Cache freshness is a **source assessment**, not proof of recalculation. SQL **NULL** for unavailable data; render blank at presentation time.

**Status rules (independent facts coexist):**

1. Existing Layer B binding → `bound` (even if error or uncertain headers).
2. No binding + clearly non–Layer-B cells (resolved header-only cells, merged participants as non-amounts, structural blanks, nonnumeric literals) → `not_applicable`.
3. Remaining numeric cells and formulas → `unbound` (including formulas without usable cache). Coverage marker — **not** “is money” or “LLM considered it.”

Header use lives in **evidence**, not a mutually exclusive label/header status. **ProjectFact** bindings remain independent; `not_applicable` to money nomenclature ≠ semantically unknown.

**Indexes:** `(parse_run_id, nomenclature_status)`, `(parse_run_id, nomenclature_path)`. Unique key already supports parse-run lookup — **no** redundant parse-run-only index.

### `cell_interpretation_evidence`

Ordered evidence attached to an interpretation (composite FK; cascade delete):

| Field | Behaviour |
| --- | --- |
| **Role** | `row_header` \| `column_header` \| `period` \| `basis` \| `currency` \| `scale` \| `unit` |
| **Source** | `source_cell_id` + copied source text; style/format via cell graph when it supplies cues. Same **parse run**; header evidence same **worksheet**. |
| **Ordinal** | Preserve multi-level order (e.g. Projected → Year 2 → Amount). |
| **Resolution** | `resolved` \| `missing` \| `ambiguous` |
| **Normalized** | Nullable normalized value + small deterministic **rule id** explaining derivation |

- **Missing:** no source cell, no normalized value.
- **Ambiguous:** retain **alternatives**; no chosen normalized value. One consistent resolution state per role; ordered alternatives are **not** a resolved header chain.
- Read model **assembles** row/column label strings from **resolved** `row_header` / `column_header` evidence — no denormalized authoritative header columns on `cell_interpretation`.

## Resolution rules

Load a **rich worksheet projection**: raw/formula identity, typed values, cache + cache_state, errors, merges, styles, coordinates. `CellPacketView` alone is insufficient. **Index cells by row/column** for reuse — do not full-scan the sheet per cell.

- Worksheet load ≠ permission to use unrelated schedules’ labels. Scope searches with **Candidate membership** and local structure; merge ranges may connect a header **anchor** to spanned columns/rows.
- Nearest left/above only **inside** that scope. A Candidate envelope, bold, or first worksheet row alone is **not** proof of a header. Coordinates are **one-based**.
- Support **numeric/date headers** (e.g. `2027`) when structural role supports it — text-only helpers are insufficient.
- Preserve ordered multi-level evidence. Keep **row detail** (Kone Elevator) separate from **catalog ancestry** (Moving Equipment → Elevator).
- Overlapping Candidates with conflicting headers → **ambiguous**; agreeing assignments may dedupe. Coverage-parent-only cells still get interpretations; unsupported headers stay **missing**.
- Share a **neutral resolver** with Layer B, passing **explicit scope**. Where a binding exists, evidence must reflect the **same scope** used for that judgment — not worksheet-wide headers. Missing labels are **not** coordinate strings pretending to be labels.
- Preserve relative **Year 2** as relative; do not invent a calendar year. Do not map an ambiguous currency symbol to INR without support.
- Preserve reported **scale** (e.g. lakh) and stored **resulting_value** separately — **do not multiply** or apply scale twice. A percent display of raw `0.12` does not become `12`.
- Prefer explicit unambiguous evidence. Conflicting period/unit/basis cues stay unresolved. **No LLM** in slice 1 for headers/context.

One resolver with a narrow read contract — not a general spreadsheet-layout framework.

## Application and read path

- **`InterpretationWriter`**: accepts existing repository/**caller transaction** and parse run; does **not** open or commit its own transaction.
- Enumerate all persisted cells → resolve scoped evidence → join newly persisted bindings → bulk-write interpretations + evidence.
- Extend `ClassifySummary` with interpretation count; must **equal persisted-cell count** on success. Unresolved meaning must **not** suppress coverage.
- Extend `CellMeaningService` / `CellMeaning` with optional interpretation + evidence. Old / unclassified / rediscovered runs → **absent** interpretation (never fabricate).
- Preserve Candidate, Layer A, binding, peers, ProjectFact on that read path.
- **No new CLI verb.** Slice 1 done when classify writes the snapshot and cell-meaning can read it.

Future display may assemble `Parent > Child… > Row Label > Column Label > Type > Formula > Resulting Value`. Keep **catalog ancestry** and **workbook headers** separate in storage. Type = Formula/Hardcoded from `value_origin`; formula blank for literals; signed results retain cache/error context. The string is **presentation**, not a stable identifier or comparison key.

## Acceptance tests

Synthetic workbooks + fake LLM; assert persisted/read behaviour.

1. **Coverage:** hidden cells, styled blanks, merged anchors and participants; participants do not invent economic duplicates.
2. **Bound path:** selected path/role snapshot + header lineage; generic valid leaf stays generic unless Layer B changes.
3. **ProjectFact:** nonnumeric fact cell keeps fact binding; money nomenclature `not_applicable`.
4. **Headers:** simple, merged, multi-level, numeric-year resolve with source IDs; adjacent schedules do not leak; conflicting overlap stays ambiguous.
5. **Context:** period/basis/currency/scale/unit evidence survives read-back; unsupported normalization stays null; different periods/scales not collapsed.
6. **Results:** literal negative/zero/text/date/boolean/error and formula numeric/text/error/empty/missing/stale distinguishable; formula identity survives unavailable formula text.
7. **Lifecycle:** successful reclassify replaces meanings+evidence; write failure rolls back to old complete snapshot; successful rediscovery invalidates; failed rediscovery preserves; second parse run never overwrites the first.
8. **Read:** before classification → no interpretation; wrong-run cell/evidence links rejected.

Real-workbook inspection may use Project Docs under repo rules. Do not commit client labels, amounts, or private fixtures.

## Sequenced follow-ups

| Slice | Blocked by | Delivery |
| --- | --- | --- |
| **1. Interpretation + context evidence** | — | One interpretation per persisted cell; cell-meaning exposes source-backed context + unresolved states; replacement/invalidation tests pass |
| **2. Formula dependency annotation** | 1 | Original expression + ordered annotated references; bounded ranges; incomplete/unresolved explicit; SUM≠AVERAGE preserved |
| **3. LLM formula gloss** | 2 | Separate prose from redacted annotated evidence via fake-testable LLM port |
| **4. Improve nomenclature leaf selection** | 1 (context contract) | Measured mapping cases pick supported economic leaf; generic-path/ambiguity measured. **No dependency on gloss** |

### Slice 2 — Formula dependency annotation

Retain `formula_expansion` / `formula_operand` if useful, but define as **original formula + dependency annotations**. Reference edges are ordered refs, **not** a full expression tree. Operators, functions, inline constants remain authoritative.

Annotate refs with coordinates + interpretation context. Bound range expansion; preserve external links, defined names, unresolved/dynamic refs, missing caches, blank targets **without inventing cells**. Report completeness/truncation. Shared leaf/ancestor on a range = **description of dependencies**, not instruction to sum and **not** a valid economic rollup. Do not create a second competing reference graph.

Derived state: invalidate with interpretations; replace atomically when regenerated. May run after interpretation exists **without** expensive expansion inside slice 1’s classify transaction.

### Slice 3 — Formula gloss

Formula text + redacted dependency annotations + headers + schedule family + binding context → narrow LLM port. Real literals/caches stay local under [ADR 0008](../adr/0008-send-a-number-redacted-sheet-to-external-models.md). Gloss = explanation, not computation, validation, or modelling rule. Lifecycle follows its annotation.

### Slice 4 — Leaf selection

Specify strategy against **measured failures** after the context contract exists (e.g. H11/H23 preferring catalog leaf over soft generic). Separate from persistence and from gloss.

## Explicitly deferred / out of scope

**Comparison and model generation**

- Discrepancy evaluation engine and full Phase 1 register UI
- Formula recalculation, sensitivities, circularity solvers
- Automated FM generation / destination-template mapping (Phase 6)

**Governance and human control**

- Analyst approval / version history of interpretations or bindings
- Durable provenance for later **approved** inputs (reclassify must not erase analyst decisions)
- Treating `bound`, confidence, or `soft_leaf` as proof of correctness or approval

**Aggregation and economics**

- Automatic economic `SUM(path)` across periods, units, duplicates, or alternative quotations
- Using `amount_role` alone to authorize those aggregations
- Inferring economic rollups solely because referenced cells share a path (also constrained in slice 2)

**LLM and heuristics deferred until measured**

- LLM fallback for ambiguous headers/context (revisit after measuring unresolved rates)
- Pre-LLM alias fast-paths and row-wise propagation across period columns
- Inventing intermediate catalog categories beyond existing soft-leaf-under-known-parent

**Catalog / product depth**

- Deeper taxonomy redesign (needs its own catalog work)
- Copying client formulas into Resurgent models as modelling rules

**Presentation**

- Treating the assembled nomenclature export string as a stable ID or comparison key

## Publication checklist

- [x] Review BRD and implementation; settle slice 1 scope (this plan)
- [ ] Record terminology in `CONTEXT.md` and ADR **0018** (`persist-cell-interpretation-after-classify`) (during implementation of #116)
- [x] Publish revised spec to GitHub: [#115](https://github.com/seemantshankar/resurgent-ai-tev/issues/115) (`ready-for-agent`), linked to #104 and #110; primary seam only
- [x] `/to-tickets` published under #115:
  - [#116](https://github.com/seemantshankar/resurgent-ai-tev/issues/116) coverage spine (frontier)
  - [#117](https://github.com/seemantshankar/resurgent-ai-tev/issues/117) evidence (blocked by #116)
  - [#118](https://github.com/seemantshankar/resurgent-ai-tev/issues/118) formula annotation (blocked by #116)
  - [#119](https://github.com/seemantshankar/resurgent-ai-tev/issues/119) formula gloss (blocked by #118)
  - [#120](https://github.com/seemantshankar/resurgent-ai-tev/issues/120) leaf selection (blocked by #117)
- [ ] Implement slice 1 through existing classify/read test seam (start #116)
- [ ] Implement follow-ups in dependency order; specify leaf-selection from observed failures

Blocking edges are declared in each ticket body (`Blocked by`). Native GitHub dependency API was not applied (bodies remain authoritative).

