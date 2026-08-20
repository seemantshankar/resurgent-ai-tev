# Excel Parser — Full Strategy v2 (Reviewed)

> Supersedes `parser-strategy.md`.  
> Input reviewed against: `Project Docs/OM Arham Ventures - Parser Complexity Findings.md`.  
> Goal for Phase 1: ingest `.xlsx`, `.xls`, and `.csv` client financial models into a queryable cell graph, tag regions and cost heads, preserve formulas/provenance, and expose errors/scratch explicitly instead of “cleaning them away”.

---

## 0. Verdict on v1 coverage

`parser-strategy.md` covers most findings conceptually, but it has **12 material gaps/risks** that must be fixed before implementation:

| # | Gap / risk in v1 | Why it matters | Fix in v2 |
|---|---|---|---|
| G1 | Claims “blank rows are never separators”, but §4.1 still ends regions after 2 blank rows | Direct contradiction; will split `depreciation` and mis-handle `Details` | Replace row-span detector with 2D connected-component + coherence scoring (§7) |
| G2 | Merged-cell participants are given the anchor’s `numeric_value`/`text_value` | Double counting in SUMs; totals inflated | Participants store `value_source='merged_anchor'`, `numeric_value=NULL`, `text_value=NULL`; expose `display_value` via view (§4, §10.13) |
| G3 | Error classifier misses `#N/A` because it checks `endswith(('!','?'))` | `#N/A` leaks through as text | Use exact error-literal set in formula lexer and cached-value classifier (§10.8) |
| G4 | `bool` is checked after `int` (`bool` subclasses `int`) | `TRUE/FALSE` become `1/0` | Check `bool` before numeric (§10.12) |
| G5 | `.xls` and `.csv` are out of scope despite BRD requiring them | Parser fails real intake variants | Add format adapters: openpyxl / xlrd / csv-sniffer (§2, §5) |
| G6 | External refs are tagged but `[15]` is not resolved to the external workbook target | Cannot tell which external file a ref points to | Parse `xl/externalLinks/*`, store `external_link` rows and `external_ref_target` (§10.2) |
| G7 | Cached-value staleness/missing cache policy is vague | openpyxl returns `None` for uncached formulas; values may be stale | Add `cache_state`, `calc_chain_state`, evaluator fallback, stale-cache rules (§9) |
| G8 | Formula normalization “whitespace tidy” can corrupt string literals | `="A  B"` must not change | Normalize only outside quoted string literals (§10.9) |
| G9 | Region model is row-spans only | Side-by-side blocks (`B  S !Q11:R18`) and blank-column-separated islands need 2D boxes | `region` stores full bbox; detector builds components over occupied cells (§7) |
| G10 | Cost-head rollup says `SUM(numeric_value WHERE region matches)` | Sums line items + subtotals + totals + merged duplicates | Rollup uses explicit total anchors or leaf-only filtered sets with double-count guards (§8) |
| G11 | Schema references fields not defined: `parsed_quantity`, `coerced_from_text`, `inferred_period_axis`, `row_hash`, `cell.provenance_id` | Tests and code won’t line up with DB | Add missing columns/tables or remove references (§4) |
| G12 | Heuristics are overfit to column A and one workbook | `Details`, `Floor area`, `AT GLANCE` violate column-A assumptions | Treat heuristics as scored signals with confidence and review queues, not truth (§7, §12) |

Additional smaller gaps: date/serial handling, number formats, Indian grouping/lakh-crore units, quoted sheet-name lexer edge cases, circular refs, veryHidden sheets, macros/OLE/DDE security, CSV dialect/encoding, duplicate detection across shifted blocks, and QA gates for parse confidence.

---

## 1. Scope

This parser owns only spreadsheet ingestion for Phase 1:

```text
client FM (.xlsx/.xls/.csv)
  -> L0 file intake + safety
  -> L1 canonical cell graph
  -> L2 formula/reference graph
  -> L3 region + cost-head tagging
  -> DB load + parse report
  -> downstream: quote parser, audited statements parser, correlator,
     discrepancy engine, project summary, export
```

It does **not** own PDF parsing, OCR, sanction-letter extraction, audited-statement canonicalization, discrepancy rules, or report rendering.

Non-goals:

- Perfectly recalculating every Excel workbook.
- Deleting scratch, hidden, or errored cells.
- Inferring a single global table where the workbook has many local tables.

---

## 2. Supported inputs and intake adapters

### 2.1 Formats

| Format | Reader | Notes |
|---|---|---|
| `.xlsx` / `.xlsm` | `openpyxl` | Formulas + cached values via two loads (`data_only=False/True`). |
| `.xls` | `xlrd` (pinned) | Formulas available only partially; store `formula_text=NULL` when unavailable and set `formula_state='unavailable'`. |
| `.csv` | stdlib `csv` + `charset-normalizer` | Sniff delimiter, quote char, encoding; no formulas; one worksheet per file. |

