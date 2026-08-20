# OM Arham Ventures.xlsx — Parser Complexity Findings

**File analyzed:** `Project Docs/OM Arham Ventures.xlsx`  
**Analyzed with:** Python `openpyxl==3.1.5` (both `data_only=False` and `data_only=True`)  
**Scope:** All 16 *visible* worksheets plus hidden sheets required for dependency resolution.

> This document lists the “mess” observed in a typical client-created financial model and its implications for the ingestion-layer Spreadsheet Parser. Every claim is backed by sheet names, cell coordinates, range references, or exact formula text so a reader can verify it in the source workbook.

---

## 1. Workbook inventory: visible vs. hidden sheets

The workbook contains **46 worksheets**; only **16 are visible**. The visible sheets are:

| # | Visible sheet | Max rows | Max cols | Merged cells |
|---|---------------|----------|----------|--------------|
| 1 | `AT GLANCE` | 44 | 10 | 1 |
| 2 | `CAPITAL COST` | 61 | 12 | 2 |
| 3 | ` wORKING CAPITAL` | 39 | 15 | 1 |
| 4 | `P  L ` | 170 | 26 | 3 |
| 5 | `B  S ` | 46 | 27 | 10 |
| 6 | `CASH FLOW` | 52 | 14 | 2 |
| 7 | `ASSETS` | 93 | 16 | 5 |
| 8 | `PREOPERATIVE EXP` | 21 | 9 | 3 |
| 9 | `SALESPROJECTION` | 83 | 18 | 5 |
| 10 | `Interest` | 185 | 18 | 2 |
| 11 | `depreciation` | 157 | 23 | 7 |
| 12 | `Prof. assumptions` | 49 | 3 | 0 |
| 13 | `Manpower` | 19 | 10 | 0 |
| 14 | `Floor area` | 38 | 19 | 0 |
| 15 | `power cost` | 36 | 12 | 14 |
| 16 | `Details` | 234 | 10 | 26 |

Key hidden sheets that visible sheets *depend on*:

| Hidden sheet | Referenced by visible sheet | Example |
|--------------|-----------------------------|---------|
| `Pages` | Most visible sheets (page-number references) | `CAPITAL COST!C2 = =Pages!D26` |
| `BS_ANLYSIS` | `B  S ` | `B  S !D40 = =BS_ANLYSIS!J172` |
| `details of fixed assets  ` | `P  L ` | `P  L !D47 = =SUM('details of fixed assets  '!...)` |

**Implication:** The parser cannot skip hidden sheets. They must be loaded into the dependency graph even if they are excluded from the final extracted report.

---

## 2. External workbook links

The workbook retains **13+ external workbook links** (legacy CA firm files, trial balances, tax forms, etc.). One visible formula actually uses an external link:

```
'CAPITAL COST'!I19 = =[15]Manpower!F35
```

`[15]` is an external-book index, not the local `Manpower` sheet. Most external data is stale/empty, but references like this remain in formulas.

**Implication:** The parser needs an explicit representation for external references, and a policy for unresolvable links (e.g., flag, fallback to cached value, or treat as `#REF!`).

---

## 3. Defined-name pollution

Approximately 31 defined names are present, many leaked from external workbooks:

```
___a65537, __a65537, __jj102, _a65537, _Fill, _jj102, A, a17J554,
abc, additions, arial, az, CASH, Challan_No, cmb_DDT.RateDividPrevYrType,
cmb_TCS.StateCode, cmb_TDS2.StateCode, DDT.AddLITPlusIntrestPayable,
DDT.TaxAndInterestPaid, FA_DEP, FA_DEP_CO, gg, L, Mane, opening,
pm, Refund, StateList, TCS.AmtTCSClaimedThisYear,
TDS2.ClaimOutOfTotTDSOnAmtPaid, wdq, YESNO
```

None appear to be used by the visible model, but they are present in the workbook metadata.

**Implication:** Defined names should be parsed defensively and mostly discarded unless referenced by a retained formula.

---

## 4. Dirty sheet names and formula references

Several sheet names contain leading/trailing spaces and inconsistent casing:

| Sheet name | Issue |
|------------|-------|
| ` wORKING CAPITAL` | Leading space, mixed case |
| `P  L ` | Double spaces + trailing space |
| `B  S ` | Double spaces + trailing space |
| `details of fixed assets  ` | Trailing double space |
| `iNCOME tAX cOMPUTATION` | Mixed casing |

Because formulas quote these names exactly, normalization breaks reference resolution:

