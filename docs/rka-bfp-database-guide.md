# RKA & BFP workbook databases: guide for an analyst or agent

_Generated 2026-10-05 from the live databases (schema, counts and value lists are read from the files, not typed by hand). The notes in sections 1 to 4 are written by hand._

## 1. What these databases are

The output of the TEV parser pipeline on one Excel workbook, **`Project Docs/Projection_RKA & BFP.xlsx`** (22 visible sheets, 25,898 cells, 15,405 of them formulas). One SQLite file per pipeline run. Both files have the **same schema** and the same ingested cell data; they differ in what the LLM stages decided.

| | Path | Notes |
| --- | --- | --- |
| **Run 1 (primary)** | `~/tev-runs/rka-bfp-run1/workspace.db` | Regions cut by gpt-6-luna: 95 Layer A regions (36 main, 51 scratch, 8 orphan), 17,993 cells bound to the ontology. Produced by commit `9a6fe5a`. |
| Run 2 | `~/tev-runs/rka-bfp-run2/workspace.db` | Regions cut by mercury-2.5 after luna timed out: 140 Layer A regions (27 main, 113 scratch), 15,583 cells bound. Produced by commit `82a0ece`. Use to compare how much region labelling varies. |

Each file has a read-only twin (`workspace.run1-final-20261005.db`, `workspace.run2-final-20261005.db`) with an identical checksum, and a `run.log` with the full console output. Open read-only: `sqlite3 -readonly <file>`. There is exactly one `parse_run` (`parse_run_id = 1`) in each file.

**Real values, not redacted.** The database holds the workbook's real numbers. Only the text sent to external LLMs had its numbers replaced by dummy amounts.

## 2. How the pipeline fills the tables

1. **ingest**: `source_file`, `workbook`, `worksheet`, `worksheet_column`, `cell`, `cell_style`, `cell_comment`, `cell_reference` (every reference token parsed out of every formula), `formula_link` (cell A reads cell B) and `formula_reach` (the transitive closure of `formula_link`, with depth), `provenance`.
2. **discover**: `candidate` rows of kind `coverage_parent`, one per sheet, containing every cell of the sheet (`candidate_member`).
3. **classify, stage 1 (region layout, LLM)**: replaces them with narrower `child` candidates, each carrying `structural_role` (`main`, `helper` or `scratch`).
4. **classify, stage 2 (Layer A, LLM)**: one `packet_disposition` row per `child` with role `main` or `helper`: the LLM's `triage` (main / scratch / orphan), `relevance`, `schedule_family`, a text `about` description, row labels, column headers and `stated_scale` (a money unit the region literally states, such as "Rs. in Lacs" = `lakh`).
5. **classify, stage 3 (Layer B)**: `cell_reading`, the type of every numeric cell (the main table to analyse), then `region_header_geometry`, `nomenclature_binding`, `cell_interpretation` and `cell_interpretation_evidence` (what each cell means and where in the ontology it sits).
6. Run bookkeeping: `llm_usage` (tokens and cost per stage and model), `run_stat` (counters per stage), `run_timing`.

## 3. Key joins and rules

* **Cell to sheet:** `cell.worksheet_id` = `worksheet.worksheet_id`. A cell is identified by `(worksheet_id, coord)`, for example `'1.2_PL_RKA'`, `'D41'`. `row_num` and `col_num` are 1-based.
* **A cell belongs to several regions.** `candidate_member` holds both the sheet-wide `coverage_parent` and a `child`. To get a cell's real region, use the `child` (the narrower one): `candidate.candidate_kind = 'child'`. Counting members across all candidates counts every cell about twice.
* **Region label per cell:** `cell` to `candidate_member` to `candidate` (child) to `packet_disposition` (triage, family, `about`, `stated_scale`). 28 of the 123 candidates have no disposition: the 22 coverage parents (never sent to Layer A) and 6 child regions that stage 1 labelled `scratch`.
* **Two different "main/scratch" labels.** `candidate.structural_role` is the stage-1 (region layout) label: `main`, `helper` or `scratch`; only `main` and `helper` regions (33 + 62 = 95 in run 1) reach Layer A. `packet_disposition.triage` is Layer A's own judgement of those regions: `main` / `scratch` / `orphan` (36 / 51 / 8 in run 1). They are separate fields and can disagree.
* **Typing:** `cell_reading.cell_id` = `cell.cell_id`. Only numeric cells have a row (17,839 of 25,898 cells).
* **Formula graph:** `formula_link(from_cell_id, to_cell_id)` means *from* reads *to*. To find what feeds a cell, select `to_cell_id` where `from_cell_id` is the cell; to find what a cell feeds, reverse it.
* **Labels:** there is no label column. Row labels are the text cells to the left of a cell on the same row; column headers are the text cells above it. `cell_interpretation_evidence` holds the resolved labels, periods, scale and currency cues per cell (`role`, `source_cell_id`, `source_text`).

## 4. Reading `cell_reading` (Layer B typing)

| Column | Meaning |
| --- | --- |
| `kind` | `money`, `quantity`, `rate` (money per quantity), `percent`, `count`, `ratio`. NULL when refused or untyped. |
| `scale` | `unit`, `thousand`, `lakh`, `million`, `crore`, `billion`. **Only meaningful for money**; for other kinds it is `unit`. For money, NULL means the sheet states no scale and none could be inferred. |
| `scale_basis` | `stated` (a label, region or sheet states it) or `inferred` (taken from the sheets that read this cell). |
| `unit`, `currency` | Optional unit text (such as sqm; set on 157 cells in run 1) and currency code (`INR` on 1,583 cells, empty otherwise). |
| `absolute_amount` | For money with a scale: the cell's number times the scale factor, as text (a value of 4933.389 with scale `million` gives 4933389092.47 style base units, here 4,933,389,092.47). |
| `type_source` | `input`: typed from labels, number format, the dictionary or a model. `derived`: typed by arithmetic over its precedents, or by a structural rule. Treat `derived` on a formula as stronger evidence than `input`. |
| `refusal` | Set instead of `kind`: `untypable` (nothing could type it), `kind_conflict` (the arithmetic mixes incompatible kinds), `cycle`, `external_dependency`. |

`kind` and `refusal` are mutually exclusive. A row with both NULL is an untyped input.

**Known weak spots in this data (found by hand audit of run 1):**
* A revenue and total chain under `Schedule_BFP` is typed `quantity`; margins built on it come out as `rate`.
* Formulas in `Debt-profile Bank wise` column U are typed inconsistently down the column (mostly money, some quantity).
* A `%` inside a money label (for example "OD @9.5%") can be typed `percent`.
* About 1% of typed cells (mostly `quantity`/`rate`) have row labels that read as plain money (Debentures, Term loan, Provision).
* 2,124 of the 13,838 money cells have a NULL scale (nothing states one and none could be inferred). The run log also reports 73 money cells whose scale differs from the one their region or sheet states.
* Region labelling (`triage`) varies run to run: compare run 1 and run 2.
* Overall: 86% of typed cells are money, 6% percent, 4% ratio, 3% quantity.