### 2.2 Safety limits

Reject or quarantine before full parse:

- ZIP bomb / expansion ratio above threshold.
- Sheet count, row count, column count, or cell count above configured limits.
- Password-protected or encrypted files.
- ActiveX/OLE/DDE payloads, external macro references, suspicious rels.
- XML entity expansion or malformed package parts.

All rejects go to `ingest_rejection` with reason and are surfaced to the analyst.

---

## 3. Architecture

```text
L0 Intake
  detect format, hash file, enforce safety limits, create parse_run
        |
        v
L1 Cell graph (deterministic)
  every sheet, every occupied/merged/hidden/referenced cell
  raw + cached + type + style hints + hidden + merged + comments
        |
        v
L2 Reference graph (deterministic)
  tokenize formulas; resolve local sheets, ranges, defined names,
  external [n] refs, error literals; build precedents/dependents
        |
        v
L3 Semantics (heuristic, confidence-scored)
  connected components -> regions -> roles -> period axis ->
  cost heads -> scratch/error/orphan flags
        |
        v
DB + parse_report + review_queue
```

Deterministic layers must be exactly reproducible. Heuristic layers must write confidence and reasons.

---

## 4. Database schema

### 4.1 Intake and workbook

```sql
CREATE TABLE parse_run (
    parse_run_id        BIGSERIAL PRIMARY KEY,
    mandate_id          INT NOT NULL,
    document_id         INT NOT NULL,
    parser_version      TEXT NOT NULL,
    started_at          TIMESTAMPTZ DEFAULT now(),
    finished_at         TIMESTAMPTZ,
    status              TEXT NOT NULL,        -- 'success' | 'partial' | 'failed' | 'rejected'
    metrics             JSONB,                -- counts, timings, confidence histogram
    warnings            JSONB,
    errors              JSONB
);

CREATE TABLE document (
    document_id         SERIAL PRIMARY KEY,
    mandate_id          INT NOT NULL,
    file_name           TEXT NOT NULL,
    file_hash           TEXT NOT NULL,
    file_type           TEXT NOT NULL,        -- 'fm_xlsx' | 'fm_xls' | 'fm_csv'
    page_count          INT,
    ingested_at         TIMESTAMPTZ DEFAULT now(),
    parser_version      TEXT,
    raw_metadata        JSONB,
    UNIQUE(mandate_id, file_hash)
);

CREATE TABLE workbook (
    workbook_id         SERIAL PRIMARY KEY,
    document_id         INT REFERENCES document(document_id),
    sheet_count         INT,
    visible_sheet_count INT,
    hidden_sheet_count  INT,
    very_hidden_count   INT DEFAULT 0,
    external_link_count INT,
    defined_name_count  INT,
    error_cell_count    INT,
    calculation_mode    TEXT,                 -- 'auto' | 'manual' | unknown
    full_calc_on_load   BOOLEAN,
    calc_chain_present  BOOLEAN
);

CREATE TABLE worksheet (
    worksheet_id        SERIAL PRIMARY KEY,
    workbook_id         INT REFERENCES workbook(workbook_id),
    sheet_name          TEXT NOT NULL,        -- verbatim, e.g. 'P  L '
    sheet_index         INT,
    sheet_state         TEXT,                 -- 'visible' | 'hidden' | 'veryHidden'
    role                TEXT,                 -- 'primary' | 'support' | 'scratch' | 'unknown'
    role_conf           REAL DEFAULT 0,
    bbox_min_row        INT,                  -- computed, never ws.dimensions
    bbox_min_col        INT,
    bbox_max_row        INT,
    bbox_max_col        INT,
    dimensions_declared TEXT,
    real_content_rows   INT,
    declared_merged     INT
);

CREATE TABLE external_link (
    external_link_id    SERIAL PRIMARY KEY,
    workbook_id         INT REFERENCES workbook(workbook_id),
    link_index          INT,                  -- [15] -> 15 when resolvable
    target_uri          TEXT,
    target_display      TEXT,
    refresh_error       BOOLEAN,
    sheet_names         JSONB,
    raw_part_name       TEXT
);
```

### 4.2 Cells