```
'P  L '!D35 = =Manpower!A3
'B  S '!D16 = ='CASH FLOW'!D45
' wORKING CAPITAL'!E22 = =ROUND((SUM('P  L '!E33:E...)/12*$D$22),0)
```

**Implication:** The parser must store sheet names verbatim and use a formula lexer that handles single-quoted sheet names with embedded spaces.

---

## 5. Phantom / unreliable used ranges

`ws.dimensions` / `max_row` / `max_column` overstate the true content region on several sheets:

- `AT GLANCE` reports `A1:J44`; real content begins around `B3`.
- `SALESPROJECTION` reports `A1:R83`; there is a large dead zone after row 45.
- `Details` reports `A1:J234`; the last real data ends near row 234, but with large disjoint blocks.

**Implication:** The parser should compute its own bounding box from non-empty cells, merged-cell ranges, and formula precedents, rather than trusting Excel’s declared dimensions.

---

## 6. Scratch / rough work left behind

### 6.1 Unlabeled formula islands below main tables

- **`'Interest'!F176:G185`** — floating arithmetic with no row label:
  ```
  F176 = =250.38-82.38
  F177 = =+F176/12
  F178 = =241.06-61.06
  G180 = =229.5-37.5
  G181 = =+G180/12
  G184 = =209.44-13.44
  G185 = =+G184/12
  ```

- **`'P  L '!D162:H170`** — unlabeled ratio blocks:
  ```
  D162 = =D59+D53
  D164 = =D162/D29*100
  D166 = =D53+D50+D59
  D168 = =D166/D29*100
  D170 = =D50+D53
  ```

- **`'B  S '!Q11:R18` and `Q35:R35`** — a secondary mini-analysis to the right of the main balance sheet, with its own duplicate header `Q16 = Current Assets`.

- **`'PREOPERATIVE EXP'!H13:I15`, `F19`, `F21`, `H18`** — side scratch:
  ```
  H13 = =87.85729+0.07
  H14 = =D20/100000
  H15 = =H14-87.75
  I15 = =H13-H15
  H18 = =D20-7503768
  F19 = =D20-D13
  ```

### 6.2 Orphan constants (stale page numbers / notes)

| Cell | Value | Context |
|------|-------|---------|
| `SALESPROJECTION!E2` | `39` | Orphan page number; real page comes from `Pages!D44` |
| `depreciation!F2` | `42` | Orphan page number; real page comes from `Pages!D46` |
| `power cost!F3` | `34` | Orphan page number |
| `SALESPROJECTION!A26` | `N.B.: The average r...` | Explanatory note inside data area |
| `Details!J155` | `0` | Random zero mid-sheet |

### 6.3 Dead but retained formulas

- **`'P  L '!D60 = =#REF!`** — a cell that contains only a `#REF!` error token.
- **`ASSETS!F35 = =12117678.83*0`** (and `F36`, `F37`) — the client zeroed out lines instead of deleting them.
- **`'Details'!F233 = =21811821.89`** followed by `F234 = =SUM(F233:F233)` — a single-cell SUM of a constant.

**Implication:** Scratch detection cannot simply delete unlabeled cells; some scratch feeds live cells. The parser should flag scratch regions while keeping them in the graph.

---

## 7. Gap pathology — blank rows and columns

### 7.1 Blank rows used purely for spacing inside a single table

- **`depreciation`** has **71 blank rows** in 157 rows. The table uses blank rows as visual spacing between asset classes (e.g., rows 12/13, 14/15, etc.).
- **`P  L `** has **103 blank rows** in 170 — a mix of spacing, section breaks, and hidden scratch.

### 7.2 Blank columns with live content beyond them

- **`B  S `**: columns `O:P` are completely empty, but `Q:R` contain a live scratch analysis (`Q11:R18`, `Q35:R35`).
- **`CASH FLOW`**: column `C` is empty but the table continues through `D:N`.

### 7.3 Hidden rows / columns that still participate in formulas

| Sheet | Hidden rows | Hidden cols | Formula dependency example |
|-------|-------------|-------------|----------------------------|
| `AT GLANCE` | 37, 38 | — | layout only |
| ` wORKING CAPITAL` | 27, 29, 33 | L, M | `M22:N22` pull from `P  L ` |
| `P  L ` | — | L | hidden year column L feeds `SUM(...)` in B S / CASH FLOW |
| `B  S ` | 30 | M | hidden col M still referenced |
| `CASH FLOW` | 14, 17, 37 | M | hidden rows/cols referenced |
| `ASSETS` | 24, 25, 35–38, 52, 67–69, 74–78 | — | hidden spacing rows |
| `SALESPROJECTION` | 18–24, 28–30 | M, N | hidden rows inside room table; hidden year cols M/N |
| `Interest` | 12–15, 138–148 | — | hidden mid-schedule rows |
| `depreciation` | 58–72 | L | hidden block; hidden col L |

