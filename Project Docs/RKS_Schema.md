# RKA & BFP Workspace Database — Schema and Query Guide for Natural-Language Querying

This guide lets an LLM with no other context answer questions about one SQLite database, `RKS_workspace.db`, by writing read-only SQL, and by reading the prose the database holds about each region. Read sections 1 to 6 before writing SQL. Sections 7 and 8 give the join map and the allowed values. Section 9 describes every table. Section 10 has the query recipes, each run against this database. Section 11 lists the data's known gaps. Appendices A to D are generated from the database: the full DDL, the nomenclature catalogue, the binding paths, and the worksheet list.

---

## 1. What this database is

| Item | Value |
|---|---|
| Engine | SQLite 3 (use the `sqlite3` dialect; no vendor-specific functions) |
| File | `RKS_workspace.db`, supplied alongside this guide. Open it from wherever it is saved; the guide does not depend on any other file or folder |
| Source workbook | `Projection_RKA & BFP.xlsx` (file type `fm_xlsx`, Microsoft Excel 16.0300; 22 visible worksheets; 25,898 occupied cells) |
| Source file hash (sha256) | `0d17ac19dbafc02bc81d59aea2d6249c28eaf5196d04a8ef0ca245ed542d2675` |
| Mandate | `mandate_id = 1`. Industry `solar_power`, inferred and not confirmed (`mandate_industry.confirmed = 0`) |
| Parse run | `parse_run_id = 1`, status `success`. This file holds one parse run |
| Access mode | Read-only. Never issue `INSERT`, `UPDATE`, `DELETE`, `DROP`, `ALTER`, `VACUUM`, or `PRAGMA` writes |

The database holds three kinds of information about the same workbook:

1. **Cell graph (the raw facts):** every occupied cell with its value, formula, style, merge and hidden flags, and every formula reference. This is exact.
2. **Structure and meaning (derived):** candidate regions on each sheet, with a layout role, a schedule family, triage and prose per region; a controlled-vocabulary path for each cell (for example `Balance Sheet > Total Debt`); an amount role; and a numeric reading for each numeric cell (kind, scale, unit, currency, absolute amount).
3. **Operations:** the parse run, stage timing, LLM usage and cost, run counters, provenance links, and audit events.

Layer 2 is machine-derived. Where a column can be empty or ambiguous, this guide says so. Do not present a Layer 2 answer as verified fact without saying how it was derived.

---

## 2. Table groups by layer

| Group | Tables |
|---|---|
| Source and workbook | `source_file`, `workbook`, `worksheet`, `worksheet_column`, `external_link`, `ingest_rejection` |
| Cells and formulas | `cell`, `cell_style`, `cell_comment`, `cell_reference`, `formula_link`, `formula_gap`, `provenance`, `review_queue` |
| Regions and prose | `candidate`, `candidate_member`, `candidate_related`, `packet_disposition`, `region_triage_opinion`, `region_header_geometry` |
| Meaning of cells | `nomenclature_node`, `nomenclature_alias`, `nomenclature_binding`, `mandate_industry`, `project_fact_field`, `cell_reading`, `cell_unit_basis`, `cell_scale_inference`, `cell_interpretation`, `cell_interpretation_evidence` |
| Operations | `parse_run`, `audit_log`, `run_stat`, `run_timing`, `llm_usage`, `schema_migration`, `schedule_family` |

Each table is described in section 9.

---

## 3. Glossary (use these meanings exactly)