```sql
CREATE TABLE cell (
    cell_id             BIGSERIAL PRIMARY KEY,
    worksheet_id        INT REFERENCES worksheet(worksheet_id),
    coord               TEXT NOT NULL,
    row_num             INT NOT NULL,
    col_num             INT NOT NULL,

    raw_value           TEXT,                 -- stringified source value
    raw_type            TEXT,                 -- number|text|bool|date|empty|formula|error
    number_format       TEXT,
    style_hints         JSONB,                -- bold/fill/border/indent only; no theme dump

    formula_text        TEXT,                 -- without leading '='
    formula_normalized  TEXT,                 -- safe normalization only
    formula_state       TEXT,                 -- 'ok' | 'unavailable' | 'parse_error'
    cached_value        TEXT,
    cache_state         TEXT,                 -- 'fresh' | 'stale' | 'missing' | 'not_formula'

    value_type          TEXT,                 -- number|text|quantity_text|date|bool|empty|error|formula
    numeric_value       NUMERIC,
    text_value          TEXT,
    bool_value          BOOLEAN,
    date_value          DATE,
    display_value       TEXT,                 -- safe for UI; merged participants inherit here only
    coerced_from_text   BOOLEAN DEFAULT false,
    parsed_quantity     JSONB,                -- {"count":1,"unit":"Set","raw":"1Set"}

    is_merged_anchor    BOOLEAN DEFAULT false,
    is_merged_participant BOOLEAN DEFAULT false,
    merged_range        TEXT,
    value_source        TEXT DEFAULT 'cell',  -- 'cell' | 'merged_anchor'

    row_hidden          BOOLEAN DEFAULT false,
    col_hidden          BOOLEAN DEFAULT false,
    sheet_hidden        BOOLEAN DEFAULT false,

    is_error            BOOLEAN DEFAULT false,
    error_type          TEXT,                 -- exact enum incl '#N/A'
    error_descendant    BOOLEAN DEFAULT false,
    error_root_cell_id  BIGINT,

    external_ref        TEXT,                 -- verbatim '[15]Manpower!F35'
    external_link_id    INT REFERENCES external_link(external_link_id),
    sheet_refs          JSONB,                -- local sheet refs, verbatim names
    defined_name_refs   JSONB,

    row_hash            TEXT,                 -- normalized row signature
    region_id           INT,                  -- FK added after region table exists
    provenance_id       BIGINT,               -- FK added after provenance exists

    is_scratch          BOOLEAN DEFAULT false,
    scratch_reason      TEXT,
    is_orphan           BOOLEAN DEFAULT false,
    extraction_conf     REAL DEFAULT 1.0,

    formula_skeleton    TEXT,                 -- abstract formula pattern, e.g. '=$H*$R'
    coherence_score     REAL,                 -- average 0..1 with formula-capable neighbours
    coherence_dirs      JSONB,                -- {"left":1.0,"right":1.0,"up":0.0,"down":1.0}
    tags                JSONB DEFAULT '{}'::jsonb
);
```

### 4.3 Regions, cost heads, provenance, audit

```sql
CREATE TABLE region (
    region_id           SERIAL PRIMARY KEY,
    worksheet_id        INT REFERENCES worksheet(worksheet_id),
    start_row           INT NOT NULL,
    end_row             INT NOT NULL,
    start_col           INT NOT NULL,
    end_col             INT NOT NULL,
    header_rows         INT[],                -- multi-row headers allowed
    region_type         TEXT,                 -- cost_head|vendor_block|pnl|bs|cash_flow|debt_schedule|mof|capacity|utility|timeline|vertical_form|support|scratch|unknown
    region_conf         REAL DEFAULT 0,
    cost_head_code      TEXT,
    cost_head_label     TEXT,
    serial_pattern      TEXT,                 -- numeric|alpha_dot|alpha_dash|mixed|none
    inferred_currency   TEXT,
    inferred_unit       TEXT,                 -- 'rs' | 'lakh' | 'crore' | 'unknown'
    period_axis         JSONB,                -- {"D":1,"E":2,...}
    schema_json         JSONB,                -- [{col,name,type,role,conf}]
    detection_reasons   JSONB
);

ALTER TABLE cell
    ADD CONSTRAINT fk_cell_region FOREIGN KEY (region_id) REFERENCES region(region_id);

CREATE TABLE cost_head (
    cost_head_id        SERIAL PRIMARY KEY,
    mandate_id          INT NOT NULL,
    code                TEXT NOT NULL,
    label               TEXT,
    classification      TEXT,                 -- capex|opex|pre_op|working_capital|contingency
    fm_total            NUMERIC,
    fm_total_basis      TEXT,                 -- 'explicit_total_anchor' | 'leaf_sum' | 'manual'
    fm_cell_ref         TEXT,
    fm_region_id        INT REFERENCES region(region_id),
    review_status       TEXT DEFAULT 'pending',
    UNIQUE(mandate_id, code)
);

CREATE TABLE provenance (
    provenance_id       BIGSERIAL PRIMARY KEY,
    source_kind         TEXT NOT NULL,        -- fm_cell|fm_region|pdf_page|pdf_line|xlsx_row|external
    document_id         INT REFERENCES document(document_id),
    document_name       TEXT,
    page_number         INT,
    line_number         INT,
    sheet_name          TEXT,
    cell_coord          TEXT,
    bbox                JSONB,
    raw_text            TEXT,
    extraction_method   TEXT,
    extraction_conf     REAL,
    captured_at         TIMESTAMPTZ DEFAULT now()
);

ALTER TABLE cell
    ADD CONSTRAINT fk_cell_provenance FOREIGN KEY (provenance_id) REFERENCES provenance(provenance_id);

CREATE TABLE audit_log (
    log_id              BIGSERIAL PRIMARY KEY,
    parse_run_id        BIGINT REFERENCES parse_run(parse_run_id),
    mandate_id          INT,
    action              TEXT,
    actor               TEXT,
    details             JSONB,
    timestamp           TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE review_queue (
    review_id           BIGSERIAL PRIMARY KEY,
    parse_run_id        BIGINT REFERENCES parse_run(parse_run_id),
    entity_kind         TEXT,                 -- cell|region|cost_head|external_link
    entity_id           BIGINT,
    reason              TEXT,
    confidence          REAL,
    payload             JSONB,
    status              TEXT DEFAULT 'open'
);
```

