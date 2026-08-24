# Excel Parser — Full Strategy v2 (Reviewed)

> Supersedes `parser-strategy.md`.  
> Input reviewed against: `Project Docs/OM Arham Ventures - Parser Complexity Findings.md`.  
> Goal for Phase 1: ingest `.xlsx`, `.xls`, and `.csv` client financial models into a queryable cell graph, tag regions and cost heads, preserve formulas/provenance, and expose errors/scratch explicitly instead of “cleaning them away”.
> Sprint 3b design locked on 2026-08-24 after inspecting Sprint 3a's committed 47-sheet region snapshot and completing the review-gated rollup design interview; see ADRs 0005–0007.

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
| G10 | Cost-head rollup says `SUM(numeric_value WHERE region matches)` | Sums line items + subtotals + totals + merged duplicates | Canonical cost heads compose provenance-linked explicit/structural contributions; leaf-only fallback stays review-gated (§8) |
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
  -> L3 region + review-gated cost-head semantics
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

> **Implementation stack.** This document was originally drafted against a Python/PostgreSQL stack. The
> implemented parser is **Java 25 + Maven + Apache POI + SQLite** — see
> [ADR 0001](../docs/adr/0001-use-java-25-and-maven-for-the-parser-platform.md) and
> [ADR 0002](../docs/adr/0002-adapt-the-postgresql-semantic-schema-to-sqlite.md). Library names below
> reflect the implementation; the SQL in §4 remains PostgreSQL-flavoured reference, with ADR 0002
> governing the actual SQLite shape.

| Format | Reader | Notes |
|---|---|---|
| `.xlsx` / `.xlsm` | Apache POI (XSSF) | Formulas + cached values via two loads (formula view and value view). |
| `.xls` | Apache POI (HSSF) | Formula text is usually available; some records cannot be decoded back to a formula string — store `formula_text=NULL` and set `formula_state='unavailable'`. |
| `.csv` | FastCSV + encoding sniffer | Sniff delimiter, quote char, encoding; no formulas; one worksheet per file. |

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
  schemas -> units/currency -> scratch/support/orphan + duplicates -> cost-head candidates/trust
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
    calc_chain_present  BOOLEAN,
    iterative_calc      BOOLEAN,              -- calcPr/@iterate; distinguishes deliberate cycles
    iterative_count     INT
);

CREATE TABLE worksheet (
    worksheet_id        SERIAL PRIMARY KEY,
    workbook_id         INT REFERENCES workbook(workbook_id),
    sheet_name          TEXT NOT NULL,        -- verbatim, e.g. 'P  L '
    sheet_index         INT,
    sheet_state         TEXT,                 -- 'visible' | 'hidden' | 'veryHidden'
    role                TEXT,                 -- 'primary' | 'support' | 'scratch' | 'unknown'
    role_conf           REAL DEFAULT 0,
    role_reasons        JSONB,
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
    error_root_cell_id  BIGINT,               -- display convenience only; full set in cell_error_root

    is_circular         BOOLEAN DEFAULT false,
    circular_group_id   INT,                  -- SCC identifier, shared by all members of a cycle

    -- Reference storage lives in cell_reference (§4.4), one row per reference token.
    -- The former scalar cell.external_ref was removed: a formula may carry many external
    -- refs (25 such formulas exist in the reference workbook) and a single column silently
    -- kept only the first, making the §12 external-ref gate unsatisfiable.

    row_hash            TEXT,                 -- normalized row signature
    region_id           INT,                  -- FK added after region table exists
    provenance_id       BIGINT,               -- FK added after provenance exists

    is_scratch          BOOLEAN DEFAULT false,
    scratch_reason      TEXT,
    is_orphan           BOOLEAN DEFAULT false,
    semantic_role       TEXT DEFAULT 'live', -- live|scratch|support|orphan; booleans above are query conveniences
    semantic_reasons    JSONB,
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
    inferred_currency_conf REAL DEFAULT 0,
    inferred_unit       TEXT,                 -- 'rs' | 'lakh' | 'crore' | 'unknown'
    inferred_unit_conf  REAL DEFAULT 0,
    period_axis         JSONB,                -- {"D":1,"E":2,...}
    schema_json         JSONB,                -- [{col,name,type,role,conf,reasons}]
    detection_reasons   JSONB
);

ALTER TABLE cell
    ADD CONSTRAINT fk_cell_region FOREIGN KEY (region_id) REFERENCES region(region_id);
```

**Region bounding boxes may overlap. This is a trap and must be documented at the schema.**
Components are ragged; the bbox is a four-number *summary* of a ragged shape, so an L-shaped
region's bbox routinely encloses cells belonging to another region. Membership is `cell.region_id`
and nothing else. The invariant that holds — and that §12 gates — is **"every occupied cell belongs
to exactly one region"**, never "bboxes tile the sheet". Any consumer that queries regions by bbox
and assumes disjointness will double-count.

**`region_key` gives a region identity that survives re-parsing and tuning.** `region_id` is a
per-run surrogate, so without a stable key an analyst's review decision is orphaned by the next
parse. The key is derived from `(worksheet_name, anchor_coord)` — the top-left *occupied* cell of
the component — plus a disambiguating ordinal in canonical `(start_row, start_col)` order where a
banner split leaves two regions sharing an anchor. It is deliberately **not** derived from the bbox:
boundary tuning moves the trailing edge, which is precisely what a stable identity must ignore.

```sql
CREATE TABLE cost_head (
    cost_head_id        SERIAL PRIMARY KEY,
    mandate_id          INT NOT NULL,
    code                TEXT NOT NULL,
    label               TEXT,
    classification      TEXT,                 -- capex|opex|pre_op|working_capital|contingency
    UNIQUE(mandate_id, code)
);

CREATE TABLE cost_head_mapping (
    cost_head_mapping_id BIGSERIAL PRIMARY KEY,
    parse_run_id         BIGINT NOT NULL REFERENCES parse_run(parse_run_id),
    source_file_id       BIGINT NOT NULL,
    cost_head_id         BIGINT NOT NULL REFERENCES cost_head(cost_head_id),
    region_id            BIGINT NOT NULL REFERENCES region(region_id),
    region_key           TEXT NOT NULL,
    match_method         TEXT NOT NULL,       -- exact_alias|fuzzy_proposal|analyst
    match_score          REAL,
    runner_up_margin     REAL,
    confidence           REAL NOT NULL,
    reasons              JSONB NOT NULL,
    UNIQUE(parse_run_id, region_id, cost_head_id)
);

CREATE TABLE cost_head_candidate (
    cost_head_candidate_id BIGSERIAL PRIMARY KEY,
    parse_run_id         BIGINT NOT NULL REFERENCES parse_run(parse_run_id),
    source_file_id       BIGINT NOT NULL,
    cost_head_id         BIGINT NOT NULL REFERENCES cost_head(cost_head_id),
    candidate_fingerprint TEXT NOT NULL,
    amount               NUMERIC,
    currency             TEXT,
    unit                 TEXT,
    automatic_trust_eligible BOOLEAN NOT NULL,
    confidence           REAL NOT NULL,
    reasons              JSONB NOT NULL,
    UNIQUE(parse_run_id, cost_head_id, candidate_fingerprint)
);

CREATE TABLE cost_head_contribution (
    cost_head_contribution_id BIGSERIAL PRIMARY KEY,
    cost_head_candidate_id BIGINT NOT NULL REFERENCES cost_head_candidate(cost_head_candidate_id),
    cost_head_mapping_id BIGINT REFERENCES cost_head_mapping(cost_head_mapping_id),
    region_id            BIGINT REFERENCES region(region_id),
    anchor_cell_id       BIGINT REFERENCES cell(cell_id),
    basis                TEXT NOT NULL,       -- explicit_total_anchor|structural_total|leaf_sum|manual
    source_amount        NUMERIC NOT NULL,
    source_currency      TEXT,
    source_unit          TEXT,
    normalized_amount    NUMERIC,
    normalized_currency  TEXT,
    normalized_unit      TEXT,
    confidence           REAL NOT NULL,
    reasons              JSONB NOT NULL
);