**Status of these weak spots.** The databases above were produced before the fixes and still contain these errors. Commit `1e6af74` fixes the first and third in code (an untyped addend now takes the kind of the money it is added to, so the `Schedule_BFP` revenue chain becomes money; a `%` after a number in a label no longer makes a cell a percent; "... Days" rows are a quantity of days). On a no-model replay of run 1 that types 348 more formula cells and moves revenue, totals and margins to the right kinds. The inconsistent `Debt-profile Bank wise` column and run-to-run region variation are not fixed yet. A new run is needed to see the corrected data in a database.


## 5. Full schema

Every table below is shown with its exact `CREATE TABLE` statement, then the row count in each database, then the values of every column that has 14 or fewer distinct values (with counts, run 1 first; run 2 where it differs).


### `audit_log`  (rows: run 1 = 2, run 2 = 2)

```sql
CREATE TABLE "audit_log" (
    audit_log_id        INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id        INTEGER REFERENCES parse_run (parse_run_id),
    event_type          TEXT NOT NULL,
    event_at            TEXT NOT NULL,
    payload             TEXT,                       -- JSON object
    severity            TEXT NOT NULL               -- 'info' | 'warning' | 'error'
)
```

Foreign keys: `parse_run_id` -> `parse_run.parse_run_id`

Values (run 1 | run 2 if different):

* `event_type`: `parse_run_started` 1, `parse_run_completed` 1
* `event_at`: `2026-10-04T22:59:56.251648Z` 1, `2026-10-04T22:59:30.624010Z` 1 | run 2: `2026-10-05T11:37:57.350287Z` 1, `2026-10-05T11:37:30.405427Z` 1
* `severity`: `info` 2


### `candidate`  (rows: run 1 = 123, run 2 = 163)

```sql
CREATE TABLE candidate (
    candidate_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    worksheet_id INTEGER NOT NULL REFERENCES worksheet (worksheet_id),
    candidate_kind TEXT NOT NULL CHECK (candidate_kind IN (
        'coverage_parent', 'child', 'parallel', 'overlap', 'related')),
    parent_candidate_id INTEGER REFERENCES candidate (candidate_id),
    bbox_min_row INTEGER,
    bbox_min_col INTEGER,
    bbox_max_row INTEGER,
    bbox_max_col INTEGER,
    internal_whitespace TEXT,
    anchors TEXT,
    structural_signatures TEXT,
    isolated_hidden_worksheet INTEGER NOT NULL DEFAULT 0
        CHECK (isolated_hidden_worksheet IN (0, 1)),
    structural_confidence REAL,
    structural_confidence_rationale TEXT,
    explanation TEXT,
    created_at TEXT NOT NULL
, structural_role TEXT
    CHECK (structural_role IS NULL OR structural_role IN ('main', 'helper', 'scratch')))
```

Foreign keys: `parent_candidate_id` -> `candidate.candidate_id`; `worksheet_id` -> `worksheet.worksheet_id`; `parse_run_id` -> `parse_run.parse_run_id`

Values (run 1 | run 2 if different):

* `candidate_kind`: `child` 101, `coverage_parent` 22 | run 2: `child` 141, `coverage_parent` 22
* `isolated_hidden_worksheet`: `0` 123 | run 2: `0` 163
* `structural_confidence`: `0.9` 87, `1.0` 22, `0.5` 14 | run 2: `0.5` 89, `0.9` 52, `1.0` 22
* `structural_confidence_rationale`: `llm region layout` 87, `mandatory coverage parent for every persisted cell...` 22, `residual cells no model region claimed` 14 | run 2: `residual cells no model region claimed` 89, `llm region layout` 52, `mandatory coverage parent for every persisted cell...` 22
* `structural_role`: `helper` 62, `main` 33, NULL 22, `scratch` 6 | run 2: `helper` 114, `main` 26, NULL 22, `scratch` 1


### `candidate_member`  (rows: run 1 = 51,849, run 2 = 51,501)

```sql
CREATE TABLE candidate_member (
    candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id) ON DELETE CASCADE,
    cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    PRIMARY KEY (candidate_id, cell_id)
)
```

Foreign keys: `cell_id` -> `cell.cell_id`; `candidate_id` -> `candidate.candidate_id`


### `candidate_related`  (rows: run 1 = 0, run 2 = 0)

```sql
CREATE TABLE candidate_related (
    candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id) ON DELETE CASCADE,
    related_candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id) ON DELETE CASCADE,
    relationship_kind TEXT NOT NULL,
    PRIMARY KEY (candidate_id, related_candidate_id, relationship_kind),
    CHECK (candidate_id != related_candidate_id)
)
```

Foreign keys: `related_candidate_id` -> `candidate.candidate_id`; `candidate_id` -> `candidate.candidate_id`


### `cell`  (rows: run 1 = 25,898, run 2 = 25,898)

```sql
CREATE TABLE "cell" (
    cell_id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    worksheet_id            INTEGER NOT NULL REFERENCES worksheet (worksheet_id),
    coord                   TEXT NOT NULL,
    row_num                 INTEGER NOT NULL,
    col_num                 INTEGER NOT NULL,
    raw_value               TEXT,
    raw_type                TEXT NOT NULL,
    value_type              TEXT NOT NULL,
    text_value              TEXT,
    display_value           TEXT,
    numeric_value           NUMERIC,
    bool_value              INTEGER CHECK (bool_value IN (0, 1)),
    date_value              TEXT,
    formula_text            TEXT,
    formula_state           TEXT,
    cached_value            TEXT,
    cache_state             TEXT,
    coerced_from_text       INTEGER NOT NULL DEFAULT 0 CHECK (coerced_from_text IN (0, 1)),
    is_error                INTEGER NOT NULL DEFAULT 0 CHECK (is_error IN (0, 1)),
    error_type              TEXT,
    is_merged_anchor        INTEGER NOT NULL DEFAULT 0 CHECK (is_merged_anchor IN (0, 1)),
    is_merged_participant   INTEGER NOT NULL DEFAULT 0 CHECK (is_merged_participant IN (0, 1)),
    merged_range            TEXT,
    value_source            TEXT NOT NULL DEFAULT 'cell' CHECK (value_source IN ('cell', 'merged_anchor')),
    row_hidden              INTEGER NOT NULL DEFAULT 0 CHECK (row_hidden IN (0, 1)),
    col_hidden              INTEGER NOT NULL DEFAULT 0 CHECK (col_hidden IN (0, 1)),
    sheet_hidden            INTEGER NOT NULL DEFAULT 0 CHECK (sheet_hidden IN (0, 1))
, style_id INTEGER REFERENCES cell_style (style_id), formula_normalized TEXT)
```

Foreign keys: `style_id` -> `cell_style.style_id`; `worksheet_id` -> `worksheet.worksheet_id`

Values (run 1 | run 2 if different):

* `raw_type`: `formula` 15,405, `empty` 4,721, `text` 2,950, `number` 2,798, `date` 24
* `value_type`: `number` 17,839, `empty` 4,721, `text` 3,026, `error` 288, `date` 24
* `formula_state`: `ok` 15,405, NULL 10,493
* `cache_state`: `fresh` 15,405, NULL 10,493
* `value_source`: `cell` 25,476, `merged_anchor` 422