Useful indexes:

```sql
CREATE INDEX idx_cell_ws_coord       ON cell(worksheet_id, coord);
CREATE INDEX idx_cell_region         ON cell(region_id);
CREATE INDEX idx_cell_numeric        ON cell(numeric_value) WHERE numeric_value IS NOT NULL;
CREATE INDEX idx_cell_text           ON cell(text_value) WHERE text_value IS NOT NULL;
CREATE INDEX idx_cell_error          ON cell(is_error, error_type);
CREATE INDEX idx_cell_external       ON cell(external_ref) WHERE external_ref IS NOT NULL;
CREATE INDEX idx_region_ws           ON region(worksheet_id);
CREATE INDEX idx_region_cost_head    ON region(cost_head_code);
CREATE INDEX idx_cost_head_mandate   ON cost_head(mandate_id);
CREATE INDEX idx_prov_doc            ON provenance(document_id);
```

---

## 5. Format adapters

```python
class SpreadsheetAdapter(Protocol):
    def sheets(self) -> list[SheetHandle]: ...
    def cells(self, sheet: SheetHandle) -> Iterable[CellIn]: ...
    def merged_ranges(self, sheet: SheetHandle) -> list[Range]: ...
    def external_links(self) -> list[ExternalLinkIn]: ...
    def defined_names(self) -> dict[str, str]: ...
    def workbook_props(self) -> WorkbookProps: ...
```

Implementations:

- `OpenpyxlXlsxAdapter` for `.xlsx/.xlsm`.
- `XlrdXlsAdapter` for legacy `.xls`; mark formulas unavailable if not exposed.
- `CsvAdapter` for `.csv`; synthesize one worksheet named from the file stem; all values are literal; encoding and delimiter recorded in `document.raw_metadata`.

Every adapter must normalize into the same `CellIn` shape so downstream logic is format-agnostic.

---

## 6. Cell normalization rules

Order matters:

1. `None` and empty string are distinct only at raw level; normalized `value_type='empty'` for both.
2. Check `bool` before numeric.
3. Dates: use adapter-provided datetime plus `number_format`; store `date_value` and original serial in `raw_value`.
4. Errors: exact membership in `{#REF!, #VALUE!, #DIV/0!, #NAME?, #NUM!, #NULL!, #N/A}`.
5. Numeric text coercion handles commas, Indian grouping, currency symbols, parentheses negatives, and percent text; set `coerced_from_text=true`.
6. Quantity text (`1Set`, `200 PC`, `L S`) stays `value_type='quantity_text'` with `parsed_quantity` JSON.
7. Formula cells: `raw_value` is formula text; `formula_text` strips only the leading `=`; `cached_value` comes from the value-only load; never coerce from formula text unless it is a constant-formula and evaluation is enabled.

Safe formula normalization:

```python
def normalize_formula(formula: str) -> str:
    # Strip legacy '=+' only at the start.
    # Collapse whitespace only outside string literals.
    # Never alter quoted sheet names or quoted string constants.
    ...
```

---

## 7. Region detection v2

### 7.1 Build occupied components, not row spans

For each worksheet:

1. Compute real bbox from occupied cells, merged ranges, comments, and same-sheet formula precedents.
2. Create a grid mask of “semantic occupancy”: non-empty cells, merged participants, error cells, and hidden cells count as occupied.
3. Dilate the mask by one row/column **only across label/formula-compatible neighbors**, then find connected components.
4. A component may be a full statement, a vendor block, a side scratch island, or a repeated inline summary.

This directly handles:

- `depreciation` blank spacer rows: spacer rows do not split a component if labels and formulas continue coherently.
- `Details`: separate vendor quote blocks become separate components.
- `B  S !Q11:R18`: side mini-analysis is its own component because columns O:P are empty.
- `AT GLANCE`: vertical form is one component but classified `vertical_form`, not forced into a table.