CREATE TABLE cost_head_contribution_cell (
    cost_head_contribution_id BIGINT NOT NULL REFERENCES cost_head_contribution(cost_head_contribution_id),
    cell_id              BIGINT NOT NULL REFERENCES cell(cell_id),
    participation       TEXT NOT NULL,       -- included|excluded
    reason              TEXT,                -- NULL when included; stable exclusion code otherwise
    PRIMARY KEY(cost_head_contribution_id, cell_id)
);

CREATE TABLE duplicate_proposal (
    duplicate_proposal_id BIGSERIAL PRIMARY KEY,
    parse_run_id         BIGINT NOT NULL REFERENCES parse_run(parse_run_id),
    left_region_id       BIGINT NOT NULL REFERENCES region(region_id),
    right_region_id      BIGINT NOT NULL REFERENCES region(region_id),
    method               TEXT NOT NULL,       -- exact_row_hash|shifted_block_signature
    score                REAL NOT NULL,
    reasons              JSONB NOT NULL,
    UNIQUE(parse_run_id, left_region_id, right_region_id, method)
);

CREATE TABLE duplicate_decision (
    duplicate_decision_id BIGSERIAL PRIMARY KEY,
    source_file_id       BIGINT NOT NULL,
    left_region_key      TEXT NOT NULL,
    right_region_key     TEXT NOT NULL,
    decision             TEXT NOT NULL,       -- Duplicate|Distinct
    superseded_region_key TEXT,
    actor                TEXT NOT NULL,
    reason               TEXT,
    decided_at           TIMESTAMPTZ NOT NULL,
    supersedes_id        BIGINT REFERENCES duplicate_decision(duplicate_decision_id)
);

CREATE TABLE manual_contribution (
    manual_contribution_id BIGSERIAL PRIMARY KEY,
    source_file_id       BIGINT NOT NULL,
    cost_head_id         BIGINT NOT NULL REFERENCES cost_head(cost_head_id),
    adjusts_contribution_id BIGINT REFERENCES cost_head_contribution(cost_head_contribution_id),
    amount               NUMERIC NOT NULL,
    currency             TEXT NOT NULL,
    unit                 TEXT NOT NULL,
    reason               TEXT NOT NULL,
    actor                TEXT NOT NULL,
    status               TEXT NOT NULL,       -- Pending|Accepted|Rejected|Withdrawn
    created_at           TIMESTAMPTZ NOT NULL,
    decided_at           TIMESTAMPTZ
);

CREATE TABLE cost_head_mapping_decision (
    mapping_decision_id BIGSERIAL PRIMARY KEY,
    source_file_id      BIGINT NOT NULL,
    region_key          TEXT NOT NULL,
    cost_head_code      TEXT NOT NULL,
    decision            TEXT NOT NULL,        -- Accepted|Rejected|Insufficient evidence|Unable to validate
    actor               TEXT NOT NULL,
    reason              TEXT,
    decided_at          TIMESTAMPTZ NOT NULL,
    supersedes_id       BIGINT REFERENCES cost_head_mapping_decision(mapping_decision_id)
);

CREATE TABLE cost_head_total_decision (
    total_decision_id   BIGSERIAL PRIMARY KEY,
    source_file_id      BIGINT NOT NULL,
    cost_head_code      TEXT NOT NULL,
    candidate_fingerprint TEXT NOT NULL,
    decision            TEXT NOT NULL,        -- Accepted|Rejected|Insufficient evidence|Unable to validate
    actor               TEXT NOT NULL,
    reason              TEXT,
    decided_at          TIMESTAMPTZ NOT NULL,
    supersedes_id       BIGINT REFERENCES cost_head_total_decision(total_decision_id)
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
    subject_kind        TEXT,                 -- cell|region|mapping|candidate|duplicate|external_link
    subject_key         TEXT NOT NULL,
    reason              TEXT,
    confidence          REAL,
    payload             JSONB,
    status              TEXT DEFAULT 'Pending',
    carried_from_decision_id BIGINT
);
```

The SQL above is the semantic contract; ADR 0002 governs its SQLite representation. Calculated
`cost_head_mapping`, `cost_head_contribution`, and `cost_head_candidate` rows are immutable outputs
of one parse run. The queue is only that run's worklist. Durable analyst authority lives in the two
cost-head decision tables, the duplicate-decision table, and accepted manual contributions.

The downstream `trust_state` (`candidate|trusted|stale`) and `trust_source`
(`automatic|analyst`) are projections, not mutable candidate columns. They combine the immutable
candidate's automatic-gate verdict with durable total decisions. Accepting a total inserts a
decision; it never updates the calculated candidate that decision refers to.

One canonical `cost_head` exists only when a region is detected or accepted for that code, an
analyst adds one, or a future mandate template requires it. The remaining locked vocabulary is
reported as **not observed**, never silently materialized as missing cost heads.

Mapping acceptance carries forward when `(source_file_id, region_key, cost_head_code)` is unchanged.
Total acceptance carries forward only when the candidate fingerprint is unchanged. The fingerprint
covers the source file, cost-head code, sorted region keys and cell coordinates, contribution and
manual-contribution identities, leaf coverage, amounts, units, currencies, and bases. It excludes
parser version, `configHash`, confidence, and explanatory reasons: a technically different run may
reuse an acceptance only when its arithmetic is semantically identical.

Sprint 3b lands as a deliberately destructive V11 migration
([ADR 0007](../docs/adr/0007-reset-pre-live-workspaces-in-the-sprint-3b-migration.md)). V1-V10 remain immutable.
V11 resets all parser-owned operational data, retains migration bookkeeping, and builds the clean
target schema above. Empty workspaces upgrade automatically; a populated workspace requires an
explicit CLI opt-in that displays the exact database path and data-loss warning. The reset is one
transaction and must pass SQLite `foreign_key_check` before commit.

### 4.4 Reference graph (Sprint 2)

One row per **reference token** in a formula. Ranges are stored **unexpanded** and expanded in
memory during a run: `=SUM(D22:D28)` is one row, not seven. A reference to a blank coordinate
never materializes a `cell` row — `resolved_cell_id` simply stays NULL — so whole-column refs
(`A:A`) cannot fabricate a million coordinates. Range expansion is clamped to the worksheet's
real bbox (§10.5), never to Excel's sheet limits.

```sql
CREATE TABLE cell_reference (
    cell_reference_id   BIGSERIAL PRIMARY KEY,
    from_cell_id        BIGINT NOT NULL REFERENCES cell(cell_id),
    token_index         INT NOT NULL,         -- order within the formula, for skeleton synthesis
    raw_token           TEXT NOT NULL,        -- verbatim, e.g. "[15]Manpower!F35" (§10.9 provenance)
    ref_kind            TEXT NOT NULL,        -- 'local_cell' | 'local_range' | 'cross_sheet_cell'
                                              -- | 'cross_sheet_range' | 'external' | 'defined_name'
    target_sheet_name   TEXT,                 -- verbatim sheet name as written in the formula
    target_worksheet_id INT REFERENCES worksheet(worksheet_id),
    target_range        TEXT,                 -- 'D22:D28' or 'F35'
    resolved_cell_id    BIGINT REFERENCES cell(cell_id),   -- single-cell refs that hit an occupied cell
    external_link_id    INT REFERENCES external_link(external_link_id),

    abs_row             BOOLEAN,              -- '$' flags survive tokenization for §7.4 skeletons
    abs_col             BOOLEAN,
    row_offset          INT,                  -- relative to from_cell; makes skeletons position-insensitive
    col_offset          INT,
    is_whole_column     BOOLEAN DEFAULT false,
    is_whole_row        BOOLEAN DEFAULT false,

    unresolved_reason   TEXT                  -- NULL when resolved; else 'sheet_not_found'
                                              -- | 'external_unresolved' | 'defined_name_unresolved'
);