- **Parse run**: the single run that produced this database. Every per-run table has `parse_run_id`; it is always `1` here. Filter on it anyway.
- **Worksheet**: one sheet of the workbook. The name is stored as in the file. Match with `TRIM(sheet_name)` to be safe.
- **Cell**: one occupied cell. `coord` is its A1 address (for example `Q92`); `row_num` and `col_num` are 1-based. `cell_id` is unique across the file.
- **Formula cell**: a cell with `formula_text`. `cached_value` is the result stored in the file for that formula.
- **Formula link (precedent edge)**: `formula_link(from_cell_id, to_cell_id)` means the formula in `from_cell_id` reads `to_cell_id`. Edges are direct. Precedents of a cell (everything it is calculated from, directly or through other formulas) need a recursive query (R4). Dependents (what reads a cell) join on `to_cell_id` (R5).
- **Cell reference**: one reference token inside a formula (`cell_reference`), of kind `local_cell`, `local_range`, `cross_sheet_cell` or `cross_sheet_range`.
- **Candidate**: a region of one worksheet. Kinds: `coverage_parent` (one per worksheet, covering every cell of that sheet) and `child` (a narrower region). Its meaning comes from its prose (section 5).
- **Structural role** (`candidate.structural_role`, children only): `main` (a schedule or table to model), `helper` (a continuation fragment, breakout, reconciliation check or side pad), `scratch` (floating debris). Coverage parents have no role.
- **Residual region**: a candidate with `structural_confidence = 0.5` and the rationale "residual cells no model region claimed". It holds cells that no schedule claims and has no prose (section 11).
- **Layer A (`packet_disposition`)**: one row per child region, with its schedule family, triage, relevance, row labels, column headers, default heading, `about` paragraph and any stated scale.
- **Schedule family** (`packet_disposition.schedule_family`): `assumptions`, `capex_detail`, `means_of_finance`, `profit_and_loss`, `balance_sheet`, `cash_flow`, `project_summary`.
- **Nomenclature binding** (`nomenclature_binding.path`): the controlled-vocabulary path of a cell, for example `Means of Finance > Term Loan`. The path sits under one root: `economic` (money lines, quantities, rates, and the labels that name them), `identity` (legal name and related facts), or `frame` (table furniture: blanks, periods, schedule titles, section banners, scale and field marks).
- **Amount role** (`amount_role`): `add` (counts in a line total), `deduct` (subtracted; not in the default total), `total` (a sum head; not in the default total), `helper` (a restatement, driver or check; not in the total). Only economic money cells carry these. To total a line, use `amount_role = 'add'`.
- **Nomenclature layers** (`nomenclature_node.layer`): `spine` (the economic catalogue, 54 nodes), `industry` (5 leaves tagged `hotel`), `mandate_soft` (1 leaf). `leaf = 1` means a binding can use the node directly.
- **Cell reading** (`cell_reading`): the typed meaning of one numeric cell: `kind`, `scale`, `unit`, `currency`, `absolute_amount`, `type_source` (`input` = read from the cell's own labels, `derived` = from its formula), `scale_basis` (`stated` = the workbook says so, `inferred` = worked out), and `refusal` (why no kind was assigned).
- **Cell interpretation** (`cell_interpretation`): one row per cell with its value facts, path, amount role and nomenclature status.
- **Nomenclature status** (`cell_interpretation.nomenclature_status`): `bound` (the cell has a path), `unbound` (no path could be assigned), `not_applicable` (formula-error cells and cells in scratch regions).
- **Interpretation evidence** (`cell_interpretation_evidence`): ordered cues about a cell: its row header, column header, period, basis, currency, scale and unit. Each has a `resolution` (`resolved`, `missing`, `ambiguous`), the source cell it came from, and a `rule_id`.
- **Entity perspective**: which company a sheet reports on: consolidated (RK group and BFP), RKA, or BFP (section 5.5).
- **Money cell**: a numeric cell whose `kind = 'money'`. It has an absolute amount only where its scale is known.

---

## 4. Conventions and rules for writing SQL

### 4.1 Always do these

1. **Read-only.** Only `SELECT` (and `WITH ... SELECT`).
2. **Filter by run.** Use `parse_run_id = 1` on every per-run table (`worksheet`, `candidate`, `packet_disposition`, `cell_reading`, `cell_interpretation`, `cell_interpretation_evidence`, `nomenclature_binding`, `cell_unit_basis`, `cell_scale_inference`, `region_*`, `run_stat`, `run_timing`, `llm_usage`). `cell`, `cell_style`, `cell_reference`, `formula_link` and `cell_comment` are linked to the run through `worksheet`.
3. **Match sheet names with trimming**: `TRIM(w.sheet_name) = 'Schedule_BFP'`.
4. **Join readings with a LEFT JOIN.** Most binding rows are not numeric, and many numeric cells have no binding. Use `LEFT JOIN cell_reading r ON r.cell_id = b.cell_id AND r.parse_run_id = b.parse_run_id`.
5. **Cast absolute amounts.** `cell_reading.absolute_amount` is TEXT. Use `CAST(r.absolute_amount AS REAL)`.
6. **Respect the scale.** `cell.numeric_value` is in the cell's own scale. Rupee amounts are in `absolute_amount`. Where it is NULL the scale is unknown: report the figure unscaled and say so.
7. **Use `UNION`, not `UNION ALL`, in recursive queries.** The formula graph contains loops.
8. **Say how a number was derived.** Name the layer (cell graph, cell reading, binding, prose) and the filter used.

### 4.2 Scale multiplier

| `scale` | Multiplier | Where it occurs |
|---|---|---|
| `unit` | 1 | rupees; 28 money cells |
| `million` | 1,000,000 | 13,270 money cells (the usual scale of money here) |
| `crore` | 10,000,000 | 227 money cells |
| `lakh` | 100,000 | 2 rate cells only |
| `thousand`, `billion` | 1,000 / 1,000,000,000 | not present |

`absolute_amount = numeric_value × multiplier` for all 13,525 money cells that carry an absolute amount. The 631 money cells with a NULL scale have no absolute amount. They are not zero and not rupees.

### 4.3 Which candidate to use

- For **which region a cell belongs to and what the region is**, use `candidate_member` joined to a `child` candidate, then `packet_disposition` on `candidate_id`.
- The `coverage_parent` (one per sheet) has no `packet_disposition` row. Do not use it to describe a schedule.
- A cell can belong to several candidates: `candidate_member` has 51,781 rows for 25,898 cells. When you need one region, prefer the child with `structural_role = 'main'`, then `helper`, never `scratch`. Say which you used.
- Do not total cells in `helper` regions. They hold continuation fragments and reconciliation checks (section 5.2).

### 4.4 Looking up a sheet-qualified coordinate

```sql
SELECT c.*
FROM cell c
JOIN worksheet w ON w.worksheet_id = c.worksheet_id
WHERE TRIM(w.sheet_name) = 'Schedule_BFP' AND c.coord = 'Q92';
```

Empty cells have no row. A formula that refers to an empty cell resolves to nothing (`cell_reference.resolved_cell_id` is NULL).

### 4.5 Which columns hold a row's labels

Tables can sit side by side on one sheet, and each can have its own header rows and label columns. So a cell's row labels depend on the region it belongs to, not on the sheet:

1. Find the child region that holds the cell (section 4.3).
2. Read that region's geometry in `region_header_geometry.geometry_json`: `bands` (its header rows, as A1 ranges) and `rowLabelColumns` (the columns that hold its row labels).
3. If `rowLabelColumns` is empty, the region has no labels of its own. Its rows take the labels of the region to its left on the same row. This is the case for a movement block beside a balance table that describes the same lines.
4. If the region has no geometry row (30 helper and 6 scratch regions), read the leftmost text in columns A to D as a fallback.

Text in an annotation column (`annotationColumns`) is a tag, not a label. On `Schedule_BFP`, column T carries categories such as `Borrowing` and column O markers such as `Linked`.

---

## 5. Prose: what the model wrote and how to use it

The database holds free text written by an LLM from each region's labels and layout. Quote it as the model's description. Check any number against its cells.

### 5.1 The prose fields

| Field | Table | What it holds | Typical length |
|---|---|---|---|
| `explanation` | `candidate` (children) | One line prefixed with the role (`LLM region (main)`, `(helper)` or `(scratch)`), then a summary. Example: `LLM region (main) Consolidated operating statement: Core operating statement presents consolidated and entity schedules through net profit.` | one sentence |
| `about` | `packet_disposition` | A short paragraph: what the region is, its function, and how its amounts should be used | 71 to 233 characters (average 126) |
| `packet_default_head` | `packet_disposition` | The schedule heading as it appears on the sheet, for example `FORM-II: OPERATING STATEMENT`, `Balance Sheet`, `Dashboard of Key Financial Numbers (Historical and Projected)`. Regions of one schedule share it | a heading |
| `row_labels` | `packet_disposition` | JSON array of the row labels in the region | JSON array |
| `column_headers` | `packet_disposition` | JSON array of the column headers, including periods (for example `Mar '23`, `FY 23`, `Year 2025`) | JSON array |
| `source_text` | `cell_interpretation_evidence` | The exact text a cell's meaning came from: a row label, column header, period, unit or scale cue | the cue |

Two things do not exist:

- **No prose for formulas.** `cell_interpretation.formula_gloss` is empty for every row. A formula's meaning comes from its text (`formula_text`), its precedents (R4), and the labels of the cells it reads.
- **No prose for residual regions.** The 10 residual candidates have a rationale but no `packet_disposition` row and no description (section 11).

### 5.2 What each structural role means

- `main`: the schedule or table to model. Its `about` text describes its function and its amounts. Use these first.
- `helper`: a continuation, breakout or check. Many explanations say so directly. Example: `LLM region (helper) BFP reconciliation differences: Formulas subtract consolidated amounts from BFP amounts, making these variance checks rather than model outputs.` Read the explanation before using a helper, and do not total it.
- `scratch`: floating debris. Omit.

Triage is a separate judgement: `packet_disposition.triage` (`main`, `scratch`, `orphan`). `relevance` (`primary`, `supporting`, `noise`) says how much a region matters. Use `primary` regions for the first answer to a schedule question. `noise` regions are checks or stray cells.

### 5.3 How to read a region

1. Find the regions on the sheet (R7) and read `packet_default_head` and `about`.
2. Read the region's `row_labels` (R18) to see which lines it holds.
3. Read its `column_headers` for periods and units.
4. Then read the cells. The prose names the schedule; the cells hold the numbers.

### 5.4 The schedule families

Counts are of the 107 classified child regions:

| Family | Regions | Typical content |
|---|---|---|
| `balance_sheet` | 30 | Balance sheet forms: assets, liabilities, net worth, debt |
| `assumptions` | 26 | Operating and financing assumptions: trains, capacity, rates, days, periods |
| `profit_and_loss` | 16 | Operating statement and its ratio continuations |
| `means_of_finance` | 16 | Term loans, working capital, promoter capital, debt by lender |
| `project_summary` | 12 | Key-financial dashboards and project facts (installed capacity, manpower) |
| `cash_flow` | 5 | Cash flow from operations, investing and financing |
| `capex_detail` | 2 | Project cost detail |

### 5.5 Entity perspective (which company a sheet reports on)

No column holds the entity. Read it from the sheet name and its header text (recipe R24):

| Perspective | Sheets (header text that states it) |
|---|---|
| Consolidated (RK group and BFP) | `1_Key Ratio_RK Group_Consol` ("Consolidated (RK And BFP)"), `1.2_PL_RK Group_Consol`, `1.3_LIABILITY_RK Group_Consol`, `1.4_ASSET_RK Group_Consol` (each "Consolidated"), `Cash Flow` ("Consolidated (RK And BFP)") |
| RKA | `1.2_PL_RKA` ("RKA"), `1.3_LIABILITY_RKA`, `1.4_ASSET_RKA`, `1_Key Ratio_RKA`, `1.1_Assumption sheet_RKA`, `KFI- RKA` |
| BFP | `2_Key Ratio_BFP` ("BFP"), `2.1_Assumption sheet_BFP`, `2.2_PL_BFP`, `2.3_LIABILITY_BFP`, `2.4_ASSET_BFP`, `KFI BFP` (label "KFI- BFP") |
| Mixed or unclear | `Schedule_RK`, `Schedule_BFP`, `Summary-Debt type wise`, `Debt-profile Bank wise` (labels "RKAHPL" and "BFP,RKA"), `LF` |

`1.1_Assumption sheet_RKA` also contains BFP cues (for example `Inter Company Sales from RKA to BFP`), so its name alone is not a complete entity filter. For mixed sheets, check the labels before attributing figures to one company.

### 5.6 Project identity facts

Identity facts are bound to the cells that state them (`path_root = 'identity'`):

- Legal name `RK Associates & Hoteliers Pvt Ltd.` (also written `A/C:RK Associates & Hoteliers Pvt Ltd.` on the liability and asset sheets): 14 cells.
- Partners or promoters: 6 cells.
- Installed capacity (`Project > Installed Capacity`): 172 cells.
- Manpower: 2 cells.
- Address, constitution, site or location, and power connection: no bound cells.

---

## 6. Strategy for fetching and analysing data

1. **Start at the region, not the cell.** For a schedule question, find its `primary` `main` regions (R20), read `packet_default_head` and `about`, then open the cells.
2. **Use `main` regions for answers; use `helper` regions only to explain.** A helper can explain a total (for example, that it is a reconciliation). It must not be added to a total.
3. **Total from the binding, and report coverage.** For a line total, use the economic path, `amount_role = 'add'`, `kind = 'money'`, and a non-NULL `absolute_amount`. State how many add cells were excluded for lacking an absolute amount (R3, R14).
4. **Search labels in two places.** For a wording you do not know, search `cell.text_value` (R12), then check which region the match sits in and whether that column is one of its label columns (section 4.5). Also search `packet_disposition.row_labels` with `json_each` (R19). A hit in the prose is a lead, not a value: confirm it in the cells.
5. **Read row labels from the region's own label columns** (section 4.5), falling back to columns A to D only where the region has no geometry row.
6. **Use period evidence carefully.** For a year, filter `cell_interpretation_evidence.role = 'period'` on `normalized_value` (R10). A `calendar_year_header` cue is reliable. A `context_conflict` cue (`resolution = 'ambiguous'`, no normalised value) is not. Do not infer a year from a column's position.
7. **Read a formula with its precedents.** Use `formula_text` (R2), the precedents (R4) and the labels of those precedents. There is no formula prose.
8. **Attribute figures to a company only by sheet perspective** (section 5.5). Do not add consolidated, RKA and BFP figures together.
9. **Keep results small.** Aggregate first (counts and sums by path or sheet), then pull rows, with `LIMIT` on any query returning cells.
10. **Report exclusions.** Say how many cells were left out of a total and why: no absolute amount, not `add`, helper or scratch region, error cell, or unbound.
11. **Do not fill gaps from prose.** A value that appears only in an `about` or `explanation` sentence is not a cell value. Find the cell, or say the value is not in the database.

---

## 7. Join map

```
source_file ──< workbook
     │
     └──< parse_run ──< worksheet ──< worksheet_column
                           │
                           ├──< cell ──< cell_comment
                           │      │  └── style_id ──> cell_style
                           │      ├──< cell_reference (from_cell_id) ──> cell (resolved_cell_id)
                           │      ├──< formula_link (from_cell_id → to_cell_id, both cell)
                           │      ├──< candidate_member >── candidate
                           │      ├──< cell_reading (parse_run_id, cell_id)
                           │      ├──< cell_unit_basis (parse_run_id, cell_id)
                           │      ├──< cell_scale_inference (parse_run_id, cell_id)
                           │      ├──< cell_interpretation (parse_run_id, cell_id)
                           │      │       └──< cell_interpretation_evidence (parse_run_id, cell_id)
                           │      └──< nomenclature_binding (parse_run_id, cell_id) >── candidate
                           │
                           └──< candidate (worksheet_id)
                                   ├──< candidate_member
                                   ├── parent_candidate_id ──> candidate
                                   ├──< packet_disposition (candidate_id)
                                   ├──< region_header_geometry (candidate_id)
                                   └──< region_triage_opinion (candidate_id)

nomenclature_node (path)  <── nomenclature_binding.path (text match, no FK)
nomenclature_alias (alias_text → leaf_path)   maps sheet wording to a path (no FK)
provenance (entity_type='cell', entity_id=cell_id, parse_run_id) → location "Sheet!A1"
project_fact_field (path)  <── nomenclature_binding.path
```

Canonical keys:

- Sheet and coordinate: `worksheet.sheet_name` (trimmed) and `cell.coord`.
- Cell to its meaning: `cell.cell_id` = `cell_reading.cell_id` = `cell_interpretation.cell_id` = `nomenclature_binding.cell_id`, with the same `parse_run_id`.
- Cell to region: `candidate_member.cell_id`, then `candidate.candidate_id` = `packet_disposition.candidate_id`.

---

## 8. Allowed values

| Column | Allowed values | Meaning | Rows in this DB |
|---|---|---|---|
| `worksheet.sheet_state` | `visible`, `hidden`, `veryHidden` | Visibility in the file | all 22 `visible` |
| `cell.value_type` | `number`, `text`, `date`, `empty`, `error` | Typed value category (`date` is format-based, section 11) | number 17,839; empty 4,721; text 3,026; error 288; date 24 |
| `cell.is_error` / `error_type` | 0/1; Excel error text | Formula error cells | 288: `#REF!` 287, `#DIV/0!` 1 |
| `cell.value_source` | `cell`, `merged_anchor` | Where the displayed value comes from in a merge | 99 merged anchors |
| `cell_reference.ref_kind` | `local_cell`, `local_range`, `cross_sheet_cell`, `cross_sheet_range` | Kind of reference | local_cell 17,201; cross_sheet_cell 6,179; local_range 3,285; cross_sheet_range 72 |
| `candidate.candidate_kind` | `coverage_parent`, `child` (others allowed by schema) | Region kind | coverage_parent 22; child 108 |
| `candidate.structural_role` | `main`, `helper`, `scratch`, NULL | Role (children only) | main 34; helper 66; scratch 8 |
| `candidate.structural_confidence` | 0.9, 1.0, 0.5 | Confidence (and rationale) | 0.9 `llm region layout` 98; 1.0 coverage parents 22; 0.5 `residual cells no model region claimed` 10 |
| `packet_disposition.triage` | `main`, `scratch`, `orphan` | Triage of the region | main 90; scratch 10; orphan 7 |
| `packet_disposition.relevance` | `primary`, `supporting`, `noise` | How much the region matters | primary 46; supporting 44; noise 17 |
| `packet_disposition.schedule_family` | see section 5.4 | Schedule family | 107 rows |
| `packet_disposition.stated_scale` | NULL, `unit`, `thousand`, `lakh`, `million`, `crore`, `billion` | Scale the region's labels state | none 52; million 34; crore 18; lakh 3 |
| `region_triage_opinion.choice` | `main`, `scratch`, `orphan` | Triage opinion | main 86; scratch 22 |
| `nomenclature_binding.path_root` | `economic`, `identity`, `frame` | Root of the path | economic 18,959; frame 5,623; identity 20 |
| `nomenclature_binding.amount_role` | `add`, `deduct`, `total`, `helper`, NULL | Role in the economic rollup | economic: add 7,264, total 1,196, helper 5,091, NULL 5,408; frame and identity NULL |
| `nomenclature_node.layer` | `spine`, `industry`, `mandate_soft` | Catalogue layer | spine 54 (14 frozen); industry 5 (tag `hotel`); mandate_soft 1 |
| `cell_reading.kind` | `money`, `quantity`, `rate`, `percent`, `count`, `ratio`, NULL | Kind of numeric cell | money 14,156; percent 1,066; ratio 516; quantity 297; count 172; rate 57; NULL 1,575 |
| `cell_reading.scale` | `unit`, `million`, `crore`, `lakh`, NULL | Scale | money: million 13,270; crore 227; unit 28; NULL 631 |
| `cell_reading.type_source` | `input`, `derived`, NULL | Read from own labels, or from its formula | derived 10,966; input 5,298; NULL 1,575 |
| `cell_reading.scale_basis` | `stated`, `inferred`, NULL | Whether the workbook states the scale | stated 8,730; inferred 4,795; NULL 4,314 |
| `cell_reading.refusal` | `untypable`, `kind_conflict`, NULL | Why no kind was assigned | untypable 271; kind_conflict 88; NULL 17,480 |
| `cell_unit_basis.basis` | `stated`, `inferred` | Whether the labels state the unit | stated 163; inferred 91 |
| `cell_interpretation.value_origin` | `literal`, `formula` | Literal number or formula | formula 15,405; literal 10,493 |
| `cell_interpretation.result_source` | `literal`, `formula_cache`, `missing_cache` | Where the value came from | formula_cache 15,405; literal 10,493 |
| `cell_interpretation.nomenclature_status` | `bound`, `unbound`, `not_applicable` | Path coverage | bound 24,602; unbound 1,000; not_applicable 296 (288 error cells, 8 scratch-region cells) |
| `cell_interpretation_evidence.role` | `row_header`, `column_header`, `period`, `basis`, `currency`, `scale`, `unit` | What the cue is | 113,311 rows |
| `cell_interpretation_evidence.resolution` | `resolved`, `missing`, `ambiguous` | Cue state | row_header 30,007 / 7,751 / 430; column_header 58,216 / 1,081 / 0; period 8,119 / 0 / 1,228; basis 5,379 / 0 / 4; currency 256; scale 577; unit 263 (resolved / missing / ambiguous) |
| `cell_interpretation_evidence.rule_id` | `llm_geometry`, `nearest_in_scope`, `calendar_year_header`, `context_conflict`, `candidate_overlap_conflict`, `basis_token`, `unit_token`, `currency_token`, `scale_token`, `formula_divisor_1e6`, NULL | Rule that produced the cue | calendar_year_header 8,119 (period); context_conflict 1,228 (period, ambiguous) |
| `llm_usage.stage` | `region-layout`, `layer-a`, `layer-b` | Pipeline stage that made the calls | 8 rows |

---

## 9. Table reference

Row counts are for this database. Unlisted columns are clear from their names.

### 9.1 Source and run metadata

**`source_file`**: the ingested file. 1 row. `file_name`, `file_hash`, `file_type`, `mandate_id`, `raw_metadata`.

**`parse_run`**: the pipeline execution. `parse_run_id = 1`, `status` (`success`), `metrics` (JSON: file name, hash, row and cell counts), `warnings`, `errors`.

**`workbook`**: 1 row. `application_name` (Microsoft Excel), `application_version` (16.0300), `sheet_count` (22), `calculation_mode` (`auto`), `calc_is_circular` (0), `error_cell_count` (288).

**`external_link`** and **`ingest_rejection`**: 0 rows. **`schema_migration`**: 44 rows (not needed for business questions).

**`audit_log`**: 2 rows (`parse_run_started`, `parse_run_completed`).

### 9.2 Workbook structure and cells

**`worksheet`**: one row per sheet, 22 rows. `sheet_name`, `sheet_index` (0-based), `sheet_state`, `bbox_*` (occupied area, 1-based), `dimensions_declared`, `real_content_rows`, `declared_merged`. Appendix D lists them with cell counts.

**`worksheet_column`**: declared column widths, keyed by (`worksheet_id`, `col_num`).

**`cell`**: every occupied cell. 25,898 rows; key `cell_id`.
- `worksheet_id`, `coord`, `row_num`, `col_num`.
- `raw_value` (value as stored; formula text for formulas), `raw_type`, `value_type` (section 8).
- `text_value` (text cells), `display_value` (as shown), `numeric_value` (numeric and numeric-formula cells), `bool_value`, `date_value`.
- `formula_text` (15,405 formula cells), `formula_normalized`, `cached_value` (the stored result), `cache_state`.
- `is_error`, `error_type`.
- `is_merged_anchor`, `is_merged_participant`, `merged_range`, `value_source`.
- `row_hidden`, `col_hidden`, `sheet_hidden`: 4,202 cells are in hidden rows and 793 in hidden columns; no sheet is hidden.
- `style_id` → `cell_style`.

**`cell_style`**: 586 appearance records (fonts, fills, borders, `number_format`).

**`cell_comment`**: 8 notes (`author`, `body`).

**`provenance`**: one row per cell, giving its source `location` (for example `Schedule_BFP!Q92`) and `raw_value`.

**`cell_reference`**: every reference token in every formula, 26,737 rows. `from_cell_id`, `token_index`, `raw_token`, `ref_kind`, `target_sheet_name`, `target_range`, `resolved_cell_id` (NULL for ranges), `abs_row`, `abs_col`, `row_offset`, `col_offset`.

**`formula_link`**: direct precedent edges, 72,788 rows across 14,605 formula cells. A range is expanded into one edge per cell it contains. The graph contains loops (section 11).

**`formula_gap`**: 0 rows. **`review_queue`**: 0 rows. **`external_link`**: 0 rows.

### 9.3 Regions and prose

**`candidate`**: 130 rows: 22 coverage parents and 108 children (main 34, helper 66, scratch 8). Columns: `worksheet_id`, `candidate_kind`, `parent_candidate_id`, `bbox_*`, `structural_role`, `structural_confidence`, `structural_confidence_rationale`, `explanation` (section 5.1).

**`candidate_member`**: which cells belong to which candidate. 51,781 rows; covers all 25,898 cells.

**`candidate_related`**: 0 rows.

**`region_triage_opinion`**: the triage opinion per region. 108 rows. `model`, `choice`, `confidence`, `p_main`, `p_scratch`, `p_orphan`, `escalated`.

**`region_header_geometry`**: header bands and label columns per region. 72 rows: all 34 main regions, 36 of 66 helper regions and 2 of 8 scratch regions. `geometry_json` holds `bands`, `rowLabelColumns`, `annotationColumns`, `groupColumns`.

**`packet_disposition`**: one row per child region, 107 rows. `schedule_family`, `triage`, `relevance`, `row_labels` (JSON), `column_headers` (JSON), `packet_default_head`, `about`, `stated_scale`, `scale_evidence_cell` (the cell that states the scale, for example `AC2`).

### 9.4 Nomenclature and meaning of cells

**`nomenclature_node`**: the catalogue of paths, 60 rows (spine 54, industry 5, mandate_soft 1). Columns: `path`, `name`, `parent_path`, `layer`, `frozen`, `leaf`, `industry_tag` (`hotel` on the five industry nodes). Appendix B lists them.

**`nomenclature_alias`**: sheet wording mapped to a catalogue leaf. 57 rows: `alias_text`, `leaf_path`.

**`mandate_industry`**: 1 row, `industry_tag = 'solar_power'`, `confirmed = 0`.

**`project_fact_field`**: 8 project facts tracked as catalogue paths (legal name, constitution, partners, address, site, installed capacity, power connection, manpower). Section 5.6 gives the bound counts.

**`schedule_family`**: 0 rows. The family names appear as text in `packet_disposition.schedule_family`.

**`nomenclature_binding`**: one row per bound cell, 24,602 rows. Columns: `candidate_id`, `path_root`, `path`, `amount_role`, `verbatim` (the text the path was matched to).

**`cell_interpretation`**: one row per cell, 25,898 rows. Columns: `value_origin`, `resulting_value`, `result_source`, `formula_text`, `nomenclature_path`, `amount_role`, `nomenclature_status`, `formula_gloss` (empty for every row), `unbound_reason` (NULL for every row).

**`cell_interpretation_evidence`**: ordered cues behind each cell's meaning, 113,311 rows. Columns: `cell_id`, `role`, `source_cell_id`, `source_text`, `ordinal`, `resolution`, `normalized_value`, `rule_id`. For a period, `normalized_value` is a calendar year from 2022 to 2031 (2025 is the most common); NULL means ambiguous.

**`cell_reading`**: the typed meaning of each numeric cell, 17,839 rows. Columns: `kind`, `scale`, `unit`, `currency`, `absolute_amount` (TEXT; money with a known scale only), `type_source`, `scale_basis`, `refusal`.
- `unit` values: `nos` 180, `days` 83, `month` 19, `trains` 10, `train` 10, `months` 7, `per train` 4, `train-months` 2, `year` 1; most cells have no unit (17,523).
- `currency`: `INR` on 1,432 cells; most have none.
- 1,216 numeric cells have neither a kind nor a refusal; they are untyped.

**`cell_unit_basis`**: the unit of a cell and whether the labels state it. 254 rows: `unit`, `basis`, `evidence` (the label it came from).

**`cell_scale_inference`**: money cells whose scale was worked out rather than stated. 1,042 rows: `scale` (`million` 876, `crore` 140, `unit` 26), `evidence`.

### 9.5 Operations

**`llm_usage`**: calls, tokens and cost per pipeline stage and model. 8 rows (R13).

**`run_timing`**: duration per pipeline stage, 5 rows (R15).

**`run_stat`**: 65 counters for the run. Columns: `stage`, `name`, `value_num` or `value_text`.

**`sqlite_sequence`**: internal SQLite bookkeeping. Do not query it.

---

## 10. Query recipes

Each recipe was run against this database. Replace `1` with the parse run id if you need another run.

### R1 — List the worksheets

```sql
SELECT worksheet_id, TRIM(sheet_name) AS sheet, sheet_index, sheet_state, real_content_rows
FROM worksheet
WHERE parse_run_id = 1
ORDER BY sheet_index;
```

### R2 — Everything about one cell (value, meaning, amount)

```sql
SELECT c.coord, c.raw_value, c.numeric_value, c.formula_text,
       ci.nomenclature_path, ci.amount_role, ci.nomenclature_status,
       r.kind, r.scale, r.unit, r.absolute_amount
FROM cell c
JOIN worksheet w ON w.worksheet_id = c.worksheet_id
LEFT JOIN cell_interpretation ci ON ci.cell_id = c.cell_id AND ci.parse_run_id = w.parse_run_id
LEFT JOIN cell_reading r ON r.cell_id = c.cell_id AND r.parse_run_id = w.parse_run_id
WHERE w.parse_run_id = 1
  AND TRIM(w.sheet_name) = 'Schedule_BFP' AND c.coord = 'Q92';
```

Result: `Q92` is `=H92-G92` (1,949.068 million), path `Balance Sheet > Total Debt`, amount role `add`, kind `money`, scale `million`, absolute amount 1,949,068,209.66.

### R3 — Sum of rupee amounts on a line

```sql
SELECT b.path,
       TRIM(w.sheet_name) AS sheet,
       COUNT(*) AS cells,
       ROUND(SUM(CAST(r.absolute_amount AS REAL)) / 1e7, 2) AS crore
FROM nomenclature_binding b
JOIN cell c ON c.cell_id = b.cell_id
JOIN worksheet w ON w.worksheet_id = c.worksheet_id AND w.parse_run_id = b.parse_run_id
JOIN cell_reading r ON r.cell_id = b.cell_id AND r.parse_run_id = b.parse_run_id
WHERE b.parse_run_id = 1
  AND b.amount_role = 'add'
  AND r.kind = 'money'
  AND r.absolute_amount IS NOT NULL
  AND b.path LIKE 'Means of Finance%'
  -- Scope to one entity/period before summing. Do not mix consolidated, RKA and BFP.
  -- AND TRIM(w.sheet_name) = '1.1_Assumption sheet_RKA'
GROUP BY b.path, TRIM(w.sheet_name)
ORDER BY b.path, sheet;
```

Result: `Means of Finance > Term Loan` 783 cells, 3,983.86 crore; `> Working Capital Assistance` 525 cells, 1,524.30 crore; `> Capex` 66 cells, 33.53 crore; `> Promoter / Partners' Capital` 6 cells, 300.00 crore; `> Unsecured Loans` 41 cells, 118.30 crore. Divide by `1e7` for crore, `1e5` for lakh, `1` for rupees. Report how many money cells had no absolute amount.

### R4 — All precedents of a cell (recursive)

```sql
WITH RECURSIVE p(cid) AS (
  SELECT fl.to_cell_id
  FROM formula_link fl
  JOIN cell c ON c.cell_id = fl.from_cell_id
  JOIN worksheet w ON w.worksheet_id = c.worksheet_id
  WHERE w.parse_run_id = 1
    AND TRIM(w.sheet_name) = 'Schedule_BFP' AND c.coord = 'Q92'
  UNION
  SELECT fl.to_cell_id FROM formula_link fl JOIN p ON fl.from_cell_id = p.cid
)
SELECT COUNT(*) AS precedents FROM p;
```

Result: 2 precedents for `Schedule_BFP!Q92` (`G92` and `H92`). Use `UNION`, not `UNION ALL`.

### R5 — Dependents of a cell (direct)

```sql
SELECT TRIM(w.sheet_name) AS sheet, c.coord, c.formula_text
FROM formula_link fl
JOIN cell c ON c.cell_id = fl.from_cell_id
JOIN worksheet w ON w.worksheet_id = c.worksheet_id
WHERE w.parse_run_id = 1
  AND fl.to_cell_id = (
  SELECT c2.cell_id FROM cell c2
  JOIN worksheet w2 ON w2.worksheet_id = c2.worksheet_id
  WHERE w2.parse_run_id = 1
    AND TRIM(w2.sheet_name) = 'Schedule_BFP' AND c2.coord = 'G92'
);
```

Result: `Schedule_BFP!Q92` (`H92-G92`), `Schedule_BFP!R92`, `Schedule_BFP!G98` (`SUM(G89:G97)`), and `2.3_LIABILITY_BFP!E7` (`Schedule_BFP!G92`).

### R6 — The formula and its references for one formula cell

```sql
SELECT c.formula_text,
       r.token_index, r.raw_token, r.ref_kind, TRIM(r.target_sheet_name) AS target_sheet,
       r.target_range, r.resolved_cell_id
FROM cell_reference r
JOIN cell c ON c.cell_id = r.from_cell_id
JOIN worksheet w ON w.worksheet_id = c.worksheet_id
WHERE w.parse_run_id = 1
  AND TRIM(w.sheet_name) = 'Schedule_BFP' AND c.coord = 'Q92'
ORDER BY r.token_index;
```

### R7 — Regions on a sheet, with schedule family and prose

```sql
SELECT p.candidate_id, c.structural_role, p.schedule_family, p.relevance,
       p.packet_default_head, p.about
FROM packet_disposition p
JOIN candidate c ON c.candidate_id = p.candidate_id
JOIN worksheet w ON w.worksheet_id = c.worksheet_id
WHERE p.parse_run_id = 1 AND c.candidate_kind = 'child' AND TRIM(w.sheet_name) = '1.2_PL_RK Group_Consol'
ORDER BY p.candidate_id;
```

Result: candidate 25 `main`, `profit_and_loss`, `primary` (heading `FORM-II: OPERATING STATEMENT`); candidate 26 `main`, `supporting` (a ratios continuation).

### R8 — Unbound cells by sheet

```sql
SELECT TRIM(w.sheet_name) AS sheet, COUNT(*) AS unbound_cells
FROM cell_interpretation ci
JOIN cell c ON c.cell_id = ci.cell_id
JOIN worksheet w ON w.worksheet_id = c.worksheet_id
WHERE ci.parse_run_id = 1 AND ci.nomenclature_status = 'unbound'
GROUP BY 1
ORDER BY unbound_cells DESC;
```

Result: `1.1_Assumption sheet_RKA` 194; `Schedule_BFP` 96; `2.1_Assumption sheet_BFP` 82. `unbound` means no path was assigned, not that the cell is unimportant.

### R9 — The evidence behind one cell

```sql
SELECT e.ordinal, e.role, e.resolution, e.source_text, e.normalized_value, e.rule_id
FROM cell_interpretation_evidence e
JOIN cell c ON c.cell_id = e.cell_id
JOIN worksheet w ON w.worksheet_id = c.worksheet_id
WHERE e.parse_run_id = 1 AND TRIM(w.sheet_name) = 'Schedule_BFP' AND c.coord = 'Q92'
ORDER BY e.ordinal;
```

Result for `Schedule_BFP!Q92`: row header `Linked` (resolved); column header `Cash Flow` (resolved); period `2025` (resolved); column header `Year 2025` (resolved).

### R10 — Cells for one period (for example 2027) with their path and amount

```sql
SELECT TRIM(w.sheet_name) AS sheet, c.coord, b.path, b.amount_role,
       c.numeric_value AS value_in_cell_scale,
       rd.scale, rd.absolute_amount
FROM cell_interpretation_evidence e
JOIN cell c ON c.cell_id = e.cell_id
JOIN worksheet w ON w.worksheet_id = c.worksheet_id
JOIN nomenclature_binding b ON b.cell_id = c.cell_id AND b.parse_run_id = e.parse_run_id
LEFT JOIN cell_reading rd ON rd.cell_id = c.cell_id AND rd.parse_run_id = e.parse_run_id
WHERE e.parse_run_id = 1 AND e.role = 'period' AND e.normalized_value = '2027'
  AND b.amount_role = 'add'
ORDER BY sheet, c.coord;
```

Result: for example `1.1_Assumption sheet_RKA!F106` (`Balance Sheet > Net Worth`, 50), `F122` (`Project Cost > Plant & Machinery > Kitchen Equipments`, 50), `F126` (`Project Cost > Preliminary & Pre-operative Expenses`, 25).

### R11 — Identity facts

```sql
SELECT TRIM(w.sheet_name) AS sheet, c.coord, c.text_value, b.path
FROM nomenclature_binding b
JOIN cell c ON c.cell_id = b.cell_id
JOIN worksheet w ON w.worksheet_id = c.worksheet_id
WHERE b.parse_run_id = 1 AND b.path_root = 'identity'
ORDER BY b.path, sheet, c.coord;
```

Result: `RK Associates & Hoteliers Pvt Ltd.` on `1.2_PL_RK Group_Consol!B1` and `1.2_PL_RKA!B1` (path `Project Identity > Legal Name`); `A/C:RK Associates & Hoteliers Pvt Ltd.` on the liability and asset sheets.

### R12 — Find row labels by text

```sql
SELECT TRIM(w.sheet_name) AS sheet, c.coord, c.text_value
FROM cell c
JOIN worksheet w ON w.worksheet_id = c.worksheet_id
WHERE w.parse_run_id = 1 AND c.text_value LIKE '%term loan%'
ORDER BY sheet, c.coord;
```

Result: `13.Term Loan (excluding instalments payable within one year)` on `1.3_LIABILITY_RK Group_Consol!B26` and `1.3_LIABILITY_RKA!B26`.

### R13 — LLM usage and cost per stage and model

```sql
SELECT stage, model_id, calls, failed_calls, failovers,
       prompt_tokens, completion_tokens, ROUND(cost_usd, 4) AS cost_usd
FROM llm_usage
WHERE parse_run_id = 1
UNION ALL
SELECT 'TOTAL', '', SUM(calls), SUM(failed_calls), SUM(failovers),
       SUM(prompt_tokens), SUM(completion_tokens), ROUND(SUM(cost_usd), 4)
FROM llm_usage WHERE parse_run_id = 1;
```

Result: total 2,685 calls, 20 failed, USD 0.747.

### R14 — Numeric kinds with scale coverage

```sql
SELECT r.kind, COALESCE(r.scale, '(unknown)') AS scale,
       COUNT(*) AS cells,
       SUM(r.absolute_amount IS NULL) AS without_absolute_amount
FROM cell_reading r
WHERE r.parse_run_id = 1 AND r.kind IS NOT NULL
GROUP BY 1, 2
ORDER BY cells DESC;
```

Result: money `million` 13,270 (all with an absolute amount); money `crore` 227; money `unit` 28; money `(unknown)` 631 (none); percent 1,066, ratio 516, quantity 297, count 172, rate 57 (none with an absolute amount).

### R15 — Stage durations

```sql
SELECT stage, ROUND(duration_millis / 1000.0, 1) AS seconds, item_count
FROM run_timing
WHERE parse_run_id = 1
ORDER BY started_at;
```

Result: ingest 6.4 s (25,898 items); discover 0.5 s (22); region-layout 54.2 s (22); layer-a 92.0 s (107); layer-b 741.7 s (17,839).

### R16 — Scale basis of money cells, with the scale cue

```sql
SELECT TRIM(w.sheet_name) AS sheet, c.coord, r.scale, r.scale_basis,
       e.source_text AS scale_cue
FROM cell_reading r
JOIN cell c ON c.cell_id = r.cell_id
JOIN worksheet w ON w.worksheet_id = c.worksheet_id
LEFT JOIN cell_interpretation_evidence e
       ON e.cell_id = r.cell_id AND e.parse_run_id = r.parse_run_id AND e.role = 'scale'
WHERE r.parse_run_id = 1 AND r.kind = 'money'
ORDER BY sheet, c.coord;
```

Use `scale_basis` to say whether the scale is stated in the workbook (`stated`) or worked out (`inferred`). Many money cells have no scale cue (`scale_cue` NULL).

### R17 — Project facts and their bound cells

```sql
SELECT p.path, COUNT(b.binding_id) AS bound_cells
FROM project_fact_field p
LEFT JOIN nomenclature_binding b ON b.path = p.path AND b.parse_run_id = 1
GROUP BY p.path
ORDER BY p.path;
```

Result: `Project > Installed Capacity` 172; `Project Identity > Legal Name` 14; `Project Identity > Partners / Promoters` 6; `Project > Manpower` 2; the other four fields 0.

### R18 — The prose and labels of one region

```sql
SELECT p.candidate_id, TRIM(w.sheet_name) AS sheet, c.structural_role,
       p.schedule_family, p.relevance, p.packet_default_head,
       c.explanation, p.about, p.row_labels, p.column_headers
FROM packet_disposition p
JOIN candidate c ON c.candidate_id = p.candidate_id
JOIN worksheet w ON w.worksheet_id = c.worksheet_id
WHERE p.parse_run_id = 1 AND p.candidate_id = 25;
```

Result for candidate 25: `profit_and_loss`, `primary`, heading `FORM-II: OPERATING STATEMENT`, and row labels beginning `Revenue from Operation`, `Other Operating income`, `Total Revenue`, `Other Income`, `Cost of Sales`.

### R19 — Regions whose row labels mention a term

```sql
SELECT DISTINCT p.candidate_id, TRIM(w.sheet_name) AS sheet,
       c.structural_role, p.packet_default_head
FROM packet_disposition p
JOIN candidate c ON c.candidate_id = p.candidate_id
JOIN worksheet w ON w.worksheet_id = c.worksheet_id,
     json_each(p.row_labels) j
WHERE p.parse_run_id = 1 AND LOWER(j.value) LIKE '%term loan%'
ORDER BY p.candidate_id;
```

Result: candidates 40 (`Schedule_RK`, `main`), 74 (`1.3_LIABILITY_RKA`, `main`), 91 (`2.3_LIABILITY_BFP`, `main`) and 93 (`2.3_LIABILITY_BFP`, `helper`).

### R20 — Primary main regions by schedule family

```sql
SELECT p.schedule_family, COUNT(*) AS regions,
       GROUP_CONCAT(TRIM(w.sheet_name), '; ') AS sheets
FROM packet_disposition p
JOIN candidate c ON c.candidate_id = p.candidate_id
JOIN worksheet w ON w.worksheet_id = c.worksheet_id
WHERE p.parse_run_id = 1 AND c.structural_role = 'main' AND p.relevance = 'primary'
GROUP BY 1
ORDER BY regions DESC;
```

Result: `balance_sheet` 8 regions; `project_summary` 5; `profit_and_loss` 4; `means_of_finance` 3; `assumptions` 2; `cash_flow` 1.

### R21 — Cells in residual regions, by sheet

```sql
SELECT TRIM(w.sheet_name) AS sheet, COUNT(*) AS cells
FROM candidate_member m
JOIN candidate k ON k.candidate_id = m.candidate_id
JOIN worksheet w ON w.worksheet_id = k.worksheet_id
WHERE w.parse_run_id = 1 AND k.structural_confidence = 0.5
GROUP BY 1
ORDER BY cells DESC;
```

Result: `Debt-profile Bank wise` 80; `1.1_Assumption sheet_RKA` 61; `2.1_Assumption sheet_BFP` 33; `Schedule_RK` 17; `LF` 10; `1.2_PL_RKA` 3. Total 204.

### R22 — Numeric cells with no kind on Schedule_BFP (columns E to H)

```sql
SELECT c.coord, c.numeric_value
FROM cell c
JOIN worksheet w ON w.worksheet_id = c.worksheet_id
LEFT JOIN cell_reading r ON r.cell_id = c.cell_id AND r.parse_run_id = w.parse_run_id
WHERE w.parse_run_id = 1
  AND w.sheet_name = 'Schedule_BFP'
  AND c.col_num BETWEEN 5 AND 8
  AND c.numeric_value IS NOT NULL
  AND r.kind IS NULL
ORDER BY c.row_num, c.col_num;
```

Result: 243 cells, including `H11`, `E26` to `H26` and `E27`. These are the balance inputs that the movement formulas in columns Q to S read.

### R23 — Formula-error cells

```sql
SELECT TRIM(w.sheet_name) AS sheet, c.coord, c.error_type, c.formula_text
FROM cell c
JOIN worksheet w ON w.worksheet_id = c.worksheet_id
WHERE c.is_error = 1
ORDER BY sheet, c.coord;
```

Result: 288 cells, `#REF!` 287 and `#DIV/0!` 1. All have `nomenclature_status = 'not_applicable'`. Example: `1.1_Assumption sheet_RKA!M65` (`'1.4_ASSET_RKA'!#REF!/'1.2_PL_RKA'!#REF!*365`).

### R24 — Entity perspective of each sheet (from header text)

```sql
SELECT TRIM(w.sheet_name) AS sheet,
       GROUP_CONCAT(DISTINCT c.text_value) AS perspective_text
FROM cell c
JOIN worksheet w ON w.worksheet_id = c.worksheet_id
WHERE c.text_value IN ('Consolidated', 'Consolidated (RK And BFP)', 'RKA', 'BFP',
                       'KFI- RKA', 'KFI - RKA', 'KFI- BFP')
GROUP BY 1
ORDER BY 1;
```

Result: matches section 5.5. `Schedule_RK`, `Schedule_BFP` and `LF` have no perspective text.

### R25 — Count of residual regions

```sql
SELECT COUNT(*) AS residual_candidates
FROM candidate
WHERE structural_confidence = 0.5;
```

Result: 10.

---

## 11. Known gaps and data-quality caveats

1. **Label columns differ by region.** Side-by-side tables can have their own label and header columns. Use each region's `rowLabelColumns` (section 4.5). Only 30 helper and 6 scratch regions lack a geometry row; all 34 main regions have one.
2. **A calendar-year header is a period.** `Year 2025` and similar headers give `period` cues (`calendar_year_header`). A duration such as `5 years` is a quantity.
3. **Residual regions have no description.** Ten candidates (confidence 0.5) hold 204 cells that no schedule claims. A further 96 cells belong to no child region at all. Most residual cells are on `Debt-profile Bank wise` (80).
4. **Many numeric cells have no type.** 1,216 numeric cells have neither a kind nor a refusal. 271 are `untypable` and 88 are `kind_conflict`. Do not infer a type for an untyped cell.
5. **Debt balance inputs are often untyped.** 243 numeric cells on `Schedule_BFP` in columns E to H have no kind (R22). The movement formulas in columns Q to S read them, and are typed money in this database.
6. **Many money cells have no absolute amount.** 631 money cells have no known scale (R14). A total over a line includes only the cells whose absolute amount is present, and can understate it. Report the count of excluded cells with any total.
7. **Unbound cells have no recorded reason.** `unbound_reason` is NULL for every row, including the 1,000 unbound cells.
8. **Several project facts have no bound cells.** Address, constitution, site or location, and power connection have none. Search labels (R12, R19) and read the adjacent values; do not claim these facts from the catalogue alone.
9. **Layer A and Layer B values are LLM outputs.** `about`, `explanation`, `packet_default_head`, `schedule_family`, `relevance`, `nomenclature_path`, `amount_role`, and the evidence `normalized_value` are model judgements. Say so when they are the basis of an answer, and point to the source cell so the user can check it.
10. **Cached formula values are the stored results.** `cell.cached_value` and `cell_interpretation.resulting_value` are the values in the file. The database does not recalculate.
11. **The formula graph contains loops.** Use `UNION` in recursive queries.
12. **Some tables are empty.** `formula_gap`, `review_queue`, `external_link`, `ingest_rejection`, `candidate_related`, `schedule_family` have no rows, and `formula_gloss` is empty for every cell. Their absence is not a data finding.
13. **Money scale is mostly `million`.** 13,270 money cells are `million` and 227 are `crore`. Do not assume crore.
14. **`value_type = 'date'` is format-based.** Some date-formatted cells hold ordinary numbers. For amounts and durations use `numeric_value`.
15. **Path and typing come from different sources.** `nomenclature_binding` (the path) and `cell_reading` (the kind and amount) are separate. Query both with LEFT JOINs and say which side is missing.
16. **Entity attribution is by sheet and header text only.** No column holds the entity. The mixed sheets (`Schedule_RK`, `Schedule_BFP`, `Debt-profile Bank wise`, `LF`) need a label check.
17. **The industry layer does not match the mandate.** The five industry nodes are tagged `hotel`, while `mandate_industry` records `solar_power` (inferred, not confirmed). Treat industry-layer paths with suspicion.
18. **The path catalogue is small.** 52 economic paths, 6 frame paths and 2 identity paths are in use. A cost head outside them may be unbound rather than absent. Try the aliases (`nomenclature_alias`) and label search (R12) before saying the data is missing.

---

## 12. Question-to-query guide

| If the user asks about… | Start with | Then |
|---|---|---|
| What sheets exist | `worksheet` (R1) | `candidate` + `packet_disposition` (R7) |
| What a schedule is about | `packet_disposition.packet_default_head` and `about` (R18) | `row_labels` (R19) |
| The value of a specific cell (for example "Q92 on Schedule_BFP") | `cell` (R2) | evidence (R9) |
| A line item's amount (for example "term loan total") | `nomenclature_binding` `path` LIKE `…Term Loan%`, `amount_role = 'add'` (R3) | Coverage from `absolute_amount` (R14) |
| Where a number comes from | `formula_link` precedents (R4), `cell_reference` (R6) | `formula_text` |
| What uses a cell | `formula_link` dependents (R5) | |
| Identity (legal name, partners) | `nomenclature_binding` `path_root = 'identity'` (R11) | `cell.text_value` |
| Project facts (capacity, manpower) | R17 | Labels (R12) and adjacent values |
| Values for a year | evidence `role = 'period'` (R10) | `cell_reading` |
| Currency, units, scales | `cell_reading`, `cell_unit_basis`, `cell_scale_inference` (R14, R16) | evidence |
| Which company a sheet is about | Section 5.5 and R24 | header text |
| Why a cell has no path | `nomenclature_status` (R8) | Error cells (R23), residual regions (R21) |
| Cost and time of the pipeline | `llm_usage` (R13), `run_timing` (R15) | |
| Comments or notes on inputs | `cell_comment` joined to `cell` | |
| Appearance (bold, fill, borders) | `cell_style` via `cell.style_id` | |

---

## 13. Answering rules for the querying LLM

1. Translate the question into SQL that follows section 4. Run it. Read the rows before answering.
2. Report numbers exactly as returned, with their units: rupees, crore, lakh or million. Convert only from `absolute_amount`.
3. State the layer behind the answer (cell graph, cell reading, binding, prose or LLM judgement) and the filters used.
4. If a total excludes cells, say how many and why (no scale, no reading, not `add`, helper or scratch region, error cell).
5. If the answer depends on a label, give the sheet and coordinate so the user can check it.
6. If the data is not in the database, say so. Do not infer values from the catalogue, the prose or the sheet names.
7. Attribute figures to a company only by sheet perspective (section 5.5).
8. Never write to the database.

---

## Appendix A — Full DDL (generated from the database)

Every table and index, exactly as stored in `RKS_workspace.db`.

```sql
CREATE TABLE "audit_log" (
    audit_log_id        INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id        INTEGER REFERENCES parse_run (parse_run_id),
    event_type          TEXT NOT NULL,
    event_at            TEXT NOT NULL,
    payload             TEXT,                       -- JSON object
    severity            TEXT NOT NULL               -- 'info' | 'warning' | 'error'
);
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
    CHECK (structural_role IS NULL OR structural_role IN ('main', 'helper', 'scratch')));
CREATE TABLE candidate_member (
    candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id) ON DELETE CASCADE,
    cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    PRIMARY KEY (candidate_id, cell_id)
);
CREATE TABLE candidate_related (
    candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id) ON DELETE CASCADE,
    related_candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id) ON DELETE CASCADE,
    relationship_kind TEXT NOT NULL,
    PRIMARY KEY (candidate_id, related_candidate_id, relationship_kind),
    CHECK (candidate_id != related_candidate_id)
);
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
, style_id INTEGER REFERENCES cell_style (style_id), formula_normalized TEXT);
CREATE TABLE cell_comment (
    cell_id INTEGER PRIMARY KEY REFERENCES cell (cell_id),
    author TEXT,
    body TEXT NOT NULL
);
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
);
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
);
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
);
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
);
CREATE TABLE cell_scale_inference (
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    scale TEXT NOT NULL,
    evidence TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (parse_run_id, cell_id)
);
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
, font_name TEXT, font_size INTEGER, italic INTEGER CHECK (italic IN (0, 1)), underline TEXT, font_color TEXT);
CREATE TABLE cell_unit_basis (
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    unit TEXT NOT NULL,
    basis TEXT NOT NULL CHECK (basis IN ('stated', 'inferred')),
    evidence TEXT,
    created_at TEXT NOT NULL,
    PRIMARY KEY (parse_run_id, cell_id)
);
CREATE TABLE external_link (
    external_link_id    INTEGER PRIMARY KEY AUTOINCREMENT,
    workbook_id         INTEGER NOT NULL REFERENCES workbook (workbook_id),
    link_type           TEXT NOT NULL,              -- 'external' | 'hyperlink' | 'ole'
    target_path         TEXT NOT NULL,
    status              TEXT NOT NULL,              -- 'active' | 'broken' | 'unchecked'
    checked_at          TEXT
, link_index INTEGER);
CREATE TABLE formula_gap (
    from_cell_id    INTEGER NOT NULL REFERENCES cell (cell_id),
    reason          TEXT NOT NULL,
    raw_token       TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (from_cell_id, reason, raw_token)
);
CREATE TABLE formula_link (
    from_cell_id    INTEGER NOT NULL REFERENCES cell (cell_id),
    to_cell_id      INTEGER NOT NULL REFERENCES cell (cell_id),
    PRIMARY KEY (from_cell_id, to_cell_id)
);
CREATE TABLE ingest_rejection (
    ingest_rejection_id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_file_id      INTEGER REFERENCES source_file (source_file_id),
    mandate_id          INTEGER NOT NULL,
    file_name           TEXT NOT NULL,
    file_hash           TEXT NOT NULL,
    reason              TEXT NOT NULL,
    detail              TEXT,                       -- JSON object
    rejected_at         TEXT NOT NULL
);
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
);
CREATE TABLE mandate_industry (
    mandate_id INTEGER PRIMARY KEY,
    industry_tag TEXT NOT NULL,
    confirmed INTEGER NOT NULL CHECK (confirmed IN (0, 1)),
    inferred INTEGER NOT NULL CHECK (inferred IN (0, 1)),
    updated_at TEXT NOT NULL
);
CREATE TABLE nomenclature_alias (
    alias_id INTEGER PRIMARY KEY AUTOINCREMENT,
    alias_text TEXT NOT NULL,
    leaf_path TEXT NOT NULL,
    layer TEXT NOT NULL CHECK (layer IN ('spine', 'industry', 'mandate_soft')),
    industry_tag TEXT,
    mandate_id INTEGER,
    created_at TEXT NOT NULL
);
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
);
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
);
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
);
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
);
CREATE TABLE project_fact_field (
    field_id INTEGER PRIMARY KEY AUTOINCREMENT,
    path TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    created_at TEXT NOT NULL
);
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
);
CREATE TABLE region_header_geometry (
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id) ON DELETE CASCADE,
    geometry_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (parse_run_id, candidate_id)
);
CREATE TABLE region_triage_opinion (
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id) ON DELETE CASCADE,
    model TEXT NOT NULL,
    choice TEXT NOT NULL CHECK (choice IN ('main', 'scratch', 'orphan')),
    confidence REAL NOT NULL,
    p_main REAL,
    p_scratch REAL,
    p_orphan REAL,
    escalated INTEGER NOT NULL CHECK (escalated IN (0, 1)),
    created_at TEXT NOT NULL,
    PRIMARY KEY (parse_run_id, candidate_id)
);
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
, subject_kind TEXT, subject_key TEXT, confidence REAL, carried_from_decision_id INTEGER);
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
);
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
);
CREATE TABLE schedule_family (
    name TEXT PRIMARY KEY,
    created_at TEXT NOT NULL
);
CREATE TABLE schema_migration (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL);
CREATE TABLE source_file (
    source_file_id  INTEGER PRIMARY KEY AUTOINCREMENT,
    mandate_id      INTEGER NOT NULL,
    file_name       TEXT NOT NULL,
    file_hash       TEXT NOT NULL,
    file_type       TEXT NOT NULL,      -- 'fm_xlsx' | 'fm_xls' | 'fm_csv'
    ingested_at     TEXT NOT NULL,
    parser_version  TEXT NOT NULL, raw_metadata TEXT,
    UNIQUE (mandate_id, file_hash)
);
CREATE TABLE sqlite_sequence(name,seq);
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
, calculation_mode TEXT, full_calc_on_load INTEGER CHECK (full_calc_on_load IN (0, 1)), calc_chain_present INTEGER CHECK (calc_chain_present IN (0, 1)), iterative_calc INTEGER CHECK (iterative_calc IN (0, 1)), iterative_count INTEGER, error_cell_count INTEGER, calc_is_circular INTEGER NOT NULL DEFAULT 0 CHECK (calc_is_circular IN (0, 1)), calc_circular_group_count INTEGER, calc_max_cycle_length INTEGER);
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
);
CREATE TABLE worksheet_column (
    worksheet_id INTEGER NOT NULL REFERENCES worksheet (worksheet_id),
    col_num INTEGER NOT NULL,
    width INTEGER NOT NULL,
    PRIMARY KEY (worksheet_id, col_num)
);
CREATE INDEX idx_audit_log_parse_run ON audit_log (parse_run_id);
CREATE INDEX idx_candidate_member_cell ON candidate_member (cell_id);
CREATE UNIQUE INDEX idx_candidate_one_coverage_parent
    ON candidate (parse_run_id, worksheet_id)
    WHERE candidate_kind = 'coverage_parent';
CREATE INDEX idx_candidate_parent ON candidate (parent_candidate_id)
    WHERE parent_candidate_id IS NOT NULL;
CREATE INDEX idx_candidate_parse_run ON candidate (parse_run_id);
CREATE INDEX idx_candidate_related_target ON candidate_related (related_candidate_id);
CREATE INDEX idx_candidate_worksheet ON candidate (worksheet_id);
CREATE INDEX idx_cell_error ON cell (is_error, error_type);
CREATE INDEX idx_cell_interpretation_evidence_cell
    ON cell_interpretation_evidence (parse_run_id, cell_id, ordinal);
CREATE INDEX idx_cell_interpretation_path
    ON cell_interpretation (parse_run_id, nomenclature_path);
CREATE INDEX idx_cell_interpretation_status
    ON cell_interpretation (parse_run_id, nomenclature_status);
CREATE INDEX idx_cell_numeric ON cell (numeric_value) WHERE numeric_value IS NOT NULL;
CREATE INDEX idx_cell_reading_kind ON cell_reading (parse_run_id, kind);
CREATE INDEX idx_cell_style_id ON cell (style_id) WHERE style_id IS NOT NULL;
CREATE UNIQUE INDEX idx_cell_style_identity ON cell_style (
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
);
CREATE INDEX idx_cell_text ON cell (text_value) WHERE text_value IS NOT NULL;
CREATE INDEX idx_cell_value_source ON cell (value_source);
CREATE INDEX idx_cell_ws_coord ON cell (worksheet_id, coord);
CREATE INDEX idx_cellref_external ON cell_reference (external_link_id) WHERE external_link_id IS NOT NULL;
CREATE INDEX idx_cellref_from ON cell_reference (from_cell_id);
CREATE INDEX idx_cellref_resolved ON cell_reference (resolved_cell_id) WHERE resolved_cell_id IS NOT NULL;
CREATE INDEX idx_cellref_unresolved ON cell_reference (unresolved_reason) WHERE unresolved_reason IS NOT NULL;
CREATE INDEX idx_external_link_index ON external_link (workbook_id, link_index);
CREATE INDEX idx_external_link_workbook      ON external_link (workbook_id);
CREATE INDEX idx_formula_link_to ON formula_link (to_cell_id);
CREATE INDEX idx_ingest_rejection_file       ON ingest_rejection (mandate_id, file_hash);
CREATE INDEX idx_ingest_rejection_source_file ON ingest_rejection (source_file_id);
CREATE INDEX idx_llm_usage_model ON llm_usage (model_id);
CREATE INDEX idx_llm_usage_parse_run ON llm_usage (parse_run_id);
CREATE UNIQUE INDEX idx_nomenclature_alias_industry_text
    ON nomenclature_alias (industry_tag, alias_text)
    WHERE layer = 'industry';
CREATE INDEX idx_nomenclature_alias_leaf ON nomenclature_alias (leaf_path);
CREATE UNIQUE INDEX idx_nomenclature_alias_mandate_text
    ON nomenclature_alias (mandate_id, alias_text)
    WHERE layer = 'mandate_soft';
CREATE UNIQUE INDEX idx_nomenclature_alias_spine_text
    ON nomenclature_alias (alias_text)
    WHERE layer = 'spine';
CREATE INDEX idx_nomenclature_binding_cell ON nomenclature_binding (cell_id);
CREATE INDEX idx_nomenclature_binding_parse_run ON nomenclature_binding (parse_run_id);
CREATE INDEX idx_nomenclature_binding_path ON nomenclature_binding (path);
CREATE UNIQUE INDEX idx_nomenclature_node_industry_path
    ON nomenclature_node (industry_tag, path)
    WHERE layer = 'industry';
CREATE INDEX idx_nomenclature_node_mandate ON nomenclature_node (mandate_id);
CREATE UNIQUE INDEX idx_nomenclature_node_mandate_path
    ON nomenclature_node (mandate_id, path)
    WHERE layer = 'mandate_soft';
CREATE INDEX idx_nomenclature_node_parent ON nomenclature_node (parent_path);
CREATE UNIQUE INDEX idx_nomenclature_node_spine_path
    ON nomenclature_node (path)
    WHERE layer = 'spine';
CREATE INDEX idx_packet_disposition_candidate ON packet_disposition (candidate_id);
CREATE INDEX idx_packet_disposition_parse_run ON packet_disposition (parse_run_id);
CREATE INDEX idx_provenance_entity           ON provenance (entity_type, entity_id);
CREATE INDEX idx_provenance_source_file      ON provenance (source_file_id);
CREATE INDEX idx_review_queue_parse_run      ON review_queue (parse_run_id);
CREATE INDEX idx_run_stat_parse_run ON run_stat (parse_run_id);
CREATE INDEX idx_run_timing_parse_run ON run_timing (parse_run_id);
CREATE INDEX idx_run_timing_stage ON run_timing (stage);
CREATE INDEX idx_workbook_source_file        ON workbook (source_file_id);
```

## Appendix B — Nomenclature catalogue (`nomenclature_node`, all 60 rows)

| path | name | parent | layer | frozen | leaf | industry_tag |
|---|---|---|---|---|---|---|
| Profit & Loss > F & B Sales | F & B Sales | Profit & Loss | industry | 0 | 1 | hotel |
| Project Cost > Plant & Machinery > Air Conditioning | Air Conditioning | Project Cost > Plant & Machinery | industry | 0 | 1 | hotel |
| Project Cost > Plant & Machinery > Elevator / Lift | Elevator / Lift | Project Cost > Plant & Machinery | industry | 0 | 1 | hotel |
| Project Cost > Plant & Machinery > Kitchen Equipments | Kitchen Equipments | Project Cost > Plant & Machinery | industry | 0 | 1 | hotel |
| Project Cost > Plant & Machinery > Wastewater / ETP | Wastewater / ETP | Project Cost > Plant & Machinery | industry | 0 | 1 | hotel |
| Means of Finance > Capex | Capex | Means of Finance | mandate_soft | 0 | 1 |  |
| Balance Sheet | Balance Sheet |  | spine | 0 | 0 |  |
| Balance Sheet > Capital Employed | Capital Employed | Balance Sheet | spine | 0 | 0 |  |
| Balance Sheet > Cash & Bank Balance | Cash & Bank Balance | Balance Sheet | spine | 0 | 0 |  |
| Balance Sheet > Current Assets | Current Assets | Balance Sheet | spine | 0 | 0 |  |
| Balance Sheet > Current Liabilities | Current Liabilities | Balance Sheet | spine | 0 | 0 |  |
| Balance Sheet > Net Block | Net Block | Balance Sheet | spine | 0 | 0 |  |
| Balance Sheet > Net Worth | Net Worth | Balance Sheet | spine | 0 | 0 |  |
| Balance Sheet > Total Debt | Total Debt | Balance Sheet | spine | 0 | 0 |  |
| Cash Flow | Cash Flow |  | spine | 0 | 0 |  |
| Cash Flow > Cash Flow from Financing | Cash Flow from Financing | Cash Flow | spine | 0 | 0 |  |
| Cash Flow > Cash Flow from Investing | Cash Flow from Investing | Cash Flow | spine | 0 | 0 |  |
| Cash Flow > Cash Flow from Operations | Cash Flow from Operations | Cash Flow | spine | 0 | 0 |  |
| Key Ratios | Key Ratios |  | spine | 0 | 0 |  |
| Key Ratios > Coverage | Coverage | Key Ratios | spine | 0 | 0 |  |
| Key Ratios > Coverage > DSCR | DSCR | Key Ratios > Coverage | spine | 0 | 0 |  |
| Key Ratios > Coverage > Interest Coverage | Interest Coverage | Key Ratios > Coverage | spine | 0 | 0 |  |
| Key Ratios > Growth | Growth | Key Ratios | spine | 0 | 0 |  |
| Key Ratios > Growth > Sales Growth | Sales Growth | Key Ratios > Growth | spine | 0 | 0 |  |
| Key Ratios > Leverage | Leverage | Key Ratios | spine | 0 | 0 |  |
| Key Ratios > Leverage > Debt Equity | Debt Equity | Key Ratios > Leverage | spine | 0 | 0 |  |
| Key Ratios > Liquidity | Liquidity | Key Ratios | spine | 0 | 0 |  |
| Key Ratios > Liquidity > Current Ratio | Current Ratio | Key Ratios > Liquidity | spine | 0 | 0 |  |
| Key Ratios > Profitability | Profitability | Key Ratios | spine | 0 | 0 |  |
| Key Ratios > Profitability > EBITDA Margin | EBITDA Margin | Key Ratios > Profitability | spine | 0 | 0 |  |
| Key Ratios > Profitability > Gross Margin | Gross Margin | Key Ratios > Profitability | spine | 0 | 0 |  |
| Key Ratios > Profitability > PAT Margin | PAT Margin | Key Ratios > Profitability | spine | 0 | 0 |  |
| Key Ratios > Profitability > Return on Capital Employed | Return on Capital Employed | Key Ratios > Profitability | spine | 0 | 0 |  |
| Key Ratios > Profitability > Return on Equity | Return on Equity | Key Ratios > Profitability | spine | 0 | 0 |  |
| Means of Finance | Means of Finance |  | spine | 1 | 0 |  |
| Means of Finance > Promoter / Partners' Capital | Promoter / Partners' Capital | Means of Finance | spine | 1 | 0 |  |
| Means of Finance > Term Loan | Term Loan | Means of Finance | spine | 1 | 0 |  |
| Means of Finance > Unsecured Loans | Unsecured Loans | Means of Finance | spine | 1 | 0 |  |
| Means of Finance > Working Capital Assistance | Working Capital Assistance | Means of Finance | spine | 1 | 0 |  |
| Profit & Loss | Profit & Loss |  | spine | 0 | 0 |  |
| Profit & Loss > Cost of Sales | Cost of Sales | Profit & Loss | spine | 0 | 0 |  |
| Profit & Loss > Depreciation & Amortisation | Depreciation & Amortisation | Profit & Loss | spine | 0 | 0 |  |
| Profit & Loss > EBIT | EBIT | Profit & Loss | spine | 0 | 0 |  |
| Profit & Loss > EBITDA | EBITDA | Profit & Loss | spine | 0 | 0 |  |
| Profit & Loss > Gross Profit | Gross Profit | Profit & Loss | spine | 0 | 0 |  |
| Profit & Loss > Interest & Finance Charges | Interest & Finance Charges | Profit & Loss | spine | 0 | 0 |  |
| Profit & Loss > Operating Expenses | Operating Expenses | Profit & Loss | spine | 0 | 0 |  |
| Profit & Loss > PAT | PAT | Profit & Loss | spine | 0 | 0 |  |
| Profit & Loss > PBT | PBT | Profit & Loss | spine | 0 | 0 |  |
| Profit & Loss > Revenue | Revenue | Profit & Loss | spine | 0 | 0 |  |
| Profit & Loss > Tax | Tax | Profit & Loss | spine | 0 | 0 |  |
| Project Cost | Project Cost |  | spine | 1 | 0 |  |
| Project Cost > Civil Works | Civil Works | Project Cost | spine | 1 | 0 |  |
| Project Cost > Contingency | Contingency | Project Cost | spine | 1 | 0 |  |
| Project Cost > Electrical Installations | Electrical Installations | Project Cost | spine | 1 | 0 |  |
| Project Cost > Land & Site Development | Land & Site Development | Project Cost | spine | 1 | 0 |  |
| Project Cost > Margin Money / Working Capital Margin | Margin Money / Working Capital Margin | Project Cost | spine | 1 | 0 |  |
| Project Cost > Misc. Fixed Assets / Furniture & Fixtures | Misc. Fixed Assets / Furniture & Fixtures | Project Cost | spine | 1 | 0 |  |
| Project Cost > Plant & Machinery | Plant & Machinery | Project Cost | spine | 1 | 0 |  |
| Project Cost > Preliminary & Pre-operative Expenses | Preliminary & Pre-operative Expenses | Project Cost | spine | 1 | 0 |  |

## Appendix C — Binding paths in use (`nomenclature_binding`)

Economic paths with the number of cells bound to each (52 paths, 18,959 cells):

| path | cells |
|---|---|
| Balance Sheet > Current Assets | 3068 |
| Balance Sheet > Current Liabilities | 1693 |
| Profit & Loss > Operating Expenses | 1438 |
| Means of Finance > Term Loan | 1410 |
| Profit & Loss > Revenue | 1252 |
| Balance Sheet > Total Debt | 1251 |
| Balance Sheet > Net Worth | 1036 |
| Means of Finance > Working Capital Assistance | 929 |
| Profit & Loss > Cost of Sales | 776 |
| Balance Sheet > Cash & Bank Balance | 467 |
| Cash Flow > Cash Flow from Operations | 383 |
| Balance Sheet > Net Block | 360 |
| Profit & Loss > Interest & Finance Charges | 355 |
| Profit & Loss > PAT | 338 |
| Cash Flow > Cash Flow from Investing | 330 |
| Balance Sheet | 304 |
| Project Cost > Plant & Machinery > Kitchen Equipments | 274 |
| Profit & Loss > PBT | 267 |
| Means of Finance | 260 |
| Profit & Loss > Tax | 236 |
| Profit & Loss > Depreciation & Amortisation | 232 |
| Profit & Loss > EBITDA | 231 |
| Profit & Loss | 230 |
| Cash Flow > Cash Flow from Financing | 190 |
| Project > Installed Capacity | 172 |
| Means of Finance > Unsecured Loans | 132 |
| Key Ratios > Growth > Sales Growth | 122 |
| Key Ratios > Profitability > EBITDA Margin | 118 |
| Means of Finance > Capex | 116 |
| Project Cost > Plant & Machinery | 115 |
| Key Ratios > Liquidity > Current Ratio | 104 |
| Key Ratios > Leverage > Debt Equity | 95 |
| Key Ratios > Profitability > Return on Capital Employed | 95 |
| Key Ratios > Liquidity | 66 |
| Key Ratios > Leverage | 63 |
| Profit & Loss > EBIT | 60 |
| Balance Sheet > Capital Employed | 54 |
| Key Ratios > Profitability > PAT Margin | 49 |
| Key Ratios > Profitability > Gross Margin | 48 |
| Key Ratios > Profitability > Return on Equity | 48 |
| Profit & Loss > Gross Profit | 48 |
| Means of Finance > Promoter / Partners' Capital | 35 |
| Key Ratios > Profitability | 27 |
| Project Cost | 21 |
| Project Cost > Misc. Fixed Assets / Furniture & Fixtures | 14 |
| Key Ratios | 10 |
| Project Cost > Preliminary & Pre-operative Expenses | 10 |
| Key Ratios > Coverage > DSCR | 8 |
| Key Ratios > Coverage > Interest Coverage | 7 |
| Project Cost > Plant & Machinery > Elevator / Lift | 6 |
| Project Cost > Civil Works | 4 |
| Project > Manpower | 2 |

Frame paths (6) and identity paths (2):

| path_root | path | cells |
|---|---|---|
| frame | Frame > Blank | 4248 |
| frame | Frame > Period | 623 |
| frame | Frame > Schedule Title | 484 |
| frame | Frame > Section Banner | 202 |
| frame | Frame > Field Mark | 54 |
| frame | Frame > Scale | 12 |
| identity | Project Identity > Legal Name | 14 |
| identity | Project Identity > Partners / Promoters | 6 |

## Appendix D — Worksheets and cell counts

| worksheet_id | sheet | sheet_index | sheet_state | dimensions_declared | real_content_rows | occupied_cells | child_candidates |
|---|---|---|---|---|---|---|---|
| 1 | 1_Key Ratio_RK Group_Consol | 0 | visible | B1:AE58 | 56 | 1519 | 2 |
| 2 | 1.2_PL_RK Group_Consol | 1 | visible | A1:AP546 | 45 | 1204 | 4 |
| 3 | 1.4_ASSET_RK Group_Consol | 2 | visible | B1:AM61 | 54 | 1836 | 5 |
| 4 | 1.3_LIABILITY_RK Group_Consol | 3 | visible | B1:AF122 | 47 | 1400 | 3 |
| 5 | Cash Flow | 4 | visible | B1:AS114 | 70 | 1827 | 3 |
| 6 | Schedule_RK | 5 | visible | A1:X352 | 305 | 2085 | 7 |
| 7 | 1.1_Assumption sheet_RKA | 6 | visible | A1:AD234 | 158 | 1315 | 14 |
| 8 | 1_Key Ratio_RKA | 7 | visible | B1:AP41 | 34 | 319 | 4 |
| 9 | KFI- RKA | 8 | visible | B3:F22 | 20 | 96 | 1 |
| 10 | 1.2_PL_RKA | 9 | visible | B1:X578 | 101 | 547 | 8 |
| 11 | 1.3_LIABILITY_RKA | 10 | visible | A1:M186 | 89 | 943 | 4 |
| 12 | 1.4_ASSET_RKA | 11 | visible | B1:R142 | 99 | 1055 | 4 |
| 13 | KFI BFP | 12 | visible | B2:H21 | 20 | 98 | 3 |
| 14 | 2_Key Ratio_BFP | 13 | visible | B1:T39 | 33 | 281 | 3 |
| 15 | 2.2_PL_BFP | 14 | visible | B1:S579 | 106 | 1104 | 3 |
| 16 | 2.3_LIABILITY_BFP | 15 | visible | A1:M123 | 104 | 1020 | 5 |
| 17 | 2.4_ASSET_BFP | 16 | visible | B1:M54 | 53 | 564 | 3 |
| 18 | Schedule_BFP | 17 | visible | A1:T396 | 382 | 2643 | 3 |
| 19 | Summary-Debt type wise | 18 | visible | B1:Z64 | 56 | 1095 | 5 |
| 20 | Debt-profile Bank wise | 19 | visible | A1:AV134 | 107 | 3683 | 8 |
| 21 | 2.1_Assumption sheet_BFP | 20 | visible | A1:AD106 | 93 | 857 | 10 |
| 22 | LF | 21 | visible | A1:S37 | 33 | 407 | 6 |