### 7.2 Break/merge scoring

Never use blank rows alone. Score candidate breaks:

```text
+2  explicit section title style (bold/fill/border/merged title)
+2  column schema changes materially
+2  serial pattern resets (1,2,3 -> A. -> ST.01)
+2  2D formula-skeleton drift (horizontal or vertical family changes)
+1  label column changes from blank to non-blank section marker
+1  formula anchor family changes
+1  >=2 consecutive blank rows AND next rows introduce a new header-like row
-2  blank spacer row inside an otherwise coherent formula block
-2  hidden rows inside a summed range
-3  break would split a merged range or a known total from its members
```

Break only when score >= 4. Otherwise keep component and log reason.

### 7.3 Region classification

Signals, each with weight:

- Header tokens: `year`, `yr`, `fy`, `construction`, `particulars`, `sl.no`, `amount`, `rate`, `qty`.
- Column-A serial pattern: numeric, alpha-dot, alpha-dash, mixed, none.
- Statement shape: assets/liabilities, revenue/expense, inflow/outflow, debt months.
- Cost-head alias match against locked vocabulary.
- Vertical-form pattern: few columns, label/value pairs, continuation rows.
- Scratch pattern: unlabeled formula island, orphan constants, disabled `*0` rows.

Every region stores `detection_reasons` and `region_conf`. Anything below threshold goes to `review_queue`, not silent guessing.

### 7.4 2D formula-coherence scoring

Formula-family consistency is a **scored feature**, not a hard rule. The parser computes an abstract skeleton for every formula cell and compares it with its non-empty neighbours in both directions.

#### 7.4.1 Skeleton abstraction

For each formula cell, produce `formula_skeleton` by replacing every cell reference with a relative role token:

```text
'P  L '!D22 = =$D$18*D10    ->  =$H$*R     (header column, same-row data)
'P  L '!E22 = =$E$18*E10    ->  =$H$*R
'P  L '!D23 = =$D$18*D11    ->  =$H$*R     (vertical neighbour of D22)
'P  L '!E23 = =$E$18*E11    ->  =$H$*R
'P  L '!D29 = =SUM(D22:D28) ->  =SUM(RANGE_VERTICAL)   (total row)
```

Token rules:

- Absolute references like `$D$18` → `$H$` (header) if they point to the assumption/header row of the region; otherwise `$ABS$`.
- Same-row relative references like `D10` → `R`.
- Same-column relative references like `D10` below `D22` → `R` as well (vertical family).
- Ranges like `D22:D28` → `RANGE_VERTICAL` / `RANGE_HORIZONTAL` based on orientation.
- Named constants/numbers remain literal.
- External refs, defined names, and error literals keep their own tokens (`EXT`, `NAME`, `#REF!`).

The skeleton is **insensitive to column letters and row numbers**, so `D22` and `M22` can match if they express the same calculation pattern.

#### 7.4.2 Neighbour comparison

For each formula cell, look at up to four neighbours (left, right, up, down), skipping blanks, merged participants, hidden rows/cols, and error cells. Compute per-direction similarity:

```python
def skeleton_similarity(a: str | None, b: str | None) -> float:
    if a is None or b is None:
        return 0.0
    if a == b:
        return 1.0
    # Allow a single-token drift (e.g. $H$ vs H) at reduced weight
    return 0.5 if token_edit_distance(a, b) == 1 else 0.0
```

Then:

```python
cell.coherence_dirs = {
    "left":  skeleton_similarity(cell.skeleton, left.skeleton),
    "right": skeleton_similarity(cell.skeleton, right.skeleton),
    "up":    skeleton_similarity(cell.skeleton, up.skeleton),
    "down":  skeleton_similarity(cell.skeleton, down.skeleton),
}
cell.coherence_score = average of non-zero directions
```

Cells without formulas get `coherence_score = NULL` and are ignored by the scorer.

#### 7.4.3 Interpretation

| Pattern | Meaning | Region action |
|---|---|---|
| High horizontal + high vertical coherence | Cell is inside a data table | Strong keep signal |
| High horizontal only | Likely a single-row data band or vertical-form row | Keep, but lower confidence |
| High vertical only | Likely a single-column schedule | Keep, but lower confidence |
| Low coherence, surrounded by labels/constants | Header, assumption, or label cell | Ignore for table interior |
| Low coherence, but row label says `Total` / `Grand Total` | Total/subtotal row | Mark as total, do not split |
| Low coherence in an unlabeled island | Scratch / orphan formula | Scratch candidate |
| Drift across several consecutive rows/columns | Probable schema or table boundary | Raise break score |

#### 7.4.4 Integration with break scoring