### `cell_comment`  (rows: run 1 = 8, run 2 = 8)

```sql
CREATE TABLE cell_comment (
    cell_id INTEGER PRIMARY KEY REFERENCES cell (cell_id),
    author TEXT,
    body TEXT NOT NULL
)
```

Foreign keys: `cell_id` -> `cell.cell_id`

Values (run 1 | run 2 if different):

* `author`: `tc={A354F370-7AE8-4729-89A7-1902CAC960DC}` 1, `tc={A1D17E91-CFD2-4EE4-ABCD-47279520BC98}` 1, `tc={876CBFF3-3217-4610-B316-AF86EB047E15}` 1, `tc={7BF3FC7B-A418-445E-B0F7-36F3C9C04FE5}` 1, `tc={49F20C05-D358-4978-A252-10FF03380AC1}` 1, `tc={2E811ACD-8D39-472C-9205-6F3D8FE0489F}` 1, `tc={2AB4106D-B9AA-4C98-BCF3-7FF9D39693C4}` 1, `tc={2A3CFF49-D201-4995-A26F-E778FB7383FA}` 1


### `cell_interpretation`  (rows: run 1 = 25,898, run 2 = 25,898)

```sql
CREATE TABLE cell_interpretation (
    interpretation_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    value_origin TEXT NOT NULL CHECK (value_origin IN ('literal', 'formula')),
    resulting_value TEXT,
    result_source TEXT NOT NULL CHECK (result_source IN ('literal', 'formula_cache', 'missing_cache')),
    formula_text TEXT,
    formula_state TEXT,
    cache_state TEXT,
    is_error INTEGER NOT NULL DEFAULT 0 CHECK (is_error IN (0, 1)),
    error_type TEXT,
    nomenclature_path TEXT,
    amount_role TEXT CHECK (amount_role IS NULL OR amount_role IN ('add', 'deduct', 'total', 'helper')),
    soft_leaf INTEGER CHECK (soft_leaf IS NULL OR soft_leaf IN (0, 1)),
    via_alias INTEGER CHECK (via_alias IS NULL OR via_alias IN (0, 1)),
    nomenclature_status TEXT NOT NULL CHECK (
        nomenclature_status IN ('bound', 'unbound', 'not_applicable')),
    formula_gloss TEXT,
    unbound_reason TEXT,
    created_at TEXT NOT NULL,
    UNIQUE (parse_run_id, cell_id)
)
```

Foreign keys: `cell_id` -> `cell.cell_id`; `parse_run_id` -> `parse_run.parse_run_id`

Values (run 1 | run 2 if different):

* `value_origin`: `formula` 15,405, `literal` 10,493
* `result_source`: `formula_cache` 15,405, `literal` 10,493
* `formula_state`: `ok` 15,405, NULL 10,493
* `cache_state`: `fresh` 15,405, NULL 10,493
* `is_error`: `0` 25,610, `1` 288
* `error_type`: NULL 25,610, `#REF!` 287, `#DIV/0!` 1
* `amount_role`: NULL 15,519, `add` 5,370, `helper` 3,895, `total` 1,114 | run 2: NULL 17,238, `add` 4,310, `helper` 3,539, `total` 811
* `nomenclature_status`: `bound` 17,993, `unbound` 7,598, `not_applicable` 307 | run 2: `bound` 15,583, `unbound` 10,026, `not_applicable` 289


### `cell_interpretation_evidence`  (rows: run 1 = 108,521, run 2 = 109,539)

```sql
CREATE TABLE cell_interpretation_evidence (
    evidence_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL,
    cell_id INTEGER NOT NULL,
    role TEXT NOT NULL CHECK (role IN (
        'row_header', 'column_header', 'period', 'basis', 'currency', 'scale', 'unit')),
    source_cell_id INTEGER REFERENCES cell (cell_id),
    source_text TEXT,
    ordinal INTEGER NOT NULL,
    resolution TEXT NOT NULL CHECK (resolution IN ('resolved', 'missing', 'ambiguous')),
    normalized_value TEXT,
    rule_id TEXT,
    FOREIGN KEY (parse_run_id, cell_id)
        REFERENCES cell_interpretation (parse_run_id, cell_id) ON DELETE CASCADE
)
```

Foreign keys: `parse_run_id` -> `cell_interpretation.parse_run_id`; `cell_id` -> `cell_interpretation.cell_id`; `source_cell_id` -> `cell.cell_id`

Values (run 1 | run 2 if different):

* `role`: `column_header` 56,275, `row_header` 35,727, `period` 9,331, `basis` 5,400, `scale` 1,030, `currency` 591, `unit` 167 | run 2: `column_header` 56,737, `row_header` 36,303, `period` 9,606, `basis` 5,296, `scale` 802, `currency` 623, `unit` 172
* `resolution`: `resolved` 98,202, `missing` 8,784, `ambiguous` 1,535 | run 2: `resolved` 98,311, `missing` 9,309, `ambiguous` 1,919


### `cell_reading`  (rows: run 1 = 17,839, run 2 = 17,839)

```sql
CREATE TABLE "cell_reading" (
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    kind TEXT,
    scale TEXT,
    unit TEXT NOT NULL DEFAULT '',
    currency TEXT NOT NULL DEFAULT '',
    absolute_amount TEXT,
    type_source TEXT,
    refusal TEXT,
    scale_basis TEXT,
    PRIMARY KEY (parse_run_id, cell_id),
    CHECK (kind IS NULL OR kind IN (
        'money', 'quantity', 'rate', 'percent', 'count', 'ratio')),
    CHECK (scale IS NULL OR scale IN (
        'unit', 'thousand', 'lakh', 'million', 'crore', 'billion')),
    CHECK (type_source IS NULL OR type_source IN ('input', 'derived')),
    CHECK (refusal IS NULL OR refusal IN (
        'untypable', 'kind_conflict', 'cycle', 'external_dependency')),
    CHECK (absolute_amount IS NULL OR (kind = 'money' AND scale IS NOT NULL)),
    CHECK (scale_basis IS NULL OR (kind = 'money' AND scale IS NOT NULL
        AND scale_basis IN ('stated', 'inferred'))),
    CHECK (
        (refusal IS NULL AND kind IS NOT NULL AND type_source IS NOT NULL
            AND (scale IS NOT NULL OR kind = 'money'))
        OR (refusal IS NOT NULL AND kind IS NULL AND scale IS NULL
            AND type_source IS NULL AND absolute_amount IS NULL)
        OR (refusal IS NULL AND kind IS NULL AND scale IS NULL
            AND type_source IS NULL AND absolute_amount IS NULL)
    )
)
```

Foreign keys: `cell_id` -> `cell.cell_id`; `parse_run_id` -> `parse_run.parse_run_id`

Values (run 1 | run 2 if different):