**Critical example:** `SALESPROJECTION` hides rows 18–24 inside the room-category table, then `D12 = =D14+D15+D16+D17` sums a range that visually skips the hidden rows.

**Implication:** Blank rows/columns must **never** be treated as automatic table separators. The parser needs a region-detection algorithm that uses labels, borders, indentation, and formula coherence, not just emptiness. Hidden rows/columns must be retained in the graph.

---

## 8. Error cells and cascading failures

There are **96 error cells** in visible sheets:

| Sheet | Error count | Error pattern |
|-------|-------------|---------------|
| `P  L ` | 38 | `#REF!` in formula text + `#VALUE!` cascade |
| `B  S ` | 29 | Mostly `#VALUE!` in cols M/N |
| `CASH FLOW` | 19 | Mostly `#VALUE!` in cols M/N |
| ` wORKING CAPITAL` | 10 | `#VALUE!` in cols M/N |
| `depreciation` | 1 | `#REF!` |

### 8.1 Errors baked into formula text

```
'P  L '!P33 = =#REF!
'P  L '!P34 = =#REF!
'P  L '!V49 = =#REF!
'P  L '!D60 = =#REF!
depreciation!N128 = =#REF!
```

These are unrecoverable; the precedent cells/rows were deleted.

### 8.2 Cascading `#VALUE!` traced to a single root cause

The wall of `#VALUE!` in the M/N columns of ` wORKING CAPITAL`, `B  S `, and `CASH FLOW` originates in `P  L `:

```
'P  L '!B35 = =Manpower!A3   → returns text "APPENDIX 7:"
'P  L '!L35 = =$B$35*L29*1.35 → text × number = #VALUE!
'P  L '!M35 = =$B$35*M29*1.34 → text × number = #VALUE!
```

Then sums like `B  S !M16 = ='CASH FLOW'!M45` and `CASH FLOW!M12 = ='P  L '!L61` propagate the error across sheets.

Concrete error cells:

```
' wORKING CAPITAL'!M22:N22, M25:N25, M26:N26, M28:N28, M32:N32 = #VALUE!
'B  S '!M16:N16, M22:N22, M27:N27, M32:N32, M35:N35, M37:N38, D40 = #VALUE!/#REF!
'CASH FLOW'!M12:N12, M18:N18, M20:N20, M38:N39, M41:N41, M44:N44, N43 = #VALUE!
```

**Implication:** The parser must store both cached values and formula text, classify errors, and preserve error cells as graph nodes. Downstream consumers should be able to ignore errors but still see the propagation chain.

---

## 9. Formula-style inconsistencies

### 9.1 Legacy `=+` unary-plus prefix

112 formulas use the Lotus-style `=+` prefix:

```
'Interest'!B13 = =+B12+1
'CAPITAL COST'!L19 = =+I19/I10*100
'CASH FLOW'!B14 = =+'P  L '!D52
'P  L '!D48 = =+' wORKING CAPITAL'!E33
'depreciation'!E14 = =+D18
```

`depreciation` alone has 90 such formulas.

### 9.2 Constants written as formulas

Inputs disguised as formulas are common:

```
'SALESPROJECTION'!F41 = =200/2
'SALESPROJECTION'!G41 = =300/2
'SALESPROJECTION'!K33 = =L45
'power cost'!J18 = =8
'power cost'!J19 = =145
'power cost'!J25 = =(61*20*2*365)/...
'PREOPERATIVE EXP'!D13 = =ROUND(Interest!...,0)
'Details'!F30 = =58590000
'Details'!E158 = =33000
'Details'!F175 = =E175*D175
```

**Implication:** The parser cannot assume formulas are “computed” and constants are “inputs.” Both must be preserved verbatim.

### 9.3 Inconsistent anchoring within the same row

`P  L ` row 23 mixes absolute and relative references across columns:

```
D23 = =$D$18*D11
E23 = =$E$18*E11
...
J23 = =J18*J11     (no $)
K23 = =K18*K11     (no $)
L23 = =L18*L11     (no $)
M23 = =M18*M11     (no $)
N23 = =N18*N11     (extra col beyond main year block)
```

Row 24 changes operand order at column L:

```
D24 = =$D$18*D12
...
K24 = =$K$18*K12
L24 = =L12*L18
M24 = =M12*M18
```