From §7.2, the `+2  2D formula-skeleton drift` signal triggers when:

- For ≥ 3 consecutive rows, the horizontal skeleton of the data columns changes materially (e.g. from `=$H$*R` to `=RANGE_VERTICAL`), **or**
- For ≥ 3 consecutive columns, the vertical skeleton changes materially, **and** the change is not explained by a total/subtotal label.

This is how the parser distinguishes:

- `P  L !D29` (a total inside the same table) from `P  L !D162` (a new scratch island).
- `Details` block A (`=C*D`) from block B (`=E+F`) even when labels are sparse.
- `B  S !Q11:R18` (side mini-analysis) from the main BS, because its skeleton family differs from `D11:N35`.

#### 7.4.5 Caveats

- A single cell dropping the `$` (e.g. `J23 = =J18*J11` vs `D23 = =$D$18*D11`) reduces coherence locally but should **not** split the table.
- Constants-as-formulas (`=200/2`) have skeleton `=CONST` and will look like outliers; they should be treated as inputs, not boundaries, unless the whole band is constant.
- Every-other-row spacing (`depreciation`) is handled by skipping blank neighbours.
- Hidden rows/cols are still compared because their formulas participate in the graph.

---

## 8. Cost-head rollup rules

Do **not** sum all numeric cells in a region. That double counts totals, subtotals, merged cells, and duplicated blocks.

Rollup precedence:

1. **Explicit total anchor**: label matches `total|grand total|total project cost|amount` and row/col aligns with the cost-head block. Example target style: `CAPITAL COST!D28`, `Details!F222`, `Details!G130`.
2. **Leaf-sum fallback**: sum only cells that are:
   - `is_merged_participant=false`
   - `is_error=false` and `error_descendant=false`
   - not on rows labeled subtotal/total
   - not referenced as an addend by another in-region total formula
3. **Manual review**: if neither passes confidence threshold, create `review_queue` row.

Write `cost_head.fm_total_basis` so downstream knows how the number was produced.

Duplicate handling: exact `row_hash` matches are logged; shifted duplicates use fuzzy normalized signatures. Never auto-delete duplicates.

---

## 9. Formula evaluation and cached-value policy

Primary source of truth is Excel’s cached value. Evaluation is a fallback, not a default.

Rules:

- If cached value exists and workbook calc mode is automatic: `cache_state='fresh'`.
- If cached value exists but file shows manual calc or stale calc chain: `cache_state='stale'`; keep value but lower confidence.
- If formula has no cached value: `cache_state='missing'`; do not invent a number unless evaluator is enabled and formula is whitelisted.
- Evaluator whitelist: constants, arithmetic, `SUM`, `ROUND`, `MIN/MAX`, `IF` on literals/simple refs. No external links, no volatile functions, no circular refs, no UDFs/macros.
- Recommended library: `formulas` behind an adapter. Any evaluation failure becomes `formula_state='parse_error'` and keeps cached/error state.

Error literal handling: `#REF!` inside formula text is an AST error node and sets `is_error=true` even if cached value is missing.

---

## 10. Findings-to-fix mapping

### 10.1 Hidden vs visible sheets

- Load `visible`, `hidden`, and `veryHidden`.
- `worksheet.role` is scored, not hard-coded from column A.
- Hidden support sheets (`Pages`, `BS_ANLYSIS`, `details of fixed assets  `) remain in graph.
- Downstream may filter presentation by visibility, never the graph.

### 10.2 External workbook links

- Parse `xl/externalLinks` and rels to resolve `[15]` to target URI/display name.
- Store both verbatim ref (`cell.external_ref`) and resolved link (`cell.external_link_id`).
- No network fetching. Stale cached values kept with confidence penalty.

### 10.3 Defined names

- Load all names into metadata.
- Retain in graph only names referenced by formulas.
- Pruned names remain in `document.raw_metadata`, not relational tables.

### 10.4 Dirty sheet names

- Store verbatim sheet names.
- Formula lexer supports quoted names with spaces: `'P  L '!D23`, `' wORKING CAPITAL'!E22`.
- Normalized matching is diagnostic only and must handle collisions explicitly.

### 10.5 Phantom used ranges

- Never trust `ws.dimensions`.
- Real bbox = occupied cells ∪ merged ranges ∪ comments ∪ same-sheet precedents.
- Store declared dimensions for diagnostics only.

### 10.6 Scratch / rough work

Keep and tag. Scratch detectors:

- Unlabeled formula islands (`Interest!F176:G185`, `P  L !D162:H170`).
- Orphan page-number constants (`SALESPROJECTION!E2=39`, `depreciation!F2=42`, `power cost!F3="34"`).
- Disabled lines (`ASSETS!F35 = =12117678.83*0`).
- Single-cell sums of constants (`Details!F234 = =SUM(F233:F233)`).
- Side analyses (`B  S !Q11:R18`) are scratch only if no live dependents; otherwise `support`.