CREATE TABLE cell_error_root (
    cell_id             BIGINT NOT NULL REFERENCES cell(cell_id),
    error_root_cell_id  BIGINT NOT NULL REFERENCES cell(cell_id),
    PRIMARY KEY (cell_id, error_root_cell_id)
);
```

`cell_error_root` exists because a cell can descend from several error roots at once
(`P  L !L35` and `M35` both feed `B  S `), which a single `cell.error_root_cell_id` column
cannot express. The scalar column is retained for cheap display; the join table is the truth
and is what answers "which statements does `Manpower!A3` poison?".

Sheet-qualified references resolve by **verbatim** sheet-name match only. Normalized matching
stays diagnostic (§10.4): a workbook containing both `'P  L '` and `'P L '` must not have them
silently merged. Every miss records an `unresolved_reason` and a `review_queue` row.

Useful indexes:

```sql
CREATE INDEX idx_cell_ws_coord       ON cell(worksheet_id, coord);
CREATE INDEX idx_cell_region         ON cell(region_id);
CREATE INDEX idx_cell_numeric        ON cell(numeric_value) WHERE numeric_value IS NOT NULL;
CREATE INDEX idx_cell_text           ON cell(text_value) WHERE text_value IS NOT NULL;
CREATE INDEX idx_cell_error          ON cell(is_error, error_type);
CREATE INDEX idx_cellref_from        ON cell_reference(from_cell_id);
CREATE INDEX idx_cellref_resolved    ON cell_reference(resolved_cell_id) WHERE resolved_cell_id IS NOT NULL;
CREATE INDEX idx_cellref_unresolved  ON cell_reference(unresolved_reason) WHERE unresolved_reason IS NOT NULL;
CREATE INDEX idx_cellref_external    ON cell_reference(external_link_id) WHERE external_link_id IS NOT NULL;
CREATE INDEX idx_error_root          ON cell_error_root(error_root_cell_id);
CREATE INDEX idx_cell_circular       ON cell(circular_group_id) WHERE circular_group_id IS NOT NULL;
CREATE INDEX idx_region_ws           ON region(worksheet_id);
CREATE INDEX idx_region_cost_head    ON region(cost_head_code);
CREATE INDEX idx_cost_head_mandate   ON cost_head(mandate_id);
CREATE INDEX idx_mapping_region_key  ON cost_head_mapping(source_file_id, region_key);
CREATE INDEX idx_mapping_cost_head   ON cost_head_mapping(cost_head_id);
CREATE INDEX idx_candidate_cost_head ON cost_head_candidate(cost_head_id, automatic_trust_eligible);
CREATE INDEX idx_candidate_fingerprint ON cost_head_candidate(source_file_id, candidate_fingerprint);
CREATE INDEX idx_contribution_candidate ON cost_head_contribution(cost_head_candidate_id);
CREATE INDEX idx_contribution_cell   ON cost_head_contribution_cell(cell_id);
CREATE INDEX idx_duplicate_left      ON duplicate_proposal(left_region_id);
CREATE INDEX idx_duplicate_right     ON duplicate_proposal(right_region_id);
CREATE INDEX idx_duplicate_decision  ON duplicate_decision(source_file_id, left_region_key, right_region_key);
CREATE INDEX idx_mapping_decision    ON cost_head_mapping_decision(source_file_id, region_key, cost_head_code);
CREATE INDEX idx_total_decision      ON cost_head_total_decision(source_file_id, cost_head_code, candidate_fingerprint);
CREATE INDEX idx_prov_doc            ON provenance(document_id);
```

---

## 5. Format adapters

```java
interface SpreadsheetAdapter {
    List<SheetHandle> sheets();
    Iterable<CellIn> cells(SheetHandle sheet);
    List<Range> mergedRanges(SheetHandle sheet);
    List<ExternalLinkIn> externalLinks();
    Map<String, String> definedNames();
    WorkbookProps workbookProps();
}
```

Implementations:

- `XlsxAdapter` (POI XSSF) for `.xlsx/.xlsm`.
- `XlsAdapter` (POI HSSF) for legacy `.xls`; mark formulas unavailable when a record will not decode.
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
3. Dilate the mask by one row/column **only across label/formula-compatible neighbors**, then find connected components using **8-connectivity**.
4. A component may be a full statement, a vendor block, a side scratch island, or a repeated inline summary.

**Compatibility (Sprint 3a).** A one-cell gap is bridged only when the cells on both sides are
either (a) formula cells whose §7.4 skeletons have non-zero similarity, or (b) text labels in the
same column. This reuses the Sprint 2 skeleton rather than inventing a second notion of sameness,
and it is what keeps `depreciation`'s every-other-row spacers inside one component: the cells above
and below a spacer are the same formula family. Bare value-class agreement is *not* compatibility —
two unrelated numeric blocks separated by a blank row stay separate.

**Connectivity is 8-way** because financial blocks are routinely ragged at the top-left: a merged
title, an indented header row, then data. 4-connectivity splits those into a title fragment and a
body, and break scoring cannot repair it — scoring only ever splits, never joins.

**Hidden-ness never affects occupancy or connectivity, on either axis.** Only content does. A hidden
*empty* column still separates two blocks; a hidden *populated* column still joins them. This
applies symmetrically to `row_hidden` and `col_hidden` (`'P  L '` hidden column L, `SALESPROJECTION`
hidden rows 18–24). Hidden sheets are detected like any other sheet; §10.1's filtering is a
presentation concern, never a graph one.

**Banner split.** A merged title spanning several blocks (`A1:K1` above `A3:E20` and `G3:K20`) is
occupied by step 2 and contiguous by definition, so it 8-connects both blocks into one component
that §7.2's row-oriented scoring can never separate. After components form, therefore: if removing
rows that consist *solely* of a merged banner leaves ≥ 2 column-disjoint sub-components, split them
vertically and emit the banner as its own one-row region. This is a connectivity-derived split
proven by geometry, not a scored one — it does not introduce vertical break scoring (§7.2).

**Every component becomes a region, regardless of size.** A one-cell component (`SALESPROJECTION!E2`,
`depreciation!F2`) is a region typed `unknown` unless Sprint 3b finds affirmative semantic evidence.
There is no target unknown-reduction ratio (§12). Leaving small components
without a region would create a NULL `region_id` bucket that nothing accounts for — indistinguishable
from a detector bug — and would defeat the §12 region-coverage gate.

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

**Cuts are horizontal only.** Every signal above with real weight is intrinsically row-oriented —
there is no meaningful serial-pattern reset across columns. Vertical separation is handled by
connectivity, not scoring: `B  S !Q11:R18` is a separate component *because* O:P are empty (§7.1),
and the one geometric exception is the banner split. Symmetric column semantics would add tuning
surface that no observed case demands; if one appears, it is a Sprint 3b amendment.

**Two signals need data the parser did not previously capture** (both added in Sprint 3a):

- *Section title style* requires font/fill/border. No cell styling was captured before Sprint 3a;
  only the "merged title" quarter of the signal was available. `is_bold`, `has_fill`, `has_border`
  and `number_format` are read from POI's `CellStyle` during ingest.
- *Column schema change* nominally requires `schema_json`, which is Sprint 3b. Break scoring instead
  uses a **column value-type profile** — the fraction of each column that is numeric, text, formula
  or blank — and fires when profiles diverge materially across a candidate break. The signal's job
  is to notice that the columns *mean something different* below a row; semantic role names
  (`description`/`qty`/`rate`/`amount`) add nothing to that and belong with rollup.

**Weights and thresholds.** The weights above live in a versioned resource file
(`region-weights.json`) whose content hash feeds `configHash`; the break threshold and the
classification floors are `ParserConfig` fields. Both routes enter the parse-run identity, so two
runs with different tuning are never mistaken for the same parse. Weights are not code constants:
that would let a recompile change every region under an identical `configHash`.

### 7.3 Region classification

Signals, each with weight:

- Header tokens: `year`, `yr`, `fy`, `construction`, `particulars`, `sl.no`, `amount`, `rate`, `qty`.
- Column-A serial pattern: numeric, alpha-dot, alpha-dash, mixed, none.
- Statement shape: assets/liabilities, revenue/expense, inflow/outflow, debt months.
- Cost-head alias match against locked vocabulary.
- Vertical-form pattern: few columns, label/value pairs, continuation rows.
- Scratch pattern: unlabeled formula island, orphan constants, disabled `*0` rows.

Every region stores `detection_reasons` and `region_conf`. Anything below threshold goes to `review_queue`, not silent guessing.

**Scoring is a weight matrix, not a cascade.** Every `region_type` is scored independently against
the signal set, so all scores are comparable. A first-match-wins cascade cannot produce a runner-up,
and the confidence formula needs one.

**`region_conf` is a margin, not a magnitude:**

```text
region_conf = clamp((top_score - runner_up_score) / top_score, 0, 1)
```

with a floor applied when `top_score` is itself below a minimum evidence bar, routing the genuinely
unclassifiable to `unknown`. The question this number answers is *"should an analyst look at
this?"*, and the case needing an analyst is **ambiguity**, not weak evidence. A region scoring 9 on
`pnl` and 8 on `bs` is exactly the one to escalate; a normalized sum or a softmax would score it
high and wave it through.

**`detection_reasons` is structured, not prose.** Each entry is `{code, weight, params}` where
`code` is a stable enum (`SERIAL_RESET`, `SKELETON_DRIFT`, `MERGED_TITLE`, `TITLE_STYLE`,
`COLUMN_PROFILE_SHIFT`, …) and `params` carries only numbers and coordinates. A formatter renders
these for humans at read time; it lives outside the persistence layer, so the stored form stays
diffable and free of workbook text (§13, snapshot scrubbing rule).

**Cost-head alias matching in Sprint 3a is exact-match only.** The locked §11 vocabulary is used as
a classification signal so 3a can assign `region_type='cost_head'` and `region.cost_head_code`;
fuzzy proposals, canonical cost heads and rollup arrive in Sprint 3b. `region.cost_head_label` is
workbook-derived text and is excluded from the committed snapshot; `cost_head_code` is a vocabulary
constant and is included.

**Sprint 3b fuzzy matching is proposal-only.** Normalize Unicode, case, whitespace and punctuation
without destroying abbreviations or digits; combine Jaro-Winkler and token-set scores; persist the
top match, runner-up margin and structured reasons. A configurable floor suppresses obvious noise,
but no fuzzy score accepts a mapping in 3b. Exact, unambiguous aliases may map automatically.
Accepting a fuzzy mapping applies only to the same source file and `region_key`; its source label is
retained as labelled evidence, not silently promoted into the global vocabulary.

**`schema_json`, `inferred_unit` and `inferred_currency` arrive in Sprint 3b.** Column roles use the
locked set `serial|description|quantity|rate|amount|period|other`; ambiguous columns remain `other`,
and each role carries confidence and structured reasons. Unit/currency evidence precedence is:
explicit region or column label, unambiguous number format, then worksheet-level label. Conflicts,
or an inference based only on a file/sheet name, yield `unknown` and review. A region's unit is the
highest-blast-radius inference in the sprint — `lakh` vs `crore` is a 100× error in a number an
analyst acts on — so trust is evaluated next to the rollup that consumes it (§8).

### 7.4 2D formula-coherence scoring

Formula-family consistency is a **scored feature**, not a hard rule. The parser computes an abstract skeleton for every formula cell and compares it with its non-empty neighbours in both directions.

#### 7.4.1 Skeleton abstraction

> **Two-stage synthesis.** The `$H$` (header) token below requires knowing the region's
> assumption/header row — but regions do not exist until Sprint 3, while the skeleton is a pure
> function of the AST and is produced in Sprint 2. Sprint 2 therefore emits a **region-free
> skeleton** in which *every* absolute reference is `$ABS$`. Sprint 3 refines `$ABS$` → `$H$`
> once regions are known. This costs nothing in the §13 fixtures, which assert skeleton
> *sameness across a family*, not the token's name: `=$ABS$*R` matching `=$ABS$*R` proves the
> same thing `=$H$*R` would.

For each formula cell, produce `formula_skeleton` by replacing every cell reference with a relative role token:

```text
'P  L '!D22 = =$D$18*D10    ->  =$ABS$*R   (Sprint 3 refines to =$H$*R)
'P  L '!E22 = =$E$18*E10    ->  =$ABS$*R
'P  L '!D23 = =$D$18*D11    ->  =$ABS$*R   (vertical neighbour of D22)
'P  L '!E23 = =$E$18*E11    ->  =$ABS$*R
'P  L '!D29 = =SUM(D22:D28) ->  =SUM(RANGE_VERTICAL)   (total row)
```

Token rules:

- Absolute references like `$D$18` → `$ABS$` in Sprint 2; refined to `$H$` (header) in Sprint 3 when they point to the assumption/header row of the region.
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

The defining Sprint 3b outcome is a **review-gated, explainable cost-head total**. Do **not** sum all
numeric cells in a region: that double counts totals, subtotals, merged participants, repeated
blocks and period balances. A calculated amount is a **candidate total** until it passes every
automatic trust gate below or an analyst explicitly accepts its exact fingerprint.

### 8.1 Canonical cost heads and contributions

There is at most one canonical cost head per `(mandate_id, code)`, but it may have many source
regions. Every region- or cell-derived amount is a separate contribution with amount, basis, unit,
currency, confidence, reasons and cell provenance. A manual correction is also a contribution; it
never overwrites a calculated value and participates only after analyst acceptance.

For each contribution persist its **leaf coverage set**: every eligible source cell represented by
that amount. Coverage determines composition:

- Disjoint peer sets may be added.
- A strict superset supersedes its contained contributions.
- Identical sets are duplicates.
- Partial overlap blocks automatic trust.

Formula-graph evidence may establish containment; bbox overlap may not, because region bboxes can
overlap (§4.3). Period values remain periodized contributions. A scalar cost-head total comes only
from an overall-total field or proof that the periods partition the total; balances, alternatives
and repeated projections are never blindly added.

### 8.2 Contribution basis precedence

1. **Explicit total anchor.** A text label matches `total|grand total|total project cost|amount`,
   aligns with the inferred `amount` column, and has a formula dependency connection to the region's
   line items. Example target style: `CAPITAL COST!D28`, `Details!F222`, `Details!G130`. A labelled
   literal with only geometric evidence remains a candidate for review. If several plausible anchors
   disagree, persist every candidate and queue review.
2. **Structural total.** An unlabeled formula may qualify when it is `SUM` over one or more
   same-region amount ranges and its dependency set exactly equals the eligible leaf set: every
   leaf appears once; no subtotal, external, cross-region, error, scratch, duplicate-ambiguous or
   wrong-period input participates; unit and currency are known; cache is fresh; and the cached
   amount agrees with the independent leaf sum. Direct arithmetic, `SUBTOTAL`, `ROUND`, cross-region
   references and other functions remain review-only in Sprint 3b.
3. **Leaf-sum fallback.** When neither anchor exists, calculate a candidate from eligible leaves,
   but never trust it automatically in Sprint 3b. An eligible leaf is an `amount`-role cell that
   does **not itself aggregate other included monetary cells**, is not a merged participant, is not
   an error or error descendant, and is not disqualified by scratch, duplicate, period, unit or
   currency evidence. A leaf remains eligible when a total formula references it. This corrects the
   former backwards rule that excluded cells merely because they were addends of a total.
4. **Manual contribution.** An analyst supplies amount, unit, currency, reason, actor and what the
   contribution supplements or corrects. Acceptance, change or withdrawal creates a new candidate
   fingerprint and invalidates any prior total acceptance.

If a labelled explicit anchor also satisfies the structural-total test, emit one contribution with
`basis='explicit_total_anchor'` and record structural agreement as supporting evidence. If the two
calculations disagree, retain both candidates and queue review.

Numeric comparison has no global monetary tolerance. Sum stored full-precision decimals and compare
the anchor and independent leaf sum after rounding both to the source number-format precision. With
no explicit display precision, require exact decimal equality. A stale or missing formula cache
prevents automatic trust.

### 8.3 Units and currency

Preserve every contribution's native amount, unit and currency. High-confidence INR amounts in
`rs|lakh|crore` may be normalized to rupees for composition while retaining the source amount.
Unknown units, unknown currencies and foreign-currency conversion block trust; the parser never
invents an exchange rate. Conflicting evidence also yields `unknown` and review.

### 8.4 Automatic trust gate

A candidate becomes `trusted` with `trust_source='automatic'` only when all are true:

- Every region mapping is exact and unambiguous, or already accepted for this source file and
  `region_key`.
- Every contribution basis is a verified explicit total anchor or structural total.
- Unit and currency are known and composition-compatible.
- Leaf coverage relationships are resolved.
- Cache and number-format-aware numeric agreement checks pass.
- No error, scratch, unresolved duplicate, period or pending-manual-contribution blocker remains.

Persist structured evidence for every gate. An analyst may accept a candidate that remains pending,
but the acceptance is tied to its fingerprint. Mapping acceptance is independent: a region may stay
correctly mapped while a changed amount, basis, unit, currency or contribution set reopens the total.

Every included source cell is linked through `cost_head_contribution_cell`. Every plausible monetary
cell excluded from a calculation is also linked with a stable reason such as `SUBTOTAL`, `ERROR`,
`SCRATCH`, `DUPLICATE_AMBIGUITY` or `WRONG_PERIOD`. An opaque arithmetic JSON summary is not enough
provenance for a number a human acts on.

### 8.5 Review lifecycle

Mapping review and total review are independent. Mapping decisions use the existing
`Pending|Accepted|Rejected|Insufficient evidence|Unable to validate` vocabulary and are keyed by
source file + region key + cost-head code. Total decisions use the same review vocabulary but are
keyed by source file + cost-head code + candidate fingerprint. Candidate trust is a separate
`candidate|trusted|stale` state with `automatic|analyst` trust source; a blocker is a structured
reason on a candidate, not another overloaded status.

On a new parse of the same source file, carry accepted mappings forward when the `region_key` is
unchanged and total acceptance when the fingerprint is byte-for-byte identical. Link the current
queue item to the prior durable decision. If source bytes change, or if amount, basis, unit,
currency, leaf coverage or contributions change, reopen the affected decision as `Pending`.

Sprint 3b includes a minimal Picocli workflow to list and inspect proposals; accept/reject mappings
and totals; mark duplicates `Duplicate|Distinct`; and add, accept or withdraw manual contributions.
Every mutation records actor, timestamp and the relevant rationale. Sprint 4 may replace this with a
richer UI feed, but 3b is not complete with persistence that no analyst can operate.

---

## 9. Formula evaluation and cached-value policy

Primary source of truth is Excel’s cached value. Evaluation is a fallback, not a default.

Rules:

- If cached value exists and workbook calc mode is automatic: `cache_state='fresh'`.
- If cached value exists but file shows manual calc or stale calc chain: `cache_state='stale'`; keep value but lower confidence.
- If formula has no cached value: `cache_state='missing'`; do not invent a number unless evaluator is enabled and formula is whitelisted.
- **Evaluation scope (Sprint 2): constant-formulas only.** A constant-formula is one whose entire
  token stream is numeric literals plus `+ - * / ( )` and unary minus — **no references and no
  function calls at all**. `=200/2` and `=33000` qualify; `=SUM(F233:F233)` (the §10.6 scratch case)
  does not. This is decidable straight off the token stream, with no evaluator semantics, no locale,
  and no error cases, and it is the only evaluation the semantics layer actually needs (§10.9).
- Evaluating a constant-formula sets `numeric_value` and **leaves `cache_state` exactly as read from
  the file**. Evaluation never rewrites cache provenance.
- POI ships a `FormulaEvaluator`, but it evaluates everything rather than a whitelist and will
  invent numbers for `cache_state='missing'` cells — precisely what this section forbids. It is not
  used. A wider whitelist (`SUM`, `ROUND`, `MIN/MAX`, `IF` on literals/simple refs) is deferred to
  Sprint 4 hardening; if it lands it sits behind an adapter, excludes external links, volatile
  functions, circular refs, and UDFs/macros, and any failure becomes `formula_state='parse_error'`
  while keeping cached/error state.

Error literal handling: `#REF!` inside formula text is an AST error node and sets `is_error=true` even if cached value is missing.