* `kind`: `money` 13,838, NULL 1,838, `percent` 926, `ratio` 586, `quantity` 405, `count` 153, `rate` 93 | run 2: `money` 13,828, NULL 1,804, `percent` 917, `ratio` 617, `quantity` 453, `count` 133, `rate` 87
* `scale`: `million` 9,159, NULL 3,962, `unit` 2,162, `lakh` 2,119, `crore` 331, `thousand` 106 | run 2: `million` 9,962, NULL 4,192, `unit` 2,206, `crore` 905, `lakh` 574
* `unit`: `` 17,682, `nos` 122, `trains` 16, `per train` 10, `months` 6, `train` 3 | run 2: `` 17,672, `nos` 127, `trains` 19, `per train` 8, `months` 6, `train` 3, `INR/train` 3, `days` 1
* `currency`: `` 16,256, `INR` 1,583 | run 2: `` 16,049, `INR` 1,789, `RSD` 1
* `type_source`: `derived` 10,215, `input` 5,786, NULL 1,838 | run 2: `derived` 10,588, `input` 5,447, NULL 1,804
* `refusal`: NULL 17,480, `untypable` 187, `kind_conflict` 172 | run 2: NULL 17,516, `kind_conflict` 220, `untypable` 103
* `scale_basis`: `stated` 9,934, NULL 6,125, `inferred` 1,780 | run 2: `stated` 9,837, NULL 6,399, `inferred` 1,603


### `cell_reference`  (rows: run 1 = 26,737, run 2 = 26,737)

```sql
CREATE TABLE cell_reference (
    cell_reference_id   INTEGER PRIMARY KEY AUTOINCREMENT,
    from_cell_id        INTEGER NOT NULL REFERENCES cell (cell_id),
    token_index         INTEGER NOT NULL,
    raw_token           TEXT NOT NULL,
    ref_kind            TEXT NOT NULL,
    target_sheet_name   TEXT,
    target_worksheet_id INTEGER REFERENCES worksheet (worksheet_id),
    target_range        TEXT,
    resolved_cell_id    INTEGER REFERENCES cell (cell_id),
    external_link_id    INTEGER REFERENCES external_link (external_link_id),
    abs_row             INTEGER CHECK (abs_row IN (0, 1)),
    abs_col             INTEGER CHECK (abs_col IN (0, 1)),
    row_offset          INTEGER,
    col_offset          INTEGER,
    is_whole_column     INTEGER NOT NULL DEFAULT 0 CHECK (is_whole_column IN (0, 1)),
    is_whole_row        INTEGER NOT NULL DEFAULT 0 CHECK (is_whole_row IN (0, 1)),
    unresolved_reason   TEXT
)
```

Foreign keys: `external_link_id` -> `external_link.external_link_id`; `resolved_cell_id` -> `cell.cell_id`; `target_worksheet_id` -> `worksheet.worksheet_id`; `from_cell_id` -> `cell.cell_id`

Values (run 1 | run 2 if different):

* `ref_kind`: `local_cell` 17,201, `cross_sheet_cell` 6,179, `local_range` 3,285, `cross_sheet_range` 72


### `cell_style`  (rows: run 1 = 586, run 2 = 586)

```sql
CREATE TABLE cell_style (
    style_id                INTEGER PRIMARY KEY AUTOINCREMENT,
    is_bold                 INTEGER CHECK (is_bold IN (0, 1)),
    number_format           TEXT,
    fill_fg_color           TEXT,
    fill_pattern            TEXT,
    border_top_style        TEXT,
    border_top_color        TEXT,
    border_right_style      TEXT,
    border_right_color      TEXT,
    border_bottom_style     TEXT,
    border_bottom_color     TEXT,
    border_left_style       TEXT,
    border_left_color       TEXT
, font_name TEXT, font_size INTEGER, italic INTEGER CHECK (italic IN (0, 1)), underline TEXT, font_color TEXT)
```

Values (run 1 | run 2 if different):

* `is_bold`: `1` 330, `0` 256
* `fill_pattern`: NULL 349, `SOLID_FOREGROUND` 237
* `border_top_style`: `THIN` 295, NULL 228, `MEDIUM` 63
* `border_top_color`: NULL 577, `0` 9
* `border_right_style`: NULL 297, `THIN` 220, `MEDIUM` 69
* `border_bottom_style`: `THIN` 261, NULL 220, `MEDIUM` 86, `DOUBLE` 19
* `border_left_style`: NULL 242, `THIN` 235, `MEDIUM` 109
* `font_name`: `Verdana` 422, `Book Antiqua` 71, `Garamond` 64, `Arial` 20, `Times New Roman` 6, `Aptos` 3
* `font_size`: `9` 439, `10` 74, `11` 70, `12` 3
* `italic`: `0` 581, `1` 5
* `underline`: NULL 578, `SINGLE` 8
* `font_color`: `8` 401, `#000000` 146, `#ff0000` 22, `#ffffff` 15, `#008000` 2


### `external_link`  (rows: run 1 = 0, run 2 = 0)

```sql
CREATE TABLE external_link (
    external_link_id    INTEGER PRIMARY KEY AUTOINCREMENT,
    workbook_id         INTEGER NOT NULL REFERENCES workbook (workbook_id),
    link_type           TEXT NOT NULL,              -- 'external' | 'hyperlink' | 'ole'
    target_path         TEXT NOT NULL,
    status              TEXT NOT NULL,              -- 'active' | 'broken' | 'unchecked'
    checked_at          TEXT
, link_index INTEGER)
```

Foreign keys: `workbook_id` -> `workbook.workbook_id`


### `formula_gap`  (rows: run 1 = 0, run 2 = 0)

```sql
CREATE TABLE formula_gap (
    from_cell_id    INTEGER NOT NULL REFERENCES cell (cell_id),
    reason          TEXT NOT NULL,
    raw_token       TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (from_cell_id, reason, raw_token)
)
```

Foreign keys: `from_cell_id` -> `cell.cell_id`


### `formula_link`  (rows: run 1 = 72,788, run 2 = 72,788)

```sql
CREATE TABLE formula_link (
    from_cell_id    INTEGER NOT NULL REFERENCES cell (cell_id),
    to_cell_id      INTEGER NOT NULL REFERENCES cell (cell_id),
    PRIMARY KEY (from_cell_id, to_cell_id)
)
```

Foreign keys: `to_cell_id` -> `cell.cell_id`; `from_cell_id` -> `cell.cell_id`


### `formula_reach`  (rows: run 1 = 3,229,383, run 2 = 3,229,383)

```sql
CREATE TABLE formula_reach (
    from_cell_id    INTEGER NOT NULL REFERENCES cell (cell_id),
    to_cell_id      INTEGER NOT NULL REFERENCES cell (cell_id),
    depth           INTEGER NOT NULL CHECK (depth >= 1),
    PRIMARY KEY (from_cell_id, to_cell_id)
)
```

Foreign keys: `to_cell_id` -> `cell.cell_id`; `from_cell_id` -> `cell.cell_id`


### `ingest_rejection`  (rows: run 1 = 0, run 2 = 0)

```sql
CREATE TABLE ingest_rejection (
    ingest_rejection_id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_file_id      INTEGER REFERENCES source_file (source_file_id),
    mandate_id          INTEGER NOT NULL,
    file_name           TEXT NOT NULL,
    file_hash           TEXT NOT NULL,
    reason              TEXT NOT NULL,
    detail              TEXT,                       -- JSON object
    rejected_at         TEXT NOT NULL
)
```