Rule: a scratch cell with outgoing precedents to non-scratch cells is not scratch.

### 10.7 Gap pathology

- Blank rows/cols are weak signals only.
- Hidden rows/cols are retained and tagged.
- Region boundaries come from component + coherence scoring (§7).
- Known cases covered: `depreciation` spacer rows, `SALESPROJECTION` hidden rows 18–24, `P  L ` hidden column L, `B  S ` empty O:P before live Q:R.

### 10.8 Errors and cascades

- Exact error enum includes `#N/A`.
- Error cells remain graph nodes.
- Build descendants from parsed formula graph, including ranges and cross-sheet refs.
- `error_descendant=true` marks cells whose chain passes through an error root.
- Example expected: `P  L !B35 = =Manpower!A3` returns text; `L35/M35` become `#VALUE!`; downstream M/N columns in `B  S ` and `CASH FLOW` inherit `error_descendant`.

### 10.9 Formula style and 2D coherence

- Preserve raw formula verbatim.
- Normalize only outside string literals; strip leading `=+` only at start.
- Constant-formulas (`=200/2`, `=33000`) may populate `numeric_value` while keeping formula provenance.
- Do not infer fill-right patterns; parse each cell independently.
- Grammar supports postfix `%`, `^`, quoted sheets, ranges, external `[n]` refs, defined names, error literals.
- Compute `formula_skeleton` for every formula cell (§7.4) and compare horizontally and vertically.
- Formula-family drift is a boundary signal only when it persists across multiple cells and is not explained by totals, constants-as-formulas, or single-cell reference-style slips.

### 10.10 Multi-table / multi-schema sheets

- Regions carry independent `schema_json`.
- Column role matching (`description`, `qty`, `rate`, `amount`) beats column-letter assumptions.
- Serial pattern stored as metadata; breaks logged.
- Headers with newlines preserved raw and normalized in schema.

### 10.11 Headers and labels

- Formula labels use resolved cached/display text for matching, formula retained for provenance.
- `text_value` trimmed; `raw_value` untouched.
- Period axis stored as structured map (`period_axis`) not string equality.
- Multi-row and merged headers supported via `header_rows`.

### 10.12 Types and values

- `bool` checked before `int`.
- Full precision kept in `NUMERIC`.
- Number format and inferred unit (`rs|lakh|crore`) stored; no silent unit conversion.
- Text-numbers coerced with `coerced_from_text`.
- Quantity text parsed to JSON without destroying raw text.
- Dates stored as dates plus raw serial.

### 10.13 Merged cells

- Anchor owns value.
- Participants get `merged_range`, `value_source='merged_anchor'`, and `display_value`, but **not** `numeric_value`/`text_value` used for aggregation.
- Aggregation queries must filter `value_source='cell'` unless explicitly doing display rendering.

### 10.14 Duplicate data

- Exact `row_hash` for identical normalized rows.
- Fuzzy block signature for shifted copies (`ASSETS` vs `Details`).
- Log duplicates; do not canonicalize automatically.

### 10.15 Testability

Every finding maps to fixtures (§13). Parse must emit a deterministic `parse_run.metrics` payload so regressions are diffable.

---

## 11. Locked cost-head vocabulary

Keep v1 vocabulary, but matching must be alias + fuzzy + review-gated:

```python
COST_HEADS = {
    "LAND": ["land", "land cost", "land & site", "site cost"],
    "SITE_DEVELOPMENT": ["site development", "land development", "site preparation"],
    "CIVIL": ["civil", "civil works", "building", "construction", "civil & structural"],
    "PLUMBING": ["plumbing", "sanitary"],
    "FIRE_FIGHTING": ["fire fighting", "fire protection", "fire system"],
    "PLANT_MACHINERY": ["plant & machinery", "p&m", "machinery", "equipment"],
    "KITCHEN_EQUIPMENT": ["kitchen", "kitchen equipment", "kitchen/store"],
    "WATER_TREATMENT": ["water treatment", "wtp", "effluent", "etp", "stp"],
    "LIFTS": ["lift", "lifts", "elevator", "elevators"],
    "HVAC": ["hvac", "air conditioning", "ventilation"],
    "ELECTRICAL": ["electrical", "electrification", "wiring", "transformer", "panel"],
    "GENERATOR": ["dg set", "dg", "generator", "genset"],
    "LED_LIGHTING": ["led", "lighting"],
    "MISC_EQUIPMENT": ["miscellaneous", "furniture", "it", "computer", "office equipment"],
    "PRE_OPERATIVE": ["pre-operative", "preoperative", "preliminary", "pre-op"],
    "WORKING_CAPITAL": ["working capital", "margin money"],
    "CONTINGENCY": ["contingency"],
}
```