### 9.1 Circular references

Excel permits circular references when iterative calculation is enabled, so a cycle is not by
itself a defect. The parser detects strongly-connected components over the reference graph, marks
every member `is_circular=true` with a shared `circular_group_id`, keeps cached values, and emits
`review_queue` rows. Severity depends on `workbook.iterative_calc`: a cycle in a workbook that
deliberately iterates is **info**; a cycle in one that does not is a **warning**. A cycle never
fails the run — §12 allows partial success provided every failure is represented in the queue.
The evaluator never enters a cycle.

---

## 10. Findings-to-fix mapping

### 10.1 Hidden vs visible sheets

- Load `visible`, `hidden`, and `veryHidden`.
- `worksheet.role` is scored from dependency- and content-weighted evidence, not region counts:
  `primary` contains cost-head contributions or major financial statements; `support` materially
  feeds primary sheets; `scratch` contains only scratch/orphan material and does not feed
  primary/support; conflicting evidence stays `unknown`. Persist confidence and structured reasons.
- Hidden support sheets (`Pages`, `BS_ANLYSIS`, `details of fixed assets  `) remain in graph.
- Worksheet role and visibility organize presentation only. Neither independently includes or
  excludes a cost-head contribution; contribution-level evidence remains authoritative.

### 10.2 External workbook links