**Implication:** Row-fill pattern inference will fail. Every formula must be parsed and stored per cell.

### 9.4 Percent literals and exponentiation

```
' wORKING CAPITAL'!E29 = =E27*14.5%*0.9
'P  L '!H18 = =G18+5%
'ASSETS'!D46 = =C46*18%
'CAPITAL COST'!L15 = =D14/I15*10^7
```

Grammar must support postfix `%` and `^`.

---

## 10. Multi-table / multi-schema sheets

### 10.1 `Details` — ~10 vendor quote blocks with different schemas

Rows 1–234 contain at least 10 distinct sub-tables:

| Block | Rows | Columns / schema |
|-------|------|------------------|
| Civil works | 1–13 | Sl.No / Floor / Area / Rate / Amount |
| Extra items | 18–26 | Description / Amount (no Sl.No) |
| Plumbing | 29–37 | Sl.No / Description / Unit / Qty / Rate / Amount |
| Fire fighting | 41–42 | Description / Amount |
| Plant & machinery | 45–52 | Sl.No / Spec / Unit Price ex-GST / GST / incl-GST / Qty / Total |
| Kitchen/store equipment | 55–130 | Code / Name / Size / Make / Qty / Unit Price / Amount |
| Water treatment | 145–149 | Sl.No / Description / Quantity / Rate / Amount |
| Lifts | 151–153 | Description / Amount |
| HVAC | 155–160 | S.No / Description / Unit / Qty / Rate / Amount |
| Miscellaneous / GenSet / LED / Electrical | 163–234 | Varying schemas |

Within these blocks:

- Serial numbering restarts: `1,2,3…` then `A.`, then `ST.02, ST.03…`, then `LA-03, LA-04…`.
- Serial `9` is skipped (`A208=11` follows `A207=9`).
- Quantity cells mix text and numbers: `"1Set"`, `"200 PC"`, `"L S"`, `"Complete JOB"`.
- Headers contain newlines: `C45 = "Unit Price\n(Excl…"`, `A157 = "S\no"`.

### 10.2 `Floor area` — two unrelated tables

- Table 1: rows 3–10 (`A3:C10`) — floor/area/rooms.
- Table 2: rows 18–28 (`A18:D28`) — facility details.

Serial column in Table 2 breaks its own formula chain:

```
A19 = 1
A20 = =A19+1
...
A23 = 5          ← hardcoded, breaks pattern
A24 = =A23+1
```

### 10.3 `Interest` — monthly schedule with inline summary headers

The monthly repayment table is interrupted by repeated side-summary headers:

```
I33 = Yearly Int
J33 = Yearly P rep
K33 = Total Rep
I34 = =SUM(D12:D41)
I45 = Yearly Int   (repeated)
I46 = =SUM(D42:D53)
I57 = Yearly Int   (repeated)
I58 = =SUM(D54:D65)
```

These headers appear **inside** the data region, not at the top.

### 10.4 `AT GLANCE` — vertical form, not a table

- Numbered items jump from `6)` at row 30 to `11)` at row 41 (items 7–10 deleted).
- Values span multiple continuation rows:
  - `G10:G14` are all partners’ names under `C10 = NAME OF PARTNERS`.
  - `G19:G25` are address lines under `C17 = LOCATION`.
- `G41 = 320KVA` is text/number mixed.

**Implication:** Table extraction must be per-region, schema-agnostic, and robust to serial-number breaks and mid-table headers.

---

## 11. Header and label quirks

### 11.1 Labels that are formulas, not strings

```
'P  L '!A13 = =SALESPROJECTION!...
'P  L '!A25 = =A13
'P  L '!A26 = =A14
'Prof. assumptions'!A19 = =A10
'Prof. assumptions'!A20 = =A11
'Prof. assumptions'!B24 = =B5
```

### 11.2 Leading/trailing whitespace in labels

```
'CAPITAL COST'!G14 = "Land Area "
'P  L '!A44 = "Insurance Charges"
'P  L '!A37 = "Repair & Maintena..."
'Details'!C45 = "Unit Price\n(Excl...)"
```

### 11.3 Inconsistent year headers across sheets

| Sheet | Year header style | Years present |
|-------|-------------------|---------------|
| `B  S ` | `Construction Year…`, `Year1`…`Year10` | 10 + construction |
| `depreciation` | `Year 1`…`Year 10` (with space) | 10 |
| `P  L ` | Formula-linked to `B  S ` (`D8 = ='B  S '!E8`) | 10 |
| `CASH FLOW` | `Construction`, `Year 1`…`Year 10` | 10 + construction |
| `power cost` | `1ST YR`…`8th YR` (mixed case) | 8 only |
| `Interest` | Fiscal years `2025-26`, months, year counters | Monthly + yearly |