Ambiguous aliases (`equipment`, `miscellaneous`) require either region context or review.

---

## 12. Confidence, review, and QA gates

Every heuristic output carries confidence and reasons. Minimum gates before `parse_run.status='success'`:

- 100% of occupied cells ingested or explicitly rejected with reason.
- 100% of formulas tokenized or marked `formula_state='parse_error'`.
- 100% of external `[n]` refs either resolved to `external_link` or queued.
- 0 unexplained losses between adapter cell count and DB cell count.
- Cost-head coverage report: matched/unmatched regions and totals basis.
- Error-cascade report: roots, descendants, affected statements.
- Scratch report: tagged cells and any scratch cells with live dependents.

Partial success is allowed only if all failures are represented in `review_queue`.

---

## 13. Test fixtures

Keep v1 fixtures and add the gap-regression tests:

| Test | Source | Assertion |
|---|---|---|
| blank rows do not split | `depreciation` rows 10–55 | one coherent region unless score threshold crossed |
| side-by-side component | `B  S !Q11:R18` vs main BS | separate region bbox, not same row-span |
| merged no double count | `B  S !C5:K5` | SUM over participants equals anchor value once |
| `#N/A` exact enum | synthetic | `error_type='#N/A'`, not text |
| bool before int | synthetic | `TRUE` -> `bool_value=true`, not `numeric_value=1` |
| external index resolved | `CAPITAL COST!I19` | `external_link_id` points to parsed link, `external_ref='[15]Manpower!F35'` |
| missing cache marked | synthetic uncached formula | `cache_state='missing'`, no invented numeric |
| normalization preserves strings | synthetic `="A  B"` | string literal spaces unchanged |
| `.xls` adapter | legacy fixture | formulas unavailable -> `formula_state='unavailable'` |
| `.csv` adapter | dialect/encoding fixture | delimiter/encoding recorded; no formula columns required |
| cost-head no double count | `Details!F222/F225` | `fm_total_basis='explicit_total_anchor'`; leaf-sum excludes total rows |
| error cascade | `P  L !B35:L35` -> `B  S !M16` | descendant flags set |
| scratch with dependents kept | `B  S !Q11:R18` | not scratch if referenced |
| period axis structured | `B  S `, `depreciation`, `power cost` | maps stored despite header spelling differences |
| horizontal formula family | `P  L !D22:M22` | `D22:M22` share skeleton `=$H$*R` (allow single-cell `$` drift) |
| vertical formula family | `P  L !D22:D28` | `D22:D28` share skeleton `=$H$*R` (except total row) |
| total row not a boundary | `P  L !D29 = =SUM(D22:D28)` | `coherence_score` low but label `Total` prevents split |
| 2D coherence boundary | `Details` rows 44–52 vs 55–130 | skeleton family change + label/serial change triggers region split |
| single-cell drift tolerated | `P  L !J23` drops `$` | region stays intact; `coherence_dirs` shows local 0.5 |
| constant-formula outlier | `SALESPROJECTION!F41 = =200/2` | `formula_skeleton='=CONST'`, not a table boundary |

---

## 14. Implementation plan

### Sprint 1 — deterministic foundation

- L0 intake, safety limits, adapters for `.xlsx/.csv` (`.xls` behind flag).
- Canonical cell graph, real bbox, merged flags, hidden flags, exact errors.
- External-link parsing and defined-name pruning.
- Schema from §4; tests for deterministic findings.

### Sprint 2 — formula/reference graph

- Formula lexer/parser with quoted sheets, ranges, `%`, `^`, external refs, error literals.
- Precedent/dependent graph, cycle detection, error-descendant marking.
- Cached-value states and evaluator fallback behind whitelist.

### Sprint 3 — semantics

- Connected-component region detection v2.
- Region classification, period axis, schema inference.
- Scratch/orphan/duplicate tagging with reasons.
- Cost-head rollup with double-count guards.

### Sprint 4 — hardening

- `.xls` adapter completion, CSV encoding suite, performance pass.
- Parse report, review queue UI feed, audit events.
- Full OM Arham Ventures integration test and fixture freeze.

---

## 15. Definition of done

The parser is done when, on `OM Arham Ventures.xlsx` and the synthetic fixtures:

1. Every occupied, hidden, merged, errored, external, and formula cell is represented once and only once.
2. No region boundary depends solely on blank rows or columns.
3. No aggregation sums merged participants, subtotals, and totals together.
4. Every heuristic classification has confidence, reasons, and a review path.
5. Re-ingesting the same file is idempotent by `(mandate_id, file_hash, parser_version)`.
6. Downstream modules can answer: value, cleaned value, formula, cached value, error state, source provenance, region, cost head, and confidence for any retained cell.