- Parse `xl/externalLinks` and rels to resolve `[15]` to target URI/display name.
- Store both verbatim ref (`cell_reference.raw_token`) and resolved link (`cell_reference.external_link_id`), one row per ref — a formula may carry several.
- `raw_token` is the reference's **span in the formula as written**, never a re-rendering of the
  parsed token. Excel quotes a sheet name only where the name requires it (`'P  L '!D29` but
  `SALESPROJECTION!E81`) and writes each endpoint's `$` markers independently, so a re-rendered
  token need not occur in the formula it came from. §7.4 depends on this: the skeleton generator
  abstracts a reference by replacing its raw span, and a span that is not literally present leaves
  the reference un-abstracted and the skeleton position-sensitive. `target_range` is the
  normalized counterpart (`E81:E90`, whole columns expanded to their bounds) and stays the
  resolution lookup key.
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

Keep and tag; never delete. Scratch candidates are detected at cell level:

- Unlabeled formula islands (`Interest!F176:G185`, `P  L !D162:H170`).
- Orphan page-number constants (`SALESPROJECTION!E2=39`, `depreciation!F2=42`, `power cost!F3="34"`).
- Disabled lines (`ASSETS!F35 = =12117678.83*0`).
- Single-cell sums of constants (`Details!F234 = =SUM(F233:F233)`).
- Side analyses (`B  S !Q11:R18`) are scratch only if no live dependents.

Then traverse **dependents** to a fixed point. Any candidate that feeds a non-scratch cell becomes
`support`; a referenced isolated constant is support, not orphan. Mark an entire region `scratch`
only when all meaningful cells remain scratch. A mixed region keeps its ordinary region type while
its scratch cells retain cell-level flags and reasons.

An orphan has no meaningful label or region role and has neither precedents nor dependents. Scratch,
support and orphan decisions are structured, reversible evidence. They may exclude a cell from an
automatic candidate calculation but never remove it from the cell graph or contribution history.

### 10.7 Gap pathology

- Blank rows/cols are weak signals only.
- Hidden rows/cols are retained and tagged.
- Region boundaries come from component + coherence scoring (§7).
- Known cases covered: `depreciation` spacer rows, `SALESPROJECTION` hidden rows 18–24, `P  L ` hidden column L, `B  S ` empty O:P before live Q:R.

### 10.8 Errors and cascades

- Exact error enum includes `#N/A`.
- Error cells remain graph nodes.
- Build descendants from parsed formula graph, including ranges and cross-sheet refs.
- `error_descendant=true` marks cells whose chain passes through an error root. All roots for a cell are stored in `cell_error_root` (§4.4), since a cell can descend from several at once.
- Example expected: `P  L !B35 = =Manpower!A3` returns text; `L35/M35` become `#VALUE!`; downstream M/N columns in `B  S ` and `CASH FLOW` inherit `error_descendant`.

**Error barriers.** Propagation is structural but not blind: some functions consume errors rather
than propagating them, and `=IFERROR(L35,0)` returns `0` — it is not poisoned. Propagation stops at
the error-consuming arguments of `IFERROR`, `IFNA`, `ISERROR`, `ISNA`, `ISERR`, `COUNT`, `COUNTA`,
`AGGREGATE`, and `SUBTOTAL`. Barrier detection is a function-token check on the parsed stream, so it
stays deterministic. Without barriers, a single `#N/A` in a model that wraps its lookups in
`IFERROR` reports most of the workbook as poisoned — a report analysts learn to ignore.

**Cached-value cross-check.** A cell's own cached value is *evidence*, not truth (§9): a stale or
missing cache would silently clear real poison, so it never overrides the graph. Where the barrier
verdict and the cached value disagree, the parser records a metric and a `review_queue` row — the
usual cause is a stale cache.

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
- Column roles use the locked enum `serial|description|quantity|rate|amount|period|other` and beat
  column-letter assumptions. Every assignment carries confidence and reasons; ambiguity is `other`.
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
- Number format and inferred unit (`rs|lakh|crore|unknown`) stored. Evidence precedence is explicit
  region/column label, unambiguous number format, then worksheet-level label. Conflicts or a
  file/sheet-name-only guess yield `unknown`.