Foreign keys: `source_file_id` -> `source_file.source_file_id`


### `llm_usage`  (rows: run 1 = 7, run 2 = 9)

```sql
CREATE TABLE "llm_usage" (
    usage_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    stage TEXT NOT NULL CHECK (stage IN ('region-layout', 'layer-a', 'layer-b', 'other')),
    model_id TEXT NOT NULL,
    calls INTEGER NOT NULL,
    failed_calls INTEGER NOT NULL DEFAULT 0,
    failovers INTEGER NOT NULL DEFAULT 0,
    prompt_tokens INTEGER NOT NULL,
    completion_tokens INTEGER NOT NULL,
    cost_usd REAL,
    cost_missing INTEGER NOT NULL DEFAULT 0,
    latency_ms_total INTEGER NOT NULL DEFAULT 0,
    recorded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (parse_run_id, stage, model_id)
)
```

Foreign keys: `parse_run_id` -> `parse_run.parse_run_id`

Values (run 1 | run 2 if different):

* `stage`: `layer-b` 3, `layer-a` 3, `region-layout` 1 | run 2: `layer-b` 4, `layer-a` 3, `region-layout` 2
* `calls`: `2149` 1, `86` 1, `65` 1, `4` 1, `2` 1, `1` 1, `0` 1 | run 2: `2212` 1, `39` 1, `27` 1, `24` 1, `13` 1, `8` 1, `6` 1, `3` 1, `0` 1
* `failed_calls`: `0` 4, `1` 2, `4` 1 | run 2: `6` 2, `2` 2, `0` 2, `25` 1, `4` 1, `3` 1
* `failovers`: `1` 3, `0` 3, `4` 1 | run 2: `0` 3, `6` 2, `2` 2, `4` 1, `3` 1
* `prompt_tokens`: `2262605` 1, `1796366` 1, `1081105` 1, `301423` 1, `294500` 1, `48782` 1, `0` 1 | run 2: `1833470` 1, `1226076` 1, `619583` 1, `598777` 1, `413174` 1, `389030` 1, `307891` 1, `100318` 1, `0` 1
* `completion_tokens`: `0` 2, `97259` 1, `37457` 1, `27673` 1, `5466` 1, `4092` 1 | run 2: `0` 2, `153697` 1, `30730` 1, `18842` 1, `18756` 1, `10939` 1, `8251` 1, `5702` 1
* `cost_usd`: `0.4870996750000001` 1, `0.23622` 1, `0.18373952500000001` 1, `0.07185464000000018` 1, `0.040410725` 1, `0.00610223` 1, `0.0` 1 | run 2: `0.4800239999999999` 1, `0.145896` 1, `0.07333880000000008` 1, `0.06700995` 1, `0.051856440000000004` 1, `0.04783787` 1, `0.04384505` 1, `0.0164165` 1, `0.0` 1
* `cost_missing`: `0` 7 | run 2: `0` 9
* `latency_ms_total`: `1242312` 1, `833958` 1, `419698` 1, `41505` 1, `35717` 1, `26582` 1, `3326` 1 | run 2: `1607180` 1, `909893` 1, `799832` 1, `361888` 1, `343950` 1, `296210` 1, `186067` 1, `64558` 1, `33826` 1


### `mandate_industry`  (rows: run 1 = 1, run 2 = 1)

```sql
CREATE TABLE mandate_industry (
    mandate_id INTEGER PRIMARY KEY,
    industry_tag TEXT NOT NULL,
    confirmed INTEGER NOT NULL CHECK (confirmed IN (0, 1)),
    inferred INTEGER NOT NULL CHECK (inferred IN (0, 1)),
    updated_at TEXT NOT NULL
)
```

Values (run 1 | run 2 if different):

* `industry_tag`: `unspecified` 1
* `confirmed`: `0` 1
* `inferred`: `1` 1
* `updated_at`: `2026-10-04T23:10:37.441866Z` 1 | run 2: `2026-10-05T11:57:11.065250Z` 1


### `nomenclature_alias`  (rows: run 1 = 92, run 2 = 143)

```sql
CREATE TABLE nomenclature_alias (
    alias_id INTEGER PRIMARY KEY AUTOINCREMENT,
    alias_text TEXT NOT NULL,
    leaf_path TEXT NOT NULL,
    layer TEXT NOT NULL CHECK (layer IN ('spine', 'industry', 'mandate_soft')),
    industry_tag TEXT,
    mandate_id INTEGER,
    created_at TEXT NOT NULL
)
```

Values (run 1 | run 2 if different):

* `layer`: `spine` 48, `mandate_soft` 36, `industry` 8 | run 2: `mandate_soft` 87, `spine` 48, `industry` 8
* `industry_tag`: NULL 84, `hotel` 8 | run 2: NULL 135, `hotel` 8


### `nomenclature_binding`  (rows: run 1 = 17,993, run 2 = 15,583)

```sql
CREATE TABLE nomenclature_binding (
    binding_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id) ON DELETE CASCADE,
    cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    path_root TEXT NOT NULL CHECK (path_root IN ('economic', 'identity', 'frame')),
    path TEXT NOT NULL,
    amount_role TEXT CHECK (amount_role IS NULL OR amount_role IN ('add', 'deduct', 'total', 'helper')),
    verbatim TEXT,
    created_at TEXT NOT NULL,
    UNIQUE (parse_run_id, cell_id)
)
```

Foreign keys: `cell_id` -> `cell.cell_id`; `candidate_id` -> `candidate.candidate_id`; `parse_run_id` -> `parse_run.parse_run_id`

Values (run 1 | run 2 if different):

* `path_root`: `economic` 12,950, `frame` 5,032, `identity` 11 | run 2: `economic` 11,048, `frame` 4,527, `identity` 8
* `amount_role`: NULL 7,614, `add` 5,370, `helper` 3,895, `total` 1,114 | run 2: NULL 6,923, `add` 4,310, `helper` 3,539, `total` 811


### `nomenclature_node`  (rows: run 1 = 95, run 2 = 146)

```sql
CREATE TABLE nomenclature_node (
    node_id INTEGER PRIMARY KEY AUTOINCREMENT,
    path TEXT NOT NULL,
    name TEXT NOT NULL,
    parent_path TEXT,
    layer TEXT NOT NULL CHECK (layer IN ('spine', 'industry', 'mandate_soft')),
    frozen INTEGER NOT NULL CHECK (frozen IN (0, 1)),
    leaf INTEGER NOT NULL CHECK (leaf IN (0, 1)),
    industry_tag TEXT,
    mandate_id INTEGER,
    created_at TEXT NOT NULL
)
```

Values (run 1 | run 2 if different):

* `layer`: `spine` 54, `mandate_soft` 36, `industry` 5 | run 2: `mandate_soft` 87, `spine` 54, `industry` 5
* `frozen`: `0` 81, `1` 14 | run 2: `0` 132, `1` 14
* `leaf`: `0` 54, `1` 41 | run 2: `1` 92, `0` 54
* `industry_tag`: NULL 90, `hotel` 5 | run 2: NULL 141, `hotel` 5


### `packet_disposition`  (rows: run 1 = 95, run 2 = 140)