**Implication:** Year-axis alignment cannot rely on header string equality; it needs semantic normalization (e.g., position index + detected year pattern).

---

## 12. Type and value oddities

### 12.1 Numbers stored as text

```
'power cost'!F3 = "34"
'Pages'!D6 = 26   but   'Pages'!D44 = "39 - 40"
```

### 12.2 Ultra-precise floating inputs

```
'Details'!D3  = 20502.775774919613
'Details'!D4  = 12200.774163901791
'ASSETS'!E11  = 20502.775774919613
```

### 12.3 Text/number mixtures in quantity columns

```
'Details'!C145 = "1Set"
'Details'!E168 = "200 PC"
'Details'!D211 = "Complete JOB"
'Details'!D202 = "L S"
```

### 12.4 Duplicate data across sheets

- `ASSETS` rows 11–21 duplicate `Details` rows 3–13 (civil works).
- `ASSETS` rows 54–130 duplicate large parts of `Details` rows 55–130 (kitchen/store equipment).
- `' wORKING CAPITAL'` and `ASSETS` both re-express parts of `Details`.

There is no single source of truth.

**Implication:** Type inference must be fuzzy, and the parser should not assume one canonical table per concept.

---

## 13. Merged cells

There are **160+ merged-cell ranges** across visible sheets. Examples:

- `AT GLANCE!D3:J3` — title row.
- `CAPITAL COST!B5:K5`, `B31:K31` — section titles.
- `B  S !C5:K5` — title.
- `power cost` has 14 merged ranges, mostly section headers.
- `Details` has 26 merged ranges, mostly item descriptions spanning columns.

**Implication:** The parser must record merge anchors and participant cells; only the top-left cell holds the value, but formatting/semantic span matters for table detection.

---

## 14. Design implications for the Spreadsheet Parser

Based on the above, the parser should be built as a **cell-centric ingestion layer** first, with table extraction as a second, heuristic layer:

1. **Canonical cell model**  
   For every cell store: `sheet`, `row`, `col`, `raw_value`, `formula`, `cached_value`, `value_type`, `is_error`, `error_type`, `is_merged_anchor`, `merged_range`, `row_hidden`, `col_hidden`, `sheet_hidden`.

2. **Tolerant formula lexer**  
   Handle `=+`, postfix `%`, `^`, quoted sheet names with spaces, `#REF!`/`#VALUE!` literals, external `[N]Sheet!Cell` references, and 3D/range references.

3. **No blanket deletion of blanks, hidden cells, or errors**  
   They are load-bearing in the dependency graph. Errors must be retained but flagged `ignorable`.

4. **Region detection via coherence, not emptiness**  
   Use label density, border patterns, merged headers, and formula-range overlap to segment tables. Blank rows/columns are weak signals only.

5. **Scratch detection as metadata**  
   Flag unlabeled formula islands, orphan constants in row 2, and `*0`-disabled rows, but keep them in the graph in case they feed live cells.

6. **Sheet-name fidelity**  
   Preserve exact sheet names; do not trim or normalize before reference resolution.

7. **External-link handling**  
   Represent external references explicitly; decide whether to resolve from cached data, flag as missing, or treat as `#REF!`.

8. **Year-axis normalization**  
   Do not match year headers by string. Infer the time axis from column position and detected patterns (`Year N`, `20XX-YY`, fiscal-year cycles, etc.).

---

## 15. Suggested test fixtures

From this workbook, the following minimal repro cases should become parser fixtures:

| Fixture purpose | Source location |
|-----------------|-----------------|
| Every-other-row gaps | `depreciation` rows 10–55 |
| Hidden-but-referenced rows/cols | `SALESPROJECTION` rows 18–24; `P  L ` column L |
| Error cascade | `P  L !B35:L35` → `B  S !M16` → `CASH FLOW!M12` |
| `#REF!` in formula text | `P  L !D60`, `depreciation!N128` |
| Scratch islands | `Interest!F176:G185`, `P  L !D162:H170` |
| Multi-schema stack | `Details` rows 1–234 |
| External reference | `CAPITAL COST!I19 = =[15]Manpower!F35` |
| `=+` prefix / constant-as-formula | `Interest!B13`, `SALESPROJECTION!F41` |
| Merged header spanning | `AT GLANCE!D3:J3` |
| Trailing-space sheet names | `P  L `, ` wORKING CAPITAL` |

---

*End of findings.*
