# Excel Parser — Strategy for Messy Financial Workbooks

> Tuned for **TEV Phase 1** ingestion: produce cleaned, tagged cell
> data from a messy client FM (like `OM Arham Ventures.xlsx`) ready
> to be loaded into the project's database.
> Companion to *OM Arham Ventures.xlsx — Parser Complexity Findings*.

---

## 0. Scope

This document is the design for the **xlsx parser** only — one piece
of the Phase 1 ingestion layer. It does not cover the PDF parsers
(quotations, audited statements, DPR, sanction letters, NWC), the
discrepancy engine, the project-summary builder, or the export
pipeline. Those consume this parser's output.

**Parser's job, end to end:**

```
messy.xlsx  ──►  Layer 1: cell graph  ──►  Layer 2: regions + cost-head tags
            ──►  DB load (cell, worksheet, region, cost_head, provenance)
            ──►  downstream modules (quote parser, audited-parse,
                  correlator, discrepancy engine) take it from here
```

**What "cleaned and tagged" means in this context:**

- Every cell stored as a row, including blanks, hidden, and errored ones
  (they're load-bearing in the dependency graph).
- Cells carry a cleaned `value_type` and parsed `numeric_value` /
  `text_value` so the DB is queryable without re-parsing formulas.
- Cells are grouped into `region` rows (vendor blocks, cost-head tables,
  P&L, BS, debt schedule, etc.) with detected schema.
- Cost heads (P&M, Civil, Electrical, etc.) are tagged on regions and
  rolled up into `cost_head` rows.
- Every value has a `provenance` row pointing to sheet, cell, page,
  bounding box — so the UI can render "click to view source".

---

## 1. Architecture

Two layers, deterministic first, heuristic second:

```
┌──────────────────────────────────────────────────────────────┐
│ LAYER 1 — Cell Ingestion (deterministic)                     │
│  • Load every cell (visible + hidden sheets, all rows/cols).  │
│  • Capture: raw value, formula, cached value, type, error,    │
│    hidden flags, merge participation, external refs.          │
│  • Output: one `cell` row per cell.                           │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────┐
│ LAYER 2 — Region / Cost-Head Tagging (heuristic)             │
│  • Detect table regions using label density, not blank gaps.  │
│  • Classify each region: cost_head, vendor_block, pnl, bs,    │
│    debt_schedule, mof, capacity, utility, timeline, etc.      │
│  • Tag cost heads against the Phase 1 cost-head vocabulary.   │
│  • Roll up FM totals per cost head into `cost_head` rows.    │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
                        DB load
```

Why two layers: `Details` (a single sheet) has ~10 sub-tables with
different column schemas, and `depreciation` has 71 blank rows used as
visual section breaks. Any single-pass table extractor breaks here.
The cell graph is the deterministic backbone; region detection is the
best-effort annotation on top, with a fallback to raw cell lookup if
it misfires.

---

## 2. Output Schema (parser's footprint)

Only the tables this parser populates. Other tables in the broader
DB (quotation, historical_financial, dpr_field, discrepancy,
project_summary, etc.) are listed in §8 for context but are populated
by other modules.

```sql
-- Intake: the FM file itself
CREATE TABLE document (
    document_id         SERIAL PRIMARY KEY,
    mandate_id          INT NOT NULL,        -- FK to mandate table (created by upstream)
    file_name           TEXT NOT NULL,
    file_hash           TEXT NOT NULL,        -- SHA-256; used to dedupe re-ingest
    file_type           TEXT,                 -- 'fm'
    page_count          INT,
    ingested_at         TIMESTAMPTZ DEFAULT now(),
    parser_version      TEXT,
    raw_metadata        JSONB                 -- openpyxl workbook properties
);

CREATE TABLE workbook (
    workbook_id         SERIAL PRIMARY KEY,
    document_id         INT REFERENCES document(document_id),
    sheet_count         INT,
    visible_sheet_count INT,
    hidden_sheet_count  INT,
    external_link_count INT,
    defined_name_count  INT,
    error_cell_count    INT
);

CREATE TABLE worksheet (
    worksheet_id        SERIAL PRIMARY KEY,
    workbook_id         INT REFERENCES workbook(workbook_id),
    sheet_name          TEXT NOT NULL,        -- verbatim, e.g. "P  L "
    sheet_index         INT,
    sheet_state         TEXT,                 -- 'visible' | 'hidden' | 'veryHidden'
    role                TEXT,                 -- 'primary' | 'support' | 'scratch'
    bbox_min_row        INT,                  -- computed, not from ws.dimensions
    bbox_min_col        INT,
    bbox_max_row        INT,
    bbox_max_col        INT,
    dimensions_declared TEXT,                 -- for diagnostic vs bbox
    real_content_rows   INT,
    declared_merged     INT
);

CREATE TABLE cell (
    cell_id             BIGSERIAL PRIMARY KEY,
    worksheet_id        INT REFERENCES worksheet(worksheet_id),
    coord               TEXT NOT NULL,        -- 'D23'
    row_num             INT,
    col_num             INT,
    raw_value           TEXT,                 -- openpyxl value, stringified
    raw_type            TEXT,                 -- 'number' | 'text' | 'bool' | 'date' | 'empty' | 'formula' | 'error'
    formula_text        TEXT,                 -- '=' stripped, raw
    formula_normalized  TEXT,                 -- after =+ strip, whitespace tidy
    cached_value        TEXT,                 -- from data_only=True
    value_type          TEXT,                 -- CLEANED: 'number' | 'text' | 'quantity_text' | 'date' | 'bool' | 'empty' | 'error'
    numeric_value       NUMERIC,              -- coerced
    text_value          TEXT,                 -- trimmed
    is_merged_anchor    BOOLEAN DEFAULT false,
    is_merged_participant BOOLEAN DEFAULT false,
    merged_range        TEXT,                 -- 'D3:J3'
    row_hidden          BOOLEAN DEFAULT false,
    col_hidden          BOOLEAN DEFAULT false,
    sheet_hidden        BOOLEAN DEFAULT false,
    is_error            BOOLEAN DEFAULT false,
    error_type          TEXT,                 -- '#REF!' | '#VALUE!' | '#DIV/0!' | '#NAME?' | '#NUM!' | '#NULL!' | '#N/A'
    error_descendant    BOOLEAN DEFAULT false,
    external_ref        TEXT,                 -- '[15]Manpower!F35'
    sheet_refs          JSONB,                -- list of local sheet refs
    region_id           INT REFERENCES region(region_id),
    is_scratch          BOOLEAN DEFAULT false,
    is_orphan           BOOLEAN DEFAULT false,
    extraction_conf     REAL DEFAULT 1.0
);

CREATE TABLE region (
    region_id           SERIAL PRIMARY KEY,
    worksheet_id        INT REFERENCES worksheet(worksheet_id),
    start_row           INT,
    end_row             INT,
    start_col           INT,
    end_col             INT,
    header_row          INT,
    region_type         TEXT,                 -- 'cost_head' | 'vendor_block' | 'pnl' | 'bs' | 'cash_flow' | 'debt_schedule' | 'mof' | 'capacity' | 'utility' | 'timeline' | 'support' | 'scratch' | 'unknown'
    cost_head_code      TEXT,                 -- see §5 vocabulary
    cost_head_label     TEXT,
    serial_pattern      TEXT,                 -- 'numeric' | 'alpha_dot' | 'alpha_dash' | 'mixed' | 'none'
    inferred_currency   TEXT,
    schema_json         JSONB,                -- [{col, name, type}, ...]
    confidence          REAL
);

CREATE TABLE cost_head (
    cost_head_id        SERIAL PRIMARY KEY,
    mandate_id          INT NOT NULL,
    code                TEXT NOT NULL,
    label               TEXT,
    classification      TEXT,                 -- 'capex' | 'opex' | 'pre_op' | 'working_capital' | 'contingency'
    fm_total            NUMERIC,              -- SUM of FM cells in the matching region
    fm_cell_ref         TEXT,                 -- anchor cell: 'CAPITAL COST!D31'
    -- quotation_total, unquoted_amount, unquoted_pct populated by the
    -- downstream quote parser, NOT by this parser
    review_status       TEXT DEFAULT 'pending',
    UNIQUE(mandate_id, code)
);

CREATE TABLE provenance (
    provenance_id       SERIAL PRIMARY KEY,
    source_kind         TEXT NOT NULL,        -- 'fm_cell' | 'fm_region' | 'pdf_page' | 'pdf_line' | 'xlsx_row' | 'external'
    document_id         INT REFERENCES document(document_id),
    document_name       TEXT,
    page_number         INT,
    line_number         INT,
    sheet_name          TEXT,
    cell_coord          TEXT,
    bbox                JSONB,
    raw_text            TEXT,
    extraction_method   TEXT,                 -- 'openpyxl' | 'pdfplumber' | 'ocr.tesseract' | 'llm.vision'
    extraction_conf     REAL,
    captured_at         TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE audit_log (
    log_id              BIGSERIAL PRIMARY KEY,
    mandate_id          INT,
    action              TEXT,                 -- 'parser_ingest' | 'region_detected' | etc.
    actor               TEXT,                 -- 'system' | analyst_user_id
    details             JSONB,
    timestamp           TIMESTAMPTZ DEFAULT now()
);

-- Indexes for the queries downstream will run
CREATE INDEX idx_cell_worksheet_coord ON cell(worksheet_id, coord);
CREATE INDEX idx_cell_region         ON cell(region_id);
CREATE INDEX idx_cell_numeric        ON cell(numeric_value) WHERE numeric_value IS NOT NULL;
CREATE INDEX idx_cell_text           ON cell(text_value) WHERE text_value IS NOT NULL;
CREATE INDEX idx_cell_formula        ON cell(formula_text) WHERE formula_text IS NOT NULL;
CREATE INDEX idx_region_worksheet    ON region(worksheet_id);
CREATE INDEX idx_region_cost_head    ON region(cost_head_code);
CREATE INDEX idx_cost_head_mandate   ON cost_head(mandate_id);
CREATE INDEX idx_prov_doc            ON provenance(document_id);
```

**Cell-level write discipline:**

- `raw_value` is **always** the openpyxl value (or its string form if
  non-string). Never overwrite.
- `numeric_value` / `text_value` are **cleaned** views, written only
  when the parser has positive confidence. `text_value` for a numeric
  cell stays NULL, and vice versa.
- Formula cells: `raw_value` holds the formula text, `formula_text`
  holds it without the leading `=`, `cached_value` holds the
  last-evaluated value from `data_only=True`, and `numeric_value` /
  `text_value` are derived from `cached_value` (not from the formula).
- Error cells: `cached_value` is the error token (`#REF!`); we
  *don't* try to coerce; `is_error=true`, `error_type` is set.

---

## 3. Handling the 15 Mess Findings

This is the meat. Each section says: what we keep, what we tag, what
the downstream consumer can rely on.

### 3.1 Hidden vs. Visible Sheets (Finding 1)

**What we do:**
- Load **every sheet**, including `veryHidden` ones (openpyxl loads them;
  we don't filter on visibility).
- Tag `worksheet.sheet_state` and `worksheet.role`.
- `role = 'support'` for sheets that exist purely to feed other sheets
  (e.g. `Pages`, `BS_ANLYSIS`, `details of fixed assets`). Detection
  heuristic: ≤ 60 rows, no own labels in column A, formula precedents
  go *out* of the sheet (i.e. it sources, not sinks).
- `role = 'primary'` for the model-facing sheets.
- `role = 'scratch'` for sheets that are entirely scratch (rare; the
  workbook only has scratch *islands* in primary sheets, see §3.6).

**Downstream guarantee:** every cell in every sheet is queryable. The
discrepancy engine never has to ask "did you skip something?".

```python
def classify_sheet_role(ws, name: str) -> str:
    bbox = compute_bbox(ws)
    if bbox == (0, 0, 0, 0):
        return "empty"
    a_col_labels = sum(
        1 for r in range(bbox[0], bbox[2] + 1)
        if ws.cell(r, 1).value and isinstance(ws.cell(r, 1).value, str)
    )
    if a_col_labels < 3:
        return "support"
    return "primary"
```

---

### 3.2 External Workbook Links (Finding 2)

**What we do:**
- During cell ingestion, regex `\[\d+\][^!]+!` on `formula_text`.
- If found, write `cell.external_ref = '[15]Manpower!F35'`.
- Set `cell.extraction_conf = 0.7` (downstream knows it's an unverified
  external source).
- `cached_value` is whatever `data_only=True` gave us (often stale or
  empty). Don't try to fetch — that's a separate service.

**Downstream guarantee:** every external reference is captured verbatim
and traceable. The quote parser, the audited-statements parser, and
the discrepancy engine can match on `cell.external_ref` if needed.

```python
import re
EXTERNAL_RE = re.compile(r"\[(\d+)\][^!]+!([A-Z]+\d+(?::[A-Z]+\d+)?)", re.IGNORECASE)

def extract_external_ref(formula: str) -> str | None:
    if not formula:
        return None
    m = EXTERNAL_RE.search(formula)
    return m.group(0) if m else None
```

---

### 3.3 Defined-Name Pollution (Finding 3)

**What we do:**
- Read all defined names into a temp dict during ingestion.
- After all cells are loaded, prune: keep only names that appear as
  tokens in at least one retained formula.
- Drop the rest silently. They are not loaded into the DB.

**Downstream guarantee:** `provenance` and `cell.external_ref` are
clean — no spurious defined-name noise. The defined-name dict is
optionally dumped into `document.raw_metadata` for forensic access.

---

### 3.4 Dirty Sheet Names (Finding 4)

**What we do:**
- Store `worksheet.sheet_name` **verbatim** — `"P  L "`, `" wORKING CAPITAL"`,
  `"B  S "`, all with their weird spaces.
- Build a `SheetIndex` helper with two methods:
  - `by_exact(name)` for formula resolution (case-sensitive, space-sensitive).
  - `by_normalized(name)` for diagnostics (strip + casefold), returns
    a list of matching sheet names.
- Formula lexer handles quoted names: `'P  L '!D23`, `Manpower!A3`,
  `' wORKING CAPITAL'!E22`.

**Downstream guarantee:** any query by sheet name is exact-match. UI
layer is responsible for showing trimmed/cleaned names to the user
but the underlying FK is verbatim.

```python
class SheetIndex:
    def __init__(self, sheets: list[str]):
        self.exact = {s: s for s in sheets}
        self.norm = defaultdict(list)
        for s in sheets:
            self.norm[s.strip().casefold()].append(s)

    def resolve(self, token: str) -> str | None:
        if token in self.exact:
            return token
        matches = self.norm.get(token.strip().casefold(), [])
        return matches[0] if len(matches) == 1 else None
```

---

### 3.5 Phantom / Unreliable Used Ranges (Finding 5)

**What we do:**
- Never trust `ws.dimensions`.
- Compute the real bounding box from three sources, take the union:
  1. **Cell content**: min/max row & col over non-empty cells.
  2. **Merges**: min/max of every `merged_cells.ranges` entry.
  3. **Formula precedents**: for every formula in the sheet, parse
     cell references; extend the box to include the *referenced* cell
     (only if the referenced cell is on the same sheet — cross-sheet
     precedents go to their own sheet's box).
- Store the computed box as `worksheet.bbox_*`; store the declared
  box as `worksheet.dimensions_declared` for diagnostic.

**Downstream guarantee:** `region.start_row >= worksheet.bbox_min_row`
always. No out-of-bounds cells in regions.

```python
def compute_bbox(ws) -> tuple[int, int, int, int]:
    rows, cols = [], []
    for row in ws.iter_rows():
        for c in row:
            if c.value is not None:
                rows.append(c.row); cols.append(c.column)
    for mr in ws.merged_cells.ranges:
        rows += [mr.min_row, mr.max_row]
        cols += [mr.min_col, mr.max_col]
    if not rows:
        return (0, 0, 0, 0)
    return (min(rows), min(cols), max(rows), max(cols))
```

---

### 3.6 Scratch / Rough Work (Finding 6)

**What we do:**
- **Don't delete.** Scratch islands are sometimes load-bearing
  (e.g. `'B  S '!Q11:R18` is a live mini-analysis).
- Detect and tag with `is_scratch = true` so downstream can ignore
  them by default.
- Detection heuristics (run in order; first match wins):
  1. **Unlabeled formula cluster**: ≥ 3 consecutive rows have formulas
     in cols B+ but column A is empty for the whole stretch.
  2. **Orphan page number**: numeric in row ≤ 3, cols A–G, value
     that also appears in `Pages!*` cells.
  3. **Disabled line**: formula matching `<num> - <num>` where the
     diff is 0, or formula ending in `*0` or `=0`.
  4. **Single-cell sum of constant**: `=SUM(Xn:Xn)` where `Xn` is
     itself `=<num>`.
  5. **Note in data area**: text starting with `N.B.`, `Note:`,
     `*` in a column that's otherwise numeric.
  6. **Mid-table header**: a row with header-like formatting (bold,
     centered, fill) inside a data region (e.g. `Interest!I33`).

- For the **secondary mini-analysis** case (B  S `Q11:R18`), check
  whether the scratch block has formula precedents going *out* of the
  block. If yes, downgrade `is_scratch` to `False` and re-classify the
  region as `region_type='support'` (or whatever it actually is).

**Downstream guarantee:** the discrepancy engine filters by
`is_scratch = false` by default, but a power user can re-include
scratch cells via SQL if they're investigating provenance.

```python
def detect_unlabeled_island(ws, bbox):
    islands = []
    in_island = False
    start_row = start_col = end_col = 0
    for r in range(bbox[0], bbox[2] + 1):
        a_val = ws.cell(r, 1).value
        formulas = [c for c in range(2, bbox[3] + 1)
                    if isinstance(ws.cell(r, c).value, str)
                    and ws.cell(r, c).value.startswith("=")]
        if not a_val and len(formulas) >= 2:
            if not in_island:
                in_island = True
                start_row = r
                start_col = min(range(2, bbox[3] + 1),
                                key=lambda c: 1 if not formulas else 999)
            end_col = max(c for c in range(2, bbox[3] + 1) if formulas)
        else:
            if in_island and r - start_row >= 2:
                islands.append((start_row, r - 1, start_col, end_col))
            in_island = False
    if in_island and (bbox[2] - start_row) >= 2:
        islands.append((start_row, bbox[2], start_col, end_col))
    return islands
```

---

### 3.7 Gap Pathology (Finding 7)

**What we do:**
- Region detection uses **label coherence**, not blank-as-separator.
- Coherence signals (score must be ≥ 2 to count as a real break):
  1. `+1` if a row has a non-default border-bottom.
  2. `+2` if a row has a non-default fill.
  3. `+1` if column A's label changes value AND the new value is
     non-empty.
  4. `+2` if formulas above and below reference different
     "anchor" cells (e.g. switching from `D$18` references to a new
     section's anchors).
  5. `+1` if a column-count change of ≥ 3 occurs between rows.
- Blank rows/cols are **never** automatic breaks.
- Hidden rows/cols: keep in the data, tag `row_hidden`/`col_hidden`.
  Region detection ignores hidden state (since hidden rows can still
  be in a sum, like `SALESPROJECTION!D12` summing across hidden
  rows 18–24).

**Downstream guarantee:** the depreciation sheet's 71 blank rows
collapse into one region; the multi-schema `Details` sheet splits
into ~10 regions; nothing depends on row emptiness for boundaries.

---

### 3.8 Error Cells and Cascading Failures (Finding 8)

**What we do:**
- **At ingestion**, classify every cell by looking at both
  `raw_value` and `cached_value`:
  ```python
  def classify(cell, raw, cached):
      if cached is None and raw is None:
          cell.value_type = "empty"; return
      if isinstance(cached, str) and cached.startswith("#") and cached.endswith(("!", "?")):
          cell.is_error = True
          cell.error_type = cached
          cell.value_type = "error"
          return
      if isinstance(raw, str) and raw.startswith("="):
          cell.formula_text = raw[1:]
          cell.value_type = "formula"   # reclassified to number/text after eval
          # cached_value is what the formula evaluated to
          if cached is not None:
              if isinstance(cached, (int, float)):
                  cell.value_type = "number"; cell.numeric_value = cached
              elif isinstance(cached, str) and not cached.startswith("#"):
                  cell.value_type = "text"; cell.text_value = cached.strip()
          return
      # raw is a literal
      if isinstance(raw, (int, float)):
          cell.value_type = "number"; cell.numeric_value = raw
      elif isinstance(raw, bool):
          cell.value_type = "bool"
      elif isinstance(raw, str):
          cell.text_value = raw.strip()
          if not cell.text_value:
              cell.value_type = "empty"
          elif re.fullmatch(r"-?\d+(\.\d+)?", cell.text_value.replace(",", "")):
              cell.value_type = "number"
              cell.numeric_value = float(cell.text_value.replace(",", ""))
              cell.coerced_from_text = True
          else:
              cell.value_type = "text"
  ```
- **After ingestion**, walk the cell dependency graph to mark
  `error_descendant` for cells whose evaluation chain passes through
  any `is_error` cell.
- Keep `#REF!` and `#VALUE!` cells in the DB with `is_error=true`.
  They are facts about the model.

**Downstream guarantee:** every error cell is preserved with its type
and propagation chain. The discrepancy engine can ignore errors or
treat them as "unable to validate" by default.

```python
def mark_error_descendants(graph: dict[str, Cell]):
    error_cells = [c for c in graph.values() if c.is_error]
    for ec in error_cells:
        for dependent in descendants_of(graph, ec):
            if not dependent.is_error:
                dependent.error_descendant = True
```

---

### 3.9 Formula-Style Inconsistencies (Finding 9)

**What we do:**

**`=+` prefix (3.9.1):**
- Store `formula_text` with the `=+` intact (forensic value).
- Store `formula_normalized` with the `+` stripped.
- Never silently rewrite `formula_text`.

```python
def normalize_formula(f: str) -> str:
    if f.startswith("=+"):
        return "=" + f[2:]
    return f
```

**Constants written as formulas (3.9.2):**
- Detect with `detect_formula_constant` (see below).
- If detected, set `value_type = 'number'` directly, set
  `numeric_value` from the constant, and keep the formula text in
  `formula_text` for provenance.
- Don't try to eval — just parse with `ast.literal_eval` for the
  trivial arithmetic cases.

```python
def detect_formula_constant(formula: str) -> bool:
    f = formula.lstrip("=+").strip()
    if re.fullmatch(r"-?\d+(\.\d+)?", f):
        return True
    if re.fullmatch(r"[\d\.\+\-\*\/\(\)\s]+", f):
        try:
            ast.literal_eval(f)
            return True
        except (ValueError, SyntaxError):
            return False
    return False
```

**Inconsistent anchoring within a row (3.9.3):**
- Don't infer fill patterns. Each formula is its own AST.
- Optionally: in `audit_log`, emit a `row_anchor_drift` event when
  two adjacent columns in the same row have differing anchor
  styles (e.g. `$D$18` vs `D18`). This becomes a Phase 2 audit
  signal.

**Percent literals and exponentiation (3.9.4):**
- For value extraction, implement a small expression evaluator that
  understands:
  - Postfix `%` (e.g. `14.5%` → 0.145)
  - `^` exponentiation
  - Unary `+` and `-`
  - Parentheses
- Or: defer evaluation to `xlcalculator` / `pycel` / `formulas` library.
  Recommended: `formulas` because it handles all the corner cases
  including `[N]Book!Cell` and 3D refs.

**Downstream guarantee:** every formula cell has a `numeric_value` or
`text_value` if the cached value is evaluable; otherwise the cached
error is preserved. The parser is robust to formula style variation.

---

### 3.10 Multi-Table / Multi-Schema Sheets (Finding 10)

**What we do:**
- Per-region schema detection (see §6).
- Within each region, the `schema_json` column records the inferred
  column types so the downstream quote-parser can match
  `Details!F175` (description) to `quotation_line_item.description`
  by column role, not by name.
- Serial-number breaks are detected and stored in `region.serial_pattern`:
  - `numeric` if 1, 2, 3, ... with no breaks.
  - `alpha_dot` if A., B., C., ...
  - `alpha_dash` if ST.01, ST.02, ...
  - `mixed` if pattern resets or mixes styles.
  - `none` if there's no serial.
- Headers with newlines (e.g. `C45 = "Unit Price\n(Excl…"`) are
  normalised in `region.schema_json` but the raw text is preserved
  on the header cell.

**Downstream guarantee:** the DB has clean `region` rows for each
sub-table, and within each region the column-role-to-letter map is
explicit. The quote parser can join on column role.

---

### 3.11 Header and Label Quirks (Finding 11)

**What we do:**

**Labels that are formulas (3.11.1):**
- If `value_type` resolves to text after classification, the cell's
  `text_value` is the label.
- Don't store the formula as the label.

**Whitespace (3.11.2):**
- `text_value` is the trimmed/cleaned version.
- `raw_value` is the original.
- Both are kept. Use `text_value` for matching, `raw_value` for
  display/audit.

**Inconsistent year headers (3.11.3):**
- The parser does **not** match year headers by string.
- Instead, it stores `region.inferred_period_axis` as a JSONB
  mapping `{col_letter: year_index}` where 0 = construction period,
  1 = Year 1, etc.
- Detection patterns (in order):
  - `r"year\s*(\d+)"` → index = int(group)
  - `r"(\d+)\s*(?:st|nd|rd|th)\s*yr"` → index = int(group)
  - `r"(\d{2,4})\s*[-–]\s*(\d{2,4})"` → index = starting year minus base
  - `r"construction\s*year"` → index = 0
  - `r"fy\s*(\d{2,4})"` → index = int(group) - base_year

**Downstream guarantee:** any cell query that needs year alignment
joins on `region.inferred_period_axis`, not on header text. The Phase 2
ratio audit and the cross-doc correlator can align P&L columns
across sheets by year index.

---

### 3.12 Type and Value Oddities (Finding 12)

**What we do:**

**Numbers stored as text (3.12.1):**
- If `text_value` matches a numeric pattern, populate `numeric_value`
  and set `value_type = 'number'`. The original text is preserved in
  `raw_value`.
- Quote parser (downstream) does the same on its side.

**Ultra-precise floating inputs (3.12.2):**
- Store full precision in `numeric_value` (PostgreSQL `NUMERIC` handles
  arbitrary precision).
- Don't round during ingestion.
- If the user needs display rounding, do it in the view layer.

**Text/number mixtures in quantity columns (3.12.3):**
- `value_type = 'quantity_text'`.
- A separate `parsed_quantity` JSONB column on `cell` (or computed view):
  ```sql
  parsed_quantity JSONB  -- {"count": 1, "unit": "Set", "raw": "1Set"}
  ```
  Parser computes this; downstream quote parser uses the same convention
  so cross-sheet quantity joins work.

**Duplicate data across sheets (3.12.4):**
- Compute a row-content hash per row during Layer 1.
- Store hashes in a `cell.row_hash` column (or in `audit_log`).
- Don't auto-dedup. The DB exposes the duplicates; downstream decides
  the canonical source.

```python
QTY_RE = re.compile(r"^\s*(\d+(?:\.\d+)?)\s*([A-Za-z]+)?\s*$")

def parse_quantity(s: str) -> dict | None:
    if not isinstance(s, str):
        return None
    m = QTY_RE.match(s)
    if m:
        return {"count": float(m.group(1)), "unit": m.group(2)}
    return {"count": None, "unit": s.strip(), "raw": s}
```

---

### 3.13 Merged Cells (Finding 13)

**What we do:**
- Walk `ws.merged_cells.ranges` after cell ingestion.
- For each range: top-left is `is_merged_anchor=true` with the value;
  the other cells get `is_merged_participant=true` and the same
  `merged_range` string.
- Participants have `numeric_value`/`text_value` derived from the
  anchor's values, so a query that does `SELECT text_value FROM cell
  WHERE coord='E5'` returns the merged title even though E5 was a
  participant.
- The anchor cell stores the same data; consumers shouldn't have to
  care.

```python
def annotate_merges(ws, cells: dict[str, Cell]):
    for mr in ws.merged_cells.ranges:
        anchor_coord = f"{get_column_letter(mr.min_col)}{mr.min_row}"
        rng = str(mr)
        cells[anchor_coord].is_merged_anchor = True
        cells[anchor_coord].merged_range = rng
        anchor_text = cells[anchor_coord].text_value
        anchor_num = cells[anchor_coord].numeric_value
        for r in range(mr.min_row, mr.max_row + 1):
            for c in range(mr.min_col, mr.max_col + 1):
                if (r, c) == (mr.min_row, mr.min_col):
                    continue
                coord = f"{get_column_letter(c)}{r}"
                if coord in cells:
                    cells[coord].is_merged_participant = True
                    cells[coord].merged_range = rng
                    cells[coord].text_value = anchor_text
                    cells[coord].numeric_value = anchor_num
```

---

## 4. Region Detection Algorithm

The single most important Layer-2 function. Two passes:

### 4.1 Pass 1 — find candidate regions

```python
def detect_regions(ws, bbox) -> list[tuple[int, int]]:
    """Returns list of (start_row, end_row)."""
    if bbox == (0, 0, 0, 0):
        return []
    regions = []
    cur_start = None
    blank_streak = 0
    for r in range(bbox[0], bbox[2] + 1):
        non_empty = sum(
            1 for c in range(bbox[1], bbox[3] + 1)
            if ws.cell(r, c).value not in (None, "")
        )
        density = non_empty / max(1, bbox[3] - bbox[1] + 1)
        if density < 0.15:
            blank_streak += 1
        else:
            if cur_start is None:
                cur_start = r
            blank_streak = 0
        # End a region on: 2+ blank rows, or a sharp density drop with
        # a label change in column A.
        if cur_start is not None and blank_streak >= 2:
            end_row = r - blank_streak
            if end_row >= cur_start:
                regions.append((cur_start, end_row))
            cur_start = None
            blank_streak = 0
        elif cur_start is not None and density > 0.6 and blank_streak > 0:
            # may be a separate table; close current and start new
            end_row = r - blank_streak
            regions.append((cur_start, end_row))
            cur_start = r
            blank_streak = 0
    if cur_start is not None:
        regions.append((cur_start, bbox[2]))
    return regions
```

### 4.2 Pass 2 — refine each region

For each candidate region:
1. Find the header row (the row with the most distinct text values,
   centred/bold formatting, no numeric data).
2. If no header row is found, the region is a `support` or `scratch`
   region; tag and skip the rest.
3. If a header row is found, infer column types from the rows below.
4. Classify the region:
   - If column A holds a serial (1, 2, 3...) and the region sits in
     `Details` → `vendor_block`.
   - If the header contains "Cost", "Cost of", or matches a cost-head
     alias → `cost_head`. Tag `cost_head_code` and `cost_head_label`.
   - If the header row is years (`Year 1..10`) and the columns sum to
     a P&L/BS shape → `pnl` / `bs` / `cash_flow` / `debt_schedule`.
   - If the header is a single label like "Yearly Int" mid-table →
     tag as inline-summary, do not split (already handled by gap
     detection, but flag in audit log).
   - If the region is the only one in the sheet and column A holds
     vertical-form labels (`AT GLANCE`) → `support` (or special
     `vertical_form` subtype).

```python
def classify_region(ws, region, header_row) -> tuple[str, dict]:
    header_texts = [str(ws.cell(header_row, c).value or "").lower()
                    for c in range(region[2], region[3] + 1)]
    a_col = [ws.cell(r, 1).value for r in range(region[0], region[1] + 1)]
    if any("year" in t and re.search(r"\d", t) for t in header_texts):
        if any("asset" in t for t in header_texts):
            return ("bs", {"period_axis": detect_period_axis(ws, header_row)})
        if any("liability" in t or "equity" in t for t in header_texts):
            return ("bs", {"period_axis": detect_period_axis(ws, header_row)})
        return ("pnl", {"period_axis": detect_period_axis(ws, header_row)})
    if is_serial_column(a_col):
        return ("vendor_block", {"serial_pattern": detect_serial_pattern(a_col)})
    label = str(a_col[header_row - region[0]] or "").lower()
    code = match_cost_head(label)
    if code:
        return ("cost_head", {"cost_head_code": code, "cost_head_label": label})
    return ("unknown", {})
```

---

## 5. Cost Head Detection

The Phase 1 cost-head vocabulary (locked vocabulary, used by both
this parser and the downstream quote parser):

```python
COST_HEADS = {
    "LAND":              ["land", "land cost", "land & site", "site cost"],
    "SITE_DEVELOPMENT":  ["site development", "land development", "site preparation"],
    "CIVIL":             ["civil", "civil works", "building", "construction", "civil & structural"],
    "PLUMBING":          ["plumbing", "sanitary"],
    "FIRE_FIGHTING":     ["fire fighting", "fire protection", "fire system"],
    "PLANT_MACHINERY":   ["plant & machinery", "p&m", "machinery", "equipment"],
    "KITCHEN_EQUIPMENT": ["kitchen", "kitchen equipment", "kitchen/store"],
    "WATER_TREATMENT":   ["water treatment", "wtp", "effluent treatment", "etp", "stp"],
    "LIFTS":             ["lifts", "elevators"],
    "HVAC":              ["hvac", "air conditioning", "ventilation"],
    "ELECTRICAL":        ["electrical", "electrical works", "wiring"],
    "GENERATOR":         ["dg set", "dg", "generator", "genset"],
    "LED_LIGHTING":      ["led", "lighting"],
    "MISC_EQUIPMENT":    ["miscellaneous", "furniture", "it", "computer", "office equipment"],
    "PRE_OPERATIVE":     ["pre-operative", "preoperative", "preliminary", "pre-op"],
    "WORKING_CAPITAL":   ["working capital", "margin money"],
    "CONTINGENCY":       ["contingency"],
}

def match_cost_head(label: str) -> str | None:
    if not label:
        return None
    n = label.lower().strip()
    for code, aliases in COST_HEADS.items():
        for alias in aliases:
            if n == alias or alias in n:
                return code
    return None
```

**Parser output:** for each detected `cost_head` region, the parser
writes:
- A `region` row with `region_type='cost_head'`, `cost_head_code`, etc.
- A `cost_head` row with `fm_total = SUM(numeric_value WHERE region
  matches)`, `fm_cell_ref = anchor cell of the cost-head label`.

**Downstream hook:** the quote parser, when it loads quotations, will
fill in `cost_head.quotation_total` and `cost_head.unquoted_amount`
on these same rows. The discrepancy engine then computes the
variance.

---

## 6. End-to-End Pipeline

```python
from openpyxl import load_workbook
from openpyxl.utils import get_column_letter

def parse_fm(xlsx_path: str, mandate_id: int) -> dict:
    """Returns dict of records ready for DB load."""
    wb_f = load_workbook(xlsx_path, data_only=False)
    wb_d = load_workbook(xlsx_path, data_only=True)
    sheet_index = SheetIndex([ws.title for ws in wb_f.worksheets])

    document = create_document_record(mandate_id, xlsx_path, wb_f)
    workbook = create_workbook_record(document, wb_f)
    worksheets, cells = ingest_all_cells(wb_f, wb_d, workbook, sheet_index)

    # Layer 2
    regions = detect_all_regions(worksheets, cells)
    cost_heads = rollup_cost_heads(regions, cells)

    # Scratch / errors / external
    tag_scratch_islands(worksheets, cells, regions)
    mark_error_descendants(cells)

    return {
        "document": document,
        "workbook": workbook,
        "worksheets": worksheets,
        "cells": cells,
        "regions": regions,
        "cost_heads": cost_heads,
    }
```

A helper that's worth having — converts the in-memory dict to SQL
`INSERT` statements or SQLAlchemy ORM objects. Either:

```python
def to_sql(payload: dict, engine):
    with engine.begin() as conn:
        conn.execute(insert(Document), payload["document"])
        conn.execute(insert(Workbook), payload["workbook"])
        conn.execute(insert(Worksheet), payload["worksheets"])
        # bulk insert cells in chunks of 10k
        for chunk in chunked(payload["cells"].values(), 10_000):
            conn.execute(insert(Cell), [asdict(c) for c in chunk])
        conn.execute(insert(Region), payload["regions"])
        conn.execute(insert(CostHead), payload["cost_heads"])
```

---

## 7. Test Fixtures (Parser-Only)

From `OM Arham Ventures.xlsx` — these become the parser's test
suite. Each row maps a finding to a Python test.

| Test | Source | Assertion |
|---|---|---|
| `test_hidden_sheet_loaded` | `Pages`, `BS_ANLYSIS` | `worksheet` rows with `sheet_state='hidden'`, `role='support'` |
| `test_external_ref_tagged` | `CAPITAL COST!I19` | `cell.external_ref='[15]Manpower!F35'`, `extraction_conf<1.0` |
| `test_defined_name_pruned` | `cmb_TDS2.StateCode` | not in any retained formula token list |
| `test_dirty_sheet_resolved` | `'P  L '!D35` → `Manpower!A3` | `cell.sheet_refs` contains `Manpower` (verbatim) |
| `test_bbox_real` | `AT GLANCE` | `bbox_min_row=3`, not 1; `dimensions_declared='A1:J44'` |
| `test_scratch_island_flagged` | `Interest!F176:G185` | all cells `is_scratch=true` |
| `test_scratch_with_precedents_kept` | `B  S !Q11:R18` | `is_scratch=false` (has out-going precedents) |
| `test_blank_row_keeps_region` | `depreciation` rows 10–55 | one `region` row, `end_row=55` |
| `test_error_cascade` | `B  S !M16` | `error_descendant=true`, `is_error=false` |
| `test_literal_ref_error` | `P  L !D60 = =#REF!` | `is_error=true`, `error_type='#REF!'` |
| `test_equals_plus_normalized` | `Interest!B13` | `formula_text='+B12+1'`, `formula_normalized='B12+1'` |
| `test_constant_formula` | `SALESPROJECTION!F41 = =200/2` | `numeric_value=100.0`, `formula_text='200/2'` |
| `test_multi_schema_details` | `Details` rows 1–234 | ≥ 8 `region` rows with `region_type='vendor_block'` |
| `test_serial_break_detected` | `Floor area!A19:A28` (1,2,3,4,5,6,7,8,9,10 but row 23 is `5` hardcoded) | `serial_pattern='mixed'`, audit log entry |
| `test_year_axis_alignment` | `B  S ` and `depreciation` | both have `inferred_period_axis` mapping D→1, E→2, ..., M→10 |
| `test_merged_anchor_inheritance` | `B  S !C5:K5` | anchor and all participants have same `text_value` |
| `test_text_stored_number_coerced` | `power cost!F3 = "34"` | `numeric_value=34.0`, `raw_value='34'` |
| `test_quantity_text_parsed` | `Details!C145 = "1Set"` | `value_type='quantity_text'`, `parsed_quantity={'count':1.0,'unit':'Set'}` |
| `test_duplicate_rows_logged` | `ASSETS` rows 11–21 vs `Details` rows 3–13 | same `row_hash` recorded in `audit_log` |
| `test_cost_head_classification` | `CAPITAL COST` rows | `cost_head` rows for CIVIL, P&M, ELECTRICAL, etc., with `fm_total` populated |
| `test_hidden_row_in_sum` | `SALESPROJECTION!D12` sums hidden rows 18–24 | `cell.region_id` non-null even though those rows are `row_hidden=true` |
| `test_provenance_for_merged_cell` | `B  S !C5:K5` | every cell in the range has `provenance_id` linking to document + sheet + coord |

---

## 8. Downstream Consumers (Out of Scope for This Parser)

This parser's output feeds these modules. The schema and tags are
designed to make their jobs easy.

| Module | What it does | What it reads from this parser's output |
|---|---|---|
| **Quote parser** (PDF + xlsx) | Ingests vendor quotations, extracts line items | `region` (vendor_blocks), `cost_head` (FM totals to match against) |
| **Audited-statements parser** (PDF) | Ingests 3–5 year BS / P&L | `region` (pnl, bs regions) for canonical line-item mapping |
| **DPR field extractor** (PDF) | Extracts project-summary fields | `cell` rows in `dpr_field` adjacent sheets if FM has DPR-like data |
| **Sanction / NWC parser** (PDF) | Extracts debt terms, promoter net worth | (no FM dependency, but uses `cost_head` classification for context) |
| **Cross-doc correlator** | Matches FM values to supporting-doc values | `cell` (FM-side), `cost_head` (FM totals) |
| **Discrepancy engine** | Runs the 25 checks | `cost_head` (variance), `cell` (specific values), `region` (period axis) |
| **Project summary builder** | Composes the summary tab | `cost_head`, `cell` (timeline, capacity) |
| **Export (Word/PDF)** | Renders the analyst deliverables | everything |

The key cross-module handshake: **`cost_head.code`** is the
shared key. The parser fills the FM side; the quote parser fills the
quotation side; the discrepancy engine computes the variance on the
joined row.

---

## 9. Implementation Plan (Parser-Only)

| Sprint | Deliverable |
|---|---|
| S1 | Layer 1 — cell ingestion with all 15 mess findings handled. `cell`, `worksheet`, `workbook`, `document` tables populated. Tests for §3.1–3.5, 3.8, 3.9, 3.13. |
| S2 | Layer 2 — region detection + cost-head tagging. `region`, `cost_head` tables populated. Tests for §3.6, 3.7, 3.10, 3.11, 3.12. |
| S3 | Provenance + audit log. SQLAlchemy models + bulk loader. Performance pass to hit 3-min NFR on the OM Arham Ventures workbook. |

After S3, the parser is done. The next modules (quote parser,
audited-statements parser, etc.) start consuming its output.

---

*End of parser strategy. The 15 mess findings from the source
analysis map 1:1 to §3.1–3.13; the DB tables in §2 are exactly the
parser's footprint; everything in §8 is downstream.*