```sql
CREATE TABLE packet_disposition (
    disposition_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id) ON DELETE CASCADE,
    schedule_family TEXT NOT NULL,
    triage TEXT NOT NULL CHECK (triage IN ('main', 'scratch', 'orphan')),
    relevance TEXT NOT NULL CHECK (relevance IN ('primary', 'supporting', 'noise')),
    row_labels TEXT,
    column_headers TEXT,
    packet_default_head TEXT,
    about TEXT NOT NULL,
    parent_candidate_id INTEGER REFERENCES candidate (candidate_id),
    cheap_pass INTEGER NOT NULL CHECK (cheap_pass IN (0, 1)),
    created_at TEXT NOT NULL, stated_scale TEXT
    CHECK (stated_scale IS NULL OR stated_scale IN (
        'unit', 'thousand', 'lakh', 'million', 'crore', 'billion')), scale_evidence_cell TEXT,
    UNIQUE (parse_run_id, candidate_id)
)
```

Foreign keys: `parent_candidate_id` -> `candidate.candidate_id`; `candidate_id` -> `candidate.candidate_id`; `parse_run_id` -> `parse_run.parse_run_id`

Values (run 1 | run 2 if different):

* `schedule_family`: `balance_sheet` 27, `assumptions` 20, `means_of_finance` 16, `profit_and_loss` 14, `project_summary` 8, `unclassified` 5, `cash_flow` 5 | run 2: `balance_sheet` 56, `profit_and_loss` 25, `assumptions` 25, `means_of_finance` 19, `cash_flow` 13, `project_summary` 2
* `triage`: `scratch` 51, `main` 36, `orphan` 8 | run 2: `scratch` 113, `main` 27
* `relevance`: `supporting` 50, `primary` 33, `noise` 12 | run 2: `supporting` 110, `primary` 30
* `cheap_pass`: `0` 95 | run 2: `0` 140
* `stated_scale`: NULL 52, `million` 28, `crore` 14, `lakh` 1 | run 2: NULL 95, `million` 29, `crore` 11, `lakh` 5


### `parse_run`  (rows: run 1 = 1, run 2 = 1)

```sql
CREATE TABLE parse_run (
    parse_run_id    INTEGER PRIMARY KEY AUTOINCREMENT,
    source_file_id  INTEGER NOT NULL REFERENCES source_file (source_file_id),
    mandate_id      INTEGER NOT NULL,
    parser_version  TEXT NOT NULL,
    config_hash     TEXT,
    started_at      TEXT NOT NULL,
    finished_at     TEXT,
    status          TEXT NOT NULL,      -- 'success' | 'partial' | 'failed' | 'rejected'
    metrics         TEXT,
    warnings        TEXT,
    errors          TEXT,
    UNIQUE (source_file_id, parser_version, config_hash)
)
```

Foreign keys: `source_file_id` -> `source_file.source_file_id`

Values (run 1 | run 2 if different):

* `parser_version`: `0.1.2-SNAPSHOT` 1
* `started_at`: `2026-10-04T22:59:30.624010Z` 1 | run 2: `2026-10-05T11:37:30.405427Z` 1
* `finished_at`: `2026-10-04T22:59:56.251409Z` 1 | run 2: `2026-10-05T11:37:57.349905Z` 1
* `status`: `success` 1


### `project_fact_field`  (rows: run 1 = 8, run 2 = 8)

```sql
CREATE TABLE project_fact_field (
    field_id INTEGER PRIMARY KEY AUTOINCREMENT,
    path TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    created_at TEXT NOT NULL
)
```

Values (run 1 | run 2 if different):

* `path`: `Project > Installed Capacity` 1, `Project > Manpower` 1, `Project > Power Connection` 1, `Project > Site / Location` 1, `Project Identity > Address` 1, `Project Identity > Constitution / Entity Type` 1, `Project Identity > Legal Name` 1, `Project Identity > Partners / Promoters` 1
* `name`: `Site` 1, `Power` 1, `Partners` 1, `Manpower` 1, `Legal Name` 1, `Constitution` 1, `Capacity` 1, `Address` 1


### `provenance`  (rows: run 1 = 25,898, run 2 = 25,898)

```sql
CREATE TABLE provenance (
    provenance_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    entity_type         TEXT NOT NULL,              -- 'cell' | 'worksheet' | 'workbook' | 'parse_run'
    entity_id           INTEGER NOT NULL,
    source_file_id      INTEGER NOT NULL REFERENCES source_file (source_file_id),
    parse_run_id        INTEGER REFERENCES parse_run (parse_run_id),
    location            TEXT NOT NULL,
    raw_value           TEXT,
    confidence          REAL,
    is_derived          INTEGER NOT NULL DEFAULT 0 CHECK (is_derived IN (0, 1)),
    notes               TEXT
)
```

Foreign keys: `parse_run_id` -> `parse_run.parse_run_id`; `source_file_id` -> `source_file.source_file_id`


### `region_header_geometry`  (rows: run 1 = 36, run 2 = 27)

```sql
CREATE TABLE region_header_geometry (
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id) ON DELETE CASCADE,
    geometry_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (parse_run_id, candidate_id)
)
```

Foreign keys: `candidate_id` -> `candidate.candidate_id`; `parse_run_id` -> `parse_run.parse_run_id`


### `review_queue`  (rows: run 1 = 0, run 2 = 0)

```sql
CREATE TABLE review_queue (
    review_queue_id     INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id        INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    category            TEXT NOT NULL,
    summary             TEXT NOT NULL,
    detail              TEXT,                       -- JSON object
    status              TEXT NOT NULL DEFAULT 'Pending',
    is_escalated        INTEGER NOT NULL DEFAULT 0 CHECK (is_escalated IN (0, 1)),
    created_at          TEXT NOT NULL,
    resolved_at         TEXT
, subject_kind TEXT, subject_key TEXT, confidence REAL, carried_from_decision_id INTEGER)
```

Foreign keys: `parse_run_id` -> `parse_run.parse_run_id`


### `run_stat`  (rows: run 1 = 46, run 2 = 46)

```sql
CREATE TABLE run_stat (
    stat_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    stage TEXT NOT NULL CHECK (stage IN (
        'run', 'ingest', 'discover', 'region-layout', 'layer-a', 'layer-b')),
    name TEXT NOT NULL,
    value_num REAL,
    value_text TEXT,
    recorded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (parse_run_id, stage, name),
    CHECK (value_num IS NOT NULL OR value_text IS NOT NULL)
)
```

Foreign keys: `parse_run_id` -> `parse_run.parse_run_id`

Values (run 1 | run 2 if different):

* `stage`: `layer-b` 19, `run` 9, `layer-a` 7, `region-layout` 5, `ingest` 3, `discover` 3
* `value_text`: NULL 41, `whole workbook` 1, `openai/gpt-6-luna  ->  inception/mercury-2.5  ->  ...` 1, `liquid/d1-20260930` 1, `liquid/d1` 1, `Projection_RKA & BFP.xlsx` 1


### `run_timing`  (rows: run 1 = 5, run 2 = 5)