- Preserve the source amount/unit/currency. High-confidence INR amounts may additionally normalize
  `lakh|crore` to rupees for rollup; foreign exchange conversion is outside parser scope.
- Text-numbers coerced with `coerced_from_text`.
- Quantity text parsed to JSON without destroying raw text.
- Dates stored as dates plus raw serial.

### 10.13 Merged cells

- Anchor owns value.
- Participants get `merged_range`, `value_source='merged_anchor'`, and `display_value`, but **not** `numeric_value`/`text_value` used for aggregation.
- Aggregation queries must filter `value_source='cell'` unless explicitly doing display rendering.

### 10.14 Duplicate data

- Compare only regions/rows with compatible schemas, units, currencies and cost-head mappings.
- Exact normalized contents plus formula skeletons produce `exact_row_hash` proposals.
- Fuzzy block signatures produce shifted-copy proposals (`ASSETS` vs `Details`).
- Persist duplicate pairs/groups, method, score and structured reasons. Never delete or silently
  canonicalize data. An analyst marks a proposal `Duplicate` or `Distinct` and may designate the
  superseded contribution.
- Only an unresolved proposal intersecting the same candidate total blocks that candidate's
  automatic trust. Accepted `Distinct` evidence prevents the same pair from repeatedly blocking
  an unchanged source file. The decision is keyed by source file and the ordered pair of stable
  region keys; changed source bytes reopen it.

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

Ambiguous aliases (`equipment`, `miscellaneous`) require review even when text-normalized equality
holds. Exact unambiguous aliases may create mappings automatically. All fuzzy matches are proposals
in Sprint 3b: normalize Unicode, case, whitespace and punctuation while preserving abbreviations and
digits; rank with Jaro-Winkler plus token-set similarity; persist top score and runner-up margin; and
use a configurable floor only to suppress obvious noise. Tests assert deterministic ranking and
relative ordering, not that an arbitrary score proves correctness.

Accepting a fuzzy proposal affects only its `(source_file_id, region_key)`. The label is accumulated
as accept/reject evidence for Sprint 4 evaluation, but becomes a global alias only through an
explicit vocabulary/configuration change. Sentence embeddings remain out of Sprint 3b: the domain is
a 17-entry abbreviation-heavy dictionary with no labelled threshold data, not prose similarity.

---

## 12. Confidence, review, and QA gates

Every heuristic output carries confidence and reasons. Minimum gates before `parse_run.status='success'`:

Gates test **accounting, not perfection**. An explicitly-reasoned failure — a `parse_error`, an
unresolved ref, a detected cycle — does not flip the run status, because it is already represented
in `review_queue`. Only an *unaccounted* shortfall does. Requiring zero parse errors would mark
every real client workbook `partial` forever (they reliably contain at least one dead external
link), which would make the status field meaningless.

- 100% of occupied cells ingested or explicitly rejected with reason.
- Formula reconciliation: `tokenized + parse_error + unavailable = total formula cells`.
  `unavailable` is its own bucket, not a parse error — the file withheld the formula, the parser did
  not fail on it (§2, `.xls`) — and those cells are queued for review.
- Reference reconciliation: every reference token is either resolved or carries an
  `unresolved_reason` and a `review_queue` row. This supersedes the v1 external-ref gate, which was
  unsatisfiable against a scalar column.
- Cycle reconciliation: every detected SCC is queued, with severity set by `workbook.iterative_calc`.
- 0 unexplained losses between adapter cell count and DB cell count.
- **Region coverage (Sprint 3a)**: every occupied cell belongs to exactly one region; no NULL
  `region_id`. This is the checkable form of §7.1's "every component becomes a region".
- **Classification accounting (Sprint 3a)**: every region is either classified above the confidence
  floor or carries a `review_queue` row. An `unknown` region is acceptable; a *silent* `unknown` is
  not. There is deliberately **no** ratio gate on `unknown` or scratch regions — a workbook that is
  genuinely 60% scratch has been parsed correctly, and failing it would test perfection rather than
  accounting. Ratios belong in the reports below, which a human reads.
- **Semantic mapping reconciliation (Sprint 3b)**: every detected cost-head region has an exact or
  accepted mapping, a fuzzy proposal, or a review item. There is no target percentage for reducing
  `unknown` regions; only silent semantics fail the gate.
- **Contribution reconciliation (Sprint 3b)**: every contribution has a basis, unit/currency
  outcome, structured reasons, and complete included/excluded cell evidence. Its leaf coverage set
  must reconcile with the recorded arithmetic.
- **Candidate reconciliation (Sprint 3b)**: every canonical cost head with source evidence has a
  candidate or an explicit blocker. Every candidate is `candidate|trusted|stale`; every trusted
  candidate records `automatic|analyst` trust source and passes the applicable fingerprint/decision
  checks. An automatically trusted value with a failed §8.4 gate, or an analyst-trusted value without
  an exact accepted fingerprint decision, is a QA failure rather than a review item.
- **Decision reconciliation (Sprint 3b)**: mapping decisions carry forward only for the same source
  file and region key; total decisions only for the same candidate fingerprint. Reused decisions
  are linked in the current queue; incompatible ones reopen as `Pending`.
- **Duplicate/scratch accounting (Sprint 3b)**: every proposed duplicate and scratch/orphan/support
  classification has stable reasons and a review path where required. Nothing is deleted.
- Cost-head coverage report: observed/not-observed vocabulary, exact/accepted/pending mappings,
  candidate/trusted/stale totals, contribution bases, blockers, and unit/currency unknowns.
- Error-cascade report: roots, descendants, affected statements.
- Scratch report: scratch, support and orphan cells plus dependency promotions.
- Duplicate report: proposed/duplicate/distinct groups and affected candidate totals.
- Worksheet-role report: roles, confidences and reasons without using role as an arithmetic filter.

Pending semantic work does not by itself fail a parse. Partial success is allowed only if every
recoverable failure is represented in `review_queue`; unaccounted artifacts, reconciliation breaks,
or an incorrectly trusted total fail the relevant QA gate.

---

## 13. Test fixtures

**Fixture policy.** Assertions run against the real reference workbook by default; synthetic
fixtures exist only for behaviour the real workbook cannot exercise. The reference workbook is never
committed (`fixtures/private/` is gitignored), so real-workbook tests skip on a fresh clone and
assert only structural facts, never financial figures. Synthetic fixtures are built in-test with
POI; no binaries are committed.

A probe of the reference workbook (47 sheets, 5,841 formulas) fixes which side each behaviour falls
on. It contains 25 multi-external-ref formulas, 68 constant-formulas, and 613 double-space string
literals — all assertable directly. It contains **zero** uncached formulas and **zero**
error-barrier functions, so those behaviours have no instance to test against and require
synthetics:

| Synthetic fixture | Covers |
|---|---|
| uncached formula | `cache_state='missing'`, no invented `numeric_value` (§9) |
| `IFERROR`/`ISNA`/`COUNT` over an error | barrier stops the cascade; barrier-vs-cache disagreement queues (§10.8) |
| circular ref, with and without `iterate` | SCC detection and the info/warning severity split (§9.1) |
| `#REF!` to a deleted sheet | `unresolved_reason='sheet_not_found'` (§4.4) |
| formula the parser cannot parse | `formula_state='parse_error'` plus reference salvage (§14) |
| whole-column ref (`=SUM(A:A)`) | range expansion clamps to the real bbox instead of Excel's limits (§4.4) |
| labelled total aligned to amount column | one `explicit_total_anchor` contribution with linked leaves |
| unlabeled exact `SUM` | `structural_total` only when dependency set equals eligible leaves and cache agrees |
| unlabeled `SUM` skips/duplicates a row | candidate stays pending with coverage reason |
| subtotal plus leaves | leaf fallback includes leaves and excludes the subtotal, not the subtotal's addends |
| partially overlapping contributions | automatic trust blocked; disjoint/superset cases compose deterministically |
| period balances vs partitioned periods | balances are not summed; proven partitions may roll up |
| lakh/crore conflict | unit becomes `unknown`; candidate cannot become trusted |
| exact vs fuzzy cost-head aliases | exact unambiguous maps; every fuzzy result queues with score + margin |
| scratch cell with live dependent | fixed-point promotion to `support` |
| exact and shifted duplicate blocks | proposal retained; only same-candidate ambiguity blocks trust |
| decision carry-forward | mapping uses source + region key; total uses exact candidate fingerprint |
| destructive populated V10 upgrade | rejected without opt-in; opted-in V11 resets atomically and passes FK check |