```sql
CREATE TABLE run_timing (
    timing_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    stage TEXT NOT NULL CHECK (stage IN ('ingest', 'discover', 'region-layout', 'layer-a', 'layer-b')),
    started_at TEXT NOT NULL,
    finished_at TEXT NOT NULL,
    duration_millis INTEGER NOT NULL,
    item_count INTEGER,
    recorded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (parse_run_id, stage)
)
```

Foreign keys: `parse_run_id` -> `parse_run.parse_run_id`

Values (run 1 | run 2 if different):

* `stage`: `discover` 1, `ingest` 1, `layer-a` 1, `layer-b` 1, `region-layout` 1
* `started_at`: `2026-10-04T23:05:11.664407Z` 1, `2026-10-04T23:00:33.049399Z` 1, `2026-10-04T22:59:59.066788Z` 1, `2026-10-04T22:59:57.168526Z` 1, `2026-10-04T22:59:28.918337Z` 1 | run 2: `2026-10-05T11:48:30.595098Z` 1, `2026-10-05T11:41:36.600361Z` 1, `2026-10-05T11:38:00.406141Z` 1, `2026-10-05T11:37:58.395122Z` 1, `2026-10-05T11:37:28.830287Z` 1
* `finished_at`: `2026-10-04T23:10:33.689830Z` 1, `2026-10-04T23:04:07.591930Z` 1, `2026-10-04T23:00:31.313553Z` 1, `2026-10-04T22:59:57.971080Z` 1, `2026-10-04T22:59:56.328265Z` 1 | run 2: `2026-10-05T11:57:07.202608Z` 1, `2026-10-05T11:47:20.501956Z` 1, `2026-10-05T11:41:34.346028Z` 1, `2026-10-05T11:37:59.247243Z` 1, `2026-10-05T11:37:57.427686Z` 1
* `duration_millis`: `322025` 1, `214542` 1, `32247` 1, `27410` 1, `803` 1 | run 2: `516607` 1, `343901` 1, `213940` 1, `28597` 1, `852` 1
* `item_count`: `22` 2, `25898` 1, `17839` 1, `95` 1 | run 2: `22` 2, `25898` 1, `17839` 1, `140` 1


### `schedule_family`  (rows: run 1 = 1, run 2 = 0)

```sql
CREATE TABLE schedule_family (
    name TEXT PRIMARY KEY,
    created_at TEXT NOT NULL
)
```

Values (run 1 | run 2 if different):

* `name`: `unclassified` 1


### `schema_migration`  (rows: run 1 = 40, run 2 = 40)

```sql
CREATE TABLE schema_migration (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)
```


### `source_file`  (rows: run 1 = 1, run 2 = 1)

```sql
CREATE TABLE source_file (
    source_file_id  INTEGER PRIMARY KEY AUTOINCREMENT,
    mandate_id      INTEGER NOT NULL,
    file_name       TEXT NOT NULL,
    file_hash       TEXT NOT NULL,
    file_type       TEXT NOT NULL,      -- 'fm_xlsx' | 'fm_xls' | 'fm_csv'
    ingested_at     TEXT NOT NULL,
    parser_version  TEXT NOT NULL, raw_metadata TEXT,
    UNIQUE (mandate_id, file_hash)
)
```

Values (run 1 | run 2 if different):

* `file_name`: `Projection_RKA & BFP.xlsx` 1
* `file_type`: `fm_xlsx` 1
* `ingested_at`: `2026-10-04T22:59:30.624010Z` 1 | run 2: `2026-10-05T11:37:30.405427Z` 1
* `parser_version`: `0.1.2-SNAPSHOT` 1


### `workbook`  (rows: run 1 = 1, run 2 = 1)

```sql
CREATE TABLE workbook (
    workbook_id         INTEGER PRIMARY KEY AUTOINCREMENT,
    source_file_id      INTEGER NOT NULL REFERENCES source_file (source_file_id),
    application_name    TEXT,
    application_version TEXT,
    sheet_count         INTEGER NOT NULL,
    sheet_names         TEXT,                       -- JSON array of sheet names
    defined_names       TEXT,                       -- JSON array
    properties          TEXT,                       -- JSON object
    is_protected        INTEGER NOT NULL DEFAULT 0 CHECK (is_protected IN (0, 1)),
    created_at          TEXT,
    modified_at         TEXT
, calculation_mode TEXT, full_calc_on_load INTEGER CHECK (full_calc_on_load IN (0, 1)), calc_chain_present INTEGER CHECK (calc_chain_present IN (0, 1)), iterative_calc INTEGER CHECK (iterative_calc IN (0, 1)), iterative_count INTEGER, error_cell_count INTEGER, calc_is_circular INTEGER NOT NULL DEFAULT 0 CHECK (calc_is_circular IN (0, 1)), calc_circular_group_count INTEGER, calc_max_cycle_length INTEGER)
```

Foreign keys: `source_file_id` -> `source_file.source_file_id`

Values (run 1 | run 2 if different):

* `application_name`: `Microsoft Excel` 1
* `application_version`: `16.0300` 1
* `sheet_count`: `22` 1
* `properties`: `{"creator":"Rakesh Mishra"}` 1
* `is_protected`: `0` 1
* `modified_at`: `2026-09-17T12:19:23Z` 1
* `calculation_mode`: `auto` 1
* `full_calc_on_load`: `0` 1
* `calc_chain_present`: `1` 1
* `iterative_calc`: `0` 1
* `error_cell_count`: `288` 1
* `calc_is_circular`: `0` 1


### `worksheet`  (rows: run 1 = 22, run 2 = 22)

```sql
CREATE TABLE "worksheet" (
    worksheet_id        INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id        INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    sheet_name          TEXT NOT NULL,
    sheet_index         INTEGER NOT NULL,
    sheet_state         TEXT,
    bbox_min_row        INTEGER,
    bbox_min_col        INTEGER,
    bbox_max_row        INTEGER,
    bbox_max_col        INTEGER,
    dimensions_declared TEXT,
    real_content_rows   INTEGER,
    declared_merged     INTEGER
)
```

Foreign keys: `parse_run_id` -> `parse_run.parse_run_id`

Values (run 1 | run 2 if different):

* `sheet_state`: `visible` 22
* `bbox_min_row`: `1` 20, `3` 1, `2` 1
* `bbox_min_col`: `2` 18, `1` 4
* `declared_merged`: `2` 8, `9` 3, `10` 2, `3` 2, `1` 2, `0` 2, `16` 1, `12` 1, `4` 1


### `worksheet_column`  (rows: run 1 = 506, run 2 = 506)

```sql
CREATE TABLE worksheet_column (
    worksheet_id INTEGER NOT NULL REFERENCES worksheet (worksheet_id),
    col_num INTEGER NOT NULL,
    width INTEGER NOT NULL,
    PRIMARY KEY (worksheet_id, col_num)
)
```

Foreign keys: `worksheet_id` -> `worksheet.worksheet_id`


## 6. Indexes

* `audit_log`: `CREATE INDEX idx_audit_log_parse_run ON audit_log (parse_run_id)`
* `candidate`: `CREATE UNIQUE INDEX idx_candidate_one_coverage_parent
    ON candidate (parse_run_id, worksheet_id)
    WHERE candidate_kind = 'coverage_parent'`
* `candidate`: `CREATE INDEX idx_candidate_parent ON candidate (parent_candidate_id)
    WHERE parent_candidate_id IS NOT NULL`
* `candidate`: `CREATE INDEX idx_candidate_parse_run ON candidate (parse_run_id)`
* `candidate`: `CREATE INDEX idx_candidate_worksheet ON candidate (worksheet_id)`
* `candidate_member`: `CREATE INDEX idx_candidate_member_cell ON candidate_member (cell_id)`
* `candidate_related`: `CREATE INDEX idx_candidate_related_target ON candidate_related (related_candidate_id)`
* `cell`: `CREATE INDEX idx_cell_error ON cell (is_error, error_type)`
* `cell`: `CREATE INDEX idx_cell_numeric ON cell (numeric_value) WHERE numeric_value IS NOT NULL`
* `cell`: `CREATE INDEX idx_cell_style_id ON cell (style_id) WHERE style_id IS NOT NULL`
* `cell`: `CREATE INDEX idx_cell_text ON cell (text_value) WHERE text_value IS NOT NULL`
* `cell`: `CREATE INDEX idx_cell_value_source ON cell (value_source)`
* `cell`: `CREATE INDEX idx_cell_ws_coord ON cell (worksheet_id, coord)`
* `cell_interpretation`: `CREATE INDEX idx_cell_interpretation_path
    ON cell_interpretation (parse_run_id, nomenclature_path)`
* `cell_interpretation`: `CREATE INDEX idx_cell_interpretation_status
    ON cell_interpretation (parse_run_id, nomenclature_status)`
* `cell_interpretation_evidence`: `CREATE INDEX idx_cell_interpretation_evidence_cell
    ON cell_interpretation_evidence (parse_run_id, cell_id, ordinal)`
* `cell_reading`: `CREATE INDEX idx_cell_reading_kind ON cell_reading (parse_run_id, kind)`
* `cell_reference`: `CREATE INDEX idx_cellref_external ON cell_reference (external_link_id) WHERE external_link_id IS NOT NULL`
* `cell_reference`: `CREATE INDEX idx_cellref_from ON cell_reference (from_cell_id)`
* `cell_reference`: `CREATE INDEX idx_cellref_resolved ON cell_reference (resolved_cell_id) WHERE resolved_cell_id IS NOT NULL`
* `cell_reference`: `CREATE INDEX idx_cellref_unresolved ON cell_reference (unresolved_reason) WHERE unresolved_reason IS NOT NULL`
* `cell_style`: `CREATE UNIQUE INDEX idx_cell_style_identity ON cell_style (
    COALESCE(is_bold, -1),
    COALESCE(number_format, ''),
    COALESCE(fill_fg_color, ''),
    COALESCE(fill_pattern, ''),
    COALESCE(border_top_style, ''),
    COALESCE(border_top_color, ''),
    COALESCE(border_right_style, ''),
    COALESCE(border_right_color, ''),
    COALESCE(border_bottom_style, ''),
    COALESCE(border_bottom_color, ''),
    COALESCE(border_left_style, ''),
    COALESCE(border_left_color, ''),
    COALESCE(font_name, ''),
    COALESCE(font_size, -1),
    COALESCE(italic, -1),
    COALESCE(underline, ''),
    COALESCE(font_color, '')
)`
* `external_link`: `CREATE INDEX idx_external_link_index ON external_link (workbook_id, link_index)`
* `external_link`: `CREATE INDEX idx_external_link_workbook      ON external_link (workbook_id)`
* `formula_link`: `CREATE INDEX idx_formula_link_to ON formula_link (to_cell_id)`
* `formula_reach`: `CREATE INDEX idx_formula_reach_to ON formula_reach (to_cell_id)`
* `ingest_rejection`: `CREATE INDEX idx_ingest_rejection_file       ON ingest_rejection (mandate_id, file_hash)`
* `ingest_rejection`: `CREATE INDEX idx_ingest_rejection_source_file ON ingest_rejection (source_file_id)`
* `llm_usage`: `CREATE INDEX idx_llm_usage_model ON llm_usage (model_id)`
* `llm_usage`: `CREATE INDEX idx_llm_usage_parse_run ON llm_usage (parse_run_id)`
* `nomenclature_alias`: `CREATE UNIQUE INDEX idx_nomenclature_alias_industry_text
    ON nomenclature_alias (industry_tag, alias_text)
    WHERE layer = 'industry'`
* `nomenclature_alias`: `CREATE INDEX idx_nomenclature_alias_leaf ON nomenclature_alias (leaf_path)`
* `nomenclature_alias`: `CREATE UNIQUE INDEX idx_nomenclature_alias_mandate_text
    ON nomenclature_alias (mandate_id, alias_text)
    WHERE layer = 'mandate_soft'`
* `nomenclature_alias`: `CREATE UNIQUE INDEX idx_nomenclature_alias_spine_text
    ON nomenclature_alias (alias_text)
    WHERE layer = 'spine'`
* `nomenclature_binding`: `CREATE INDEX idx_nomenclature_binding_cell ON nomenclature_binding (cell_id)`
* `nomenclature_binding`: `CREATE INDEX idx_nomenclature_binding_parse_run ON nomenclature_binding (parse_run_id)`
* `nomenclature_binding`: `CREATE INDEX idx_nomenclature_binding_path ON nomenclature_binding (path)`
* `nomenclature_node`: `CREATE UNIQUE INDEX idx_nomenclature_node_industry_path
    ON nomenclature_node (industry_tag, path)
    WHERE layer = 'industry'`
* `nomenclature_node`: `CREATE INDEX idx_nomenclature_node_mandate ON nomenclature_node (mandate_id)`
* `nomenclature_node`: `CREATE UNIQUE INDEX idx_nomenclature_node_mandate_path
    ON nomenclature_node (mandate_id, path)
    WHERE layer = 'mandate_soft'`
* `nomenclature_node`: `CREATE INDEX idx_nomenclature_node_parent ON nomenclature_node (parent_path)`
* `nomenclature_node`: `CREATE UNIQUE INDEX idx_nomenclature_node_spine_path
    ON nomenclature_node (path)
    WHERE layer = 'spine'`
* `packet_disposition`: `CREATE INDEX idx_packet_disposition_candidate ON packet_disposition (candidate_id)`
* `packet_disposition`: `CREATE INDEX idx_packet_disposition_parse_run ON packet_disposition (parse_run_id)`
* `provenance`: `CREATE INDEX idx_provenance_entity           ON provenance (entity_type, entity_id)`
* `provenance`: `CREATE INDEX idx_provenance_source_file      ON provenance (source_file_id)`
* `review_queue`: `CREATE INDEX idx_review_queue_parse_run      ON review_queue (parse_run_id)`
* `run_stat`: `CREATE INDEX idx_run_stat_parse_run ON run_stat (parse_run_id)`
* `run_timing`: `CREATE INDEX idx_run_timing_parse_run ON run_timing (parse_run_id)`
* `run_timing`: `CREATE INDEX idx_run_timing_stage ON run_timing (stage)`
* `workbook`: `CREATE INDEX idx_workbook_source_file        ON workbook (source_file_id)`