**Golden region snapshot (Sprint 3a).** Sprint 1 and 2 assertions were exact (`raw_token =
'[15]Manpower!F35'`). Sprint 3 assertions are judgements — "`depreciation` rows 10–55 form one
region" — and every tuning change moves dozens at once. Hand-written assertions on the §13 named
cases catch those cases and nothing else, which means the break weights could be retuned and a
regression on the other 46 sheets would go unnoticed until a wrong total surfaced months later. So
the region output is additionally captured as a **committed golden snapshot**, diffed on every run:

- One JSON file per sheet under `fixtures/snapshots/`, named by a *slug* of the sheet name — the
  real names carry double and trailing spaces and are unsafe as filenames. Regions sorted by
  `(start_row, start_col)`.
- Each entry carries: sheet slug, bbox, `region_type`, `region_conf`, `serial_pattern`,
  `region_key`, `cost_head_code`, and `detection_reasons` as codes plus numeric params.
- **Scrubbing rule: no workbook-derived free text.** No labels, no header token strings, no
  `cost_head_label`, no values. Sheet names already appear in this document; bounding boxes,
  confidences and reason codes are pure structure.
- Regeneration is opt-in only (`-Dsnapshot.update=true`). A test run can never silently bless a
  regression.

**Known limitation.** The reference workbook is gitignored, so the snapshot test skips wherever the
workbook is absent — including CI. The regression net therefore fires only on machines that hold the
workbook, and the control on a rubber-stamped re-baseline is reviewer discipline plus the per-sheet
file layout keeping diffs small enough to read. This is the weakest link in the Sprint 3 test story,
and it is weak because the workbook cannot be committed. A pseudonymised structural twin was
considered and rejected: formulas, merges, hidden flags and geometry survive pseudonymisation, but
region *classification* keys on label text, so a twin would validate roughly half of Sprint 3a and
diverge silently on the rest — a half-valid fixture that looks fully valid is a worse trap than an
honest skip.

**Golden semantic snapshot (Sprint 3b).** Keep semantic output in a second committed snapshot rather
than mixing it into the region baseline
([ADR 0005](../docs/adr/0005-commit-a-separate-scrubbed-semantic-snapshot.md)). It carries column-role codes, inferred
unit/currency codes, worksheet roles, canonical cost-head codes, contribution bases, trust states,
and structured reason codes. It carries **no amounts, labels, raw values or value-derived candidate
fingerprints**. Regeneration is opt-in and the same local-only/private-workbook limitation applies.
The synthetic fixtures above remain the CI proof for every trust branch; the semantic snapshot is
the broad 47-sheet regression diff.

Keep v1 fixtures and add the gap-regression tests:

| Test | Source | Assertion |
|---|---|---|
| blank rows do not split | `depreciation` rows 10–55 | one coherent region unless score threshold crossed |
| side-by-side component | `B  S !Q11:R18` vs main BS | separate region bbox, not same row-span |
| merged no double count | `B  S !C5:K5` | SUM over participants equals anchor value once |
| `#N/A` exact enum | synthetic | `error_type='#N/A'`, not text |
| bool before int | synthetic | `TRUE` -> `bool_value=true`, not `numeric_value=1` |
| external index resolved | `CAPITAL COST!I19` | `cell_reference.external_link_id` points to parsed link, `raw_token='[15]Manpower!F35'` |
| multi-external-ref formula | `'[17]BS&PL'!G23+'[17]BS&PL'!G45` | both refs stored as separate `cell_reference` rows; neither is dropped |
| missing cache marked | synthetic uncached formula | `cache_state='missing'`, no invented numeric |
| normalization preserves strings | synthetic `="A  B"` | string literal spaces unchanged |
| `.xls` adapter | legacy fixture | formulas unavailable -> `formula_state='unavailable'` |
| `.csv` adapter | dialect/encoding fixture | delimiter/encoding recorded; no formula columns required |
| cost-head no double count | `Details!F222/F225` | explicit anchor linked to eligible leaves; subtotal/total formulas excluded without excluding their addends |
| error cascade | `P  L !B35:L35` -> `B  S !M16` | descendant flags set |
| scratch with dependents kept | `B  S !Q11:R18` | not scratch if referenced |
| period axis structured | `B  S `, `depreciation`, `power cost` | maps stored despite header spelling differences |
| horizontal formula family | `P  L !D22:M22` | `D22:M22` share one skeleton — `=$ABS$*R` in Sprint 2, `=$H$*R` after Sprint 3 refinement (allow single-cell `$` drift) |
| vertical formula family | `P  L !D22:D28` | `D22:D28` share one skeleton (except total row) |
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

Sprint 2 is the **deterministic** formula layer: everything it produces is a pure function of the
parsed formula, and every heuristic stays in Sprint 3 (§3).

**Parsing.** POI's `FormulaParser` is the primary tokenizer — its grammar already covers the §10.9
list (quoted sheet names, ranges, `%`, `^`, `[n]` external refs, defined names, error literals) and
is battle-tested against exactly the dirty sheet names in the findings doc. On
`FormulaParseException` a **reference-salvage fallback** runs: it scans the formula text for
reference tokens and records them as unresolved `cell_reference` rows, marks
`formula_state='parse_error'`, and produces no AST, no skeleton, and no constant-evaluation. This
preserves the one thing a damaged formula still tells us — what it points at — without maintaining a
second grammar.

**Graph construction** runs inside the same parse run and the same transaction as ingest. There is
no state in which a document is "ingested but ungraphed": §15's idempotency key is one file, one
run, one verdict. Cell ids are already in hand from the insert batch, so the second pass costs a
map, not a re-read.

| Ticket | Delivers |
|---|---|
| 14 | V8 migration: `cell_reference`, `cell_error_root`, cell graph columns, workbook calc columns, and removal of the scalar `cell.external_ref` — one `cell` table rebuild carrying every Sprint 2 column |
| 15 | Tokenizer seam: POI `FormulaParser` primary, reference-salvage fallback, `formula_state='parse_error'` |
| 16 | Reference resolution: verbatim sheet matching, the three `unresolved_reason` values, external-link binding; retires the regex extractor from the main path |
| 17 | Precedent/dependent graph, bbox-clamped range expansion, SCC cycle detection, workbook calc metadata persisted |
| 18 | Error cascade: barrier list, `cell_error_root`, cached-value cross-check metric |
| 19 | Constant-formula evaluation (arithmetic literals only; cache provenance untouched) |
| 20 | `formula_skeleton` in region-free `$ABS$` form |
| 21 | QA gate record refactor, Sprint 2 metrics, real-workbook integration-test extension |

Ticket 14 unblocks the rest; the remainder run in order. Later tickets add schema only if they
discover they need it, as corrective migrations.

### Sprint 3 — semantics, split into 3a and 3b

Sprint 3 is where the parser stops being a pure function of the file. Everything Sprints 1 and 2
produced was reproducible from the bytes alone; regions, classification and rollup are heuristics
with thresholds and no oracle. Two consequences shape the split.

**Why 3a and 3b are separate sprints.** Cost-head rollup is the first thing this project produces
that is *a number a human acts on*, and its correctness is entirely downstream of region boundaries
being right. Planning it now means writing tickets against an input that does not exist yet. 3a
delivers regions that can be looked at; 3b's tickets get written against real observed output rather
than this document's idealisation.

**How heuristic output is held still.** All tuning enters `configHash` — thresholds as
`ParserConfig` fields, weights as a hashed resource file (§7.2) — so a re-tuned run is never
mistaken for the same parse. Regions carry a `region_key` stable across re-parses (§4.3). And the
full region output is captured as a committed, scrubbed golden snapshot (§13), because the ten
hand-written cases below cover ten cases and the workbook has 47 sheets.

#### Sprint 3a — regions

| Ticket | Delivers |
|---|---|
| 22 | CI: GitHub Actions, JDK 25, `mvn -B verify` on push/PR to `main`, branch protection |
| 23 | V10 migration: `region` table, `cell.region_id` FK, `formula_skeleton_regional`, style + `number_format` columns, `worksheet.role`/`role_conf` — one `cell` rebuild carrying every 3a column |
| 24 | Style capture: XLSX adapter populates bold/fill/border/number-format; `.xls` and `.csv` degrade explicitly |
| 25 | Components: occupancy mask, 8-connectivity, skeleton-compatible dilation, banner split, `region_key` (§7.1) |
| 26 | Break scoring: §7.2 signals including the column value-type-profile proxy, weights resource feeding `configHash` |
| 27 | Classification: weight matrix, margin confidence, exact cost-head alias signal, `detection_reasons` codes + formatter (§7.3) |
| 28 | Period axis: `period_axis` extraction; `CellContextEnricher` repopulated from region headers, `^Year\d+$` regex deleted (§10.11) |
| 29 | Snapshot harness: per-sheet golden files, canonical ordering, `-Dsnapshot.update=true`, scrubbing rule (§13) |
| 30 | Gates and metrics: region-coverage and classification-accounting gates, `review_queue` rows, 3a metrics, `RealWorkbookIT` extension (§12) |

Ticket 22 comes first and depends on nothing: every ticket after it lands on a branch whose
green-ness is visible, which is the exact failure #31 recorded. Ticket 23 unblocks the rest.

Ticket 29 sits after 27, not before 25. A baseline taken before classification exists would be a
file of `unknown` regions that ticket 27 wholly rewrites — worthless as a baseline and misleading as
a diff. The cost is that 25 and 26 ship covered only by synthetic fixtures and the §13 named cases.

`CellContextEnricher`'s per-cell `row_label`/`col_label` **survive** as a denormalized query path
("PBIT for Year 5" without a join), but are repopulated from region headers in ticket 28 and the
`^Year\s*\d+$` regex is deleted. Two disagreeing header inferences in one database is how you get a
bug nobody can localise.

#### Sprint 3b — semantics over regions

Designed after inspecting 3a's real output. The committed snapshot contains 1,026 regions across 47
sheets: 864 are explicitly `unknown`, 191 are one-cell components, and only 26 are exact cost-head
matches (including 14 `CIVIL` and four `PLANT_MACHINERY`). That observation fixes two requirements:
there is no arbitrary unknown-reduction target, and one canonical cost head must accept multiple
source contributions rather than owning one `fm_region_id`.

Sprint 3a output is stable but not untouchable. Sprint 3b may correct a region boundary or header
inference only when a concrete 3b schema/rollup invariant exposes the defect. Every correction needs
a focused regression test and a reviewed golden-region-snapshot diff; semantic work is not a licence
for broad untargeted retuning.

The defining outcome is a **review-gated, explainable trusted cost-head total**. Schema inference,
unit/currency inference, scratch/support/orphan classification, duplicates and worksheet roles exist
to qualify, protect or explain that number. They are not independent coverage contests.

Work proceeds in this dependency order; `/to-spec` and `/to-tickets` turn it into blocking issues:

1. **V11 clean target schema and reset guard.** Keep V1-V10 immutable. On populated workspaces require
   explicit CLI opt-in, then transactionally reset all parser-owned data and build the §4.3 model.
2. **Region schema semantics.** Infer locked column roles, unit and currency with confidence/reasons;
   preserve native amounts and normalize only high-confidence INR `lakh|crore` to rupees.
3. **Protective classifiers.** Compute scratch candidates then fixed-point support promotion,
   orphans, duplicate proposals and dependency/content-weighted worksheet roles.
4. **Canonical mappings.** Create canonical heads only when observed/added/required; exact aliases
   map automatically, while deterministic Jaro-Winkler + token-set fuzzy results remain review
   proposals and labelled evidence.
5. **Contribution and candidate engine.** Detect explicit anchors and `SUM`-only structural totals,
   build eligible-leaf coverage, compose disjoint/superset contributions, persist inclusion/exclusion
   provenance, calculate fingerprints and enforce every automatic trust gate (§8).
6. **Durable decisions and manual contributions.** Carry mapping acceptance by source + region key,
   total acceptance by exact fingerprint, and keep manual adjustments provenance-bearing and
   reversible
   ([ADR 0006](../docs/adr/0006-separate-calculated-cost-head-artifacts-from-analyst-decisions.md)).
7. **Minimal Picocli analyst workflow.** List/show proposals; accept/reject mappings and totals; mark
   duplicates `Duplicate|Distinct`; add/accept/withdraw manual contributions. Sprint 4 still owns the
   richer UI feed.
8. **Accounting, reports and regression controls.** Add §12 metrics/gates, the scrubbed semantic
   snapshot (ADR 0005), synthetic CI coverage for every trust branch, and real-workbook structural
   assertions without financial values.

Fuzzy matching remains offline string similarity, **not embeddings**. `P&M`, `DG set`, `ETP` and
`civil & structural` are abbreviation/punctuation cases over a 17-entry hand-curated dictionary;
sentence embeddings add a pinned binary dependency before labelled evidence exists. Sprint 3b's
accept/reject decisions create that labelled set for Sprint 4 evaluation.

### Sprint 4 — hardening

- `.xls` adapter completion, CSV encoding suite, performance pass.
- Rich parse-report/review-queue UI feed and broader audit/event integration; Sprint 3b already ships
  the minimal CLI needed to operate its review-gated result.
- #32 — cross-sheet `#REF!` skeletons retain the sheet name (`'CAPITAL COST'!#REF!`), 10 cells of
  the 801 `#REF!` cells in the reference workbook. Deferred here deliberately: §7.4.2 compares
  same-sheet neighbours only and the retained name is constant within each affected band, so the
  corruption never enters a comparison; §7.4.4's drift signal needs ≥ 3 consecutive rows or columns
  and the affected cells are one uniform six-cell band plus four isolated cells. Once region
  detection exists, whether these cells land on a boundary becomes *measurable* rather than assumed
   — which beats tokenizer surgery on POI's handling of deleted references to buy something not yet
  observable. The condition to watch for: ≥ 3 consecutive rows or columns mixing sheet-qualified
  `#REF!` against plain `#REF!`, where the skeletons genuinely differ and a false boundary appears.
- Evaluate embedding-based cost-head matching against the labelled set 3b's review queue produced.
- Full OM Arham Ventures integration test and fixture freeze.

---

## 15. Definition of done

The parser is done when, on `OM Arham Ventures.xlsx` and the synthetic fixtures:

1. Every occupied, hidden, merged, errored, external, and formula cell is represented once and only once.
2. No region boundary depends solely on blank rows or columns.
3. Every inferred column role, unit, currency, scratch/support/orphan state, duplicate proposal and
   worksheet role carries confidence, structured reasons and an accounting/review path.
4. Every observed cost-head region has an exact/accepted mapping, fuzzy proposal or review item;
   no target classification ratio is imposed on genuinely unknown regions.
5. Every cost-head contribution identifies its basis, native amount/unit/currency, eligible-leaf
   coverage, and every included or plausibly excluded source cell.
6. No aggregation sums merged participants, subtotals and their leaves, duplicate coverage,
   partially overlapping regions, or unproven period balances together.
7. Only verified explicit anchors and structural totals can become automatically trusted, and every
   §8.4 gate has persisted evidence. Leaf-sum-only candidates require analyst acceptance.
8. Mapping decisions survive only an unchanged source file + region key; total decisions survive
   only an unchanged candidate fingerprint. Manual corrections are contributions, never overwrites.
9. An analyst can operate the complete review lifecycle through the Sprint 3b CLI without waiting
   for the Sprint 4 UI.
10. Re-ingesting the same file/configuration is idempotent by
    `(mandate_id, file_hash, parser_version, config_hash)` while preserving immutable older runs.
11. V11 resets populated pre-live workspaces only with explicit path-scoped opt-in, atomically, and
    leaves a foreign-key-clean target schema.
12. Region and semantic snapshots reveal no workbook-derived free text or financial values, and
    synthetic CI fixtures exercise every deterministic/trust branch.
13. Downstream modules can answer, for any retained cell or cost head: source value, cleaned value,
    formula, cached/error state, provenance, region, mapping, contributions, candidate total,
    trusted total, confidence, reasons and review state.
