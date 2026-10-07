# Complete Details for Cell J20

## Cell Identity
| Property | Value |
|----------|-------|
| **Cell Reference** | J20 |
| **Cell ID (DB)** | 454 |
| **Worksheet** | 1_Key Ratio_RK Group_Consol |
| **Row Number** | 20 |
| **Column Number** | 10 |

---

## Cell Context & Labels

### Row Label (From Column B)
- **Cell**: B20
- **Label**: **Total Debt**

### Column Headers (Hierarchy)
| Level | Cell | Content |
|-------|------|---------|
| **Section** | J5 | (empty - inherits from D5: "Consolidated (RK And BFP)") |
| **Sub-Section** | J6 | (empty - inherits from D6: "Projected") |
| **Time Period** | J7 | **FY 29** |

### What This Cell Represents
- **Metric**: Total Debt
- **Time Period**: FY 29 (Fiscal Year 2029)
- **Type**: Projected (not actual)
- **Scope**: Consolidated (combines RK and BFP)

---

## Cell Content & Formula

### Raw Formula
```
=S20+AB20
```

### Interpretation
Sum of:
- **S20** → `='1_Key_Ratio_RKA'!J17` = **9,130.33 Mn** (RK Group debt for FY 29)
- **AB20** → `='2_Key_Ratio_BFP'!J15` = **1,678.48 Mn** (BFP debt for FY 29)

### Result
- **Consolidated Total Debt (FY 29)**: **10,808.80 Mn**

---

## Database Fields

### Cell Table
| Field | Value |
|-------|-------|
| cell_id | 454 |
| worksheet_id | 1 |
| coord | J20 |
| row_num | 20 |
| col_num | 10 |
| raw_value | `=S20+AB20` |
| raw_type | formula |
| value_type | number |
| numeric_value | 10808.8016703 |
| text_value | 10808.801670300003 |
| display_value | 10808.801670300003 |
| formula_text | `S20+AB20` |
| formula_state | ok |
| formula_normalized | `S20+AB20` |
| cached_value | 10808.801670300003 |
| cache_state | fresh |
| coerced_from_text | 0 (false) |
| is_error | 0 (false) |
| is_merged_anchor | 0 (false) |
| is_merged_participant | 0 (false) |
| style_id | 19 |

### Cell Interpretation Table
| Field | Value |
|-------|-------|
| interpretation_id | 454 |
| parse_run_id | 1 |
| cell_id | 454 |
| value_origin | formula |
| resulting_value | 10808.801670300003 |
| result_source | formula_cache |
| formula_state | ok |
| cache_state | fresh |
| is_error | 0 |
| nomenclature_path | (NULL) |
| nomenclature_status | **not_applicable** |
| amount_role | (NULL) |
| soft_leaf | (NULL) |
| via_alias | (NULL) |
| error_type | (NULL) |

### Cell Reading Table
| Field | Value |
|-------|-------|
| parse_run_id | 1 |
| cell_id | 454 |
| kind | **money** |
| scale | **million** |
| unit | (NULL) |
| currency | (NULL) |
| absolute_amount | 10,808,801,670.3 |
| type_source | derived |
| refusal | (NULL) |
| scale_basis | stated |

---

## Candidate / Region Classification

### Candidate Information
Cell J20 belongs to **two candidates**:

#### Candidate 1: Coverage Parent
- **Candidate ID**: 1
- **Kind**: coverage_parent
- **Role**: (main)
- **Bounding Box**: Rows 1-58, Columns 2-30
- **Description**: "Coverage parent for worksheet '1_Key_Ratio_RK Group_Consol'"
- **Purpose**: Covers entire worksheet structure

#### Candidate 2: LLM Region (Primary)
- **Candidate ID**: 23
- **Kind**: child
- **Role**: **main** ✅ (This is the primary region)
- **Bounding Box**: Rows 8-23, Columns 2-30
- **Description**: "LLM region (main) Summary Financials: Official section headers with Revenue, Profit, and EBITDA figures consolidated across years."
- **Status**: This cell is part of the main financial summary table

---

## Data Quality Assessment

| Check | Result | Status |
|-------|--------|--------|
| Cell Error | None | ✅ OK |
| Formula Valid | Yes | ✅ OK |
| Cache Fresh | Yes | ✅ OK |
| Cross-Sheet Refs Valid | Yes | ✅ OK |
| Numeric Precision | Full | ✅ OK |
| Coerced Type | No | ✅ OK |

---

## Cross-Sheet References (Data Lineage)

### This Cell (J20) References:

```
J20 in '1_Key Ratio_RK Group_Consol'
  ├─ S20 → '1_Key_Ratio_RKA'!J17
  │         9130.33 Mn (RK Total Debt FY 29)
  │
  └─ AB20 → '2_Key_Ratio_BFP'!J15
            1678.48 Mn (BFP Total Debt FY 29)
```

### Source Sheet Details
- **RK Source**: Worksheet "1_Key_Ratio_RKA" (ID: 8), Cell J17
- **BFP Source**: Worksheet "2_Key_Ratio_BFP" (ID: 14), Cell J15

---

## Column S (RKA Data) Context

| Property | Value |
|----------|-------|
| **Column Reference** | S (Column 19) |
| **Row 5** | (empty - inherits "Consolidated (RK And BFP)") |
| **Row 6** | (empty - inherits "Projected") |
| **Row 7** | FY 29 |
| **Purpose** | RKA Group data for FY 29 |
| **Row 20 Value** | `='1_Key_Ratio_RKA'!J17` = 9,130.33 Mn |

---

## Column AB (BFP Data) Context

| Property | Value |
|----------|-------|
| **Column Reference** | AB (Column 28) |
| **Row 5** | (empty - inherits "Consolidated (RK And BFP)") |
| **Row 6** | (empty - inherits "Projected") |
| **Row 7** | FY 29 |
| **Purpose** | BFP Group data for FY 29 |
| **Row 20 Value** | `='2_Key_Ratio_BFP'!J15` = 1,678.48 Mn |

---

## Summary Hierarchy

```
WORKSHEET: 1_Key Ratio_RK Group_Consol
│
├─ CANDIDATE 23 (Main Financial Region, Rows 8-23)
│  │
│  └─ ROW 20: Total Debt
│     │
│     └─ COLUMN J (FY 29 Projected)
│        │
│        └─ CELL J20
│           │
│           ├─ RK Component (S20): 9,130.33 Mn from '1_Key_Ratio_RKA'!J17
│           ├─ BFP Component (AB20): 1,678.48 Mn from '2_Key_Ratio_BFP'!J15
│           │
│           └─ CONSOLIDATED TOTAL: 10,808.80 Mn
```

---

## Verification Status

✅ **All Data Verified**
- Formula calculates correctly: 9,130.33 + 1,678.48 = 10,808.81 ✓
- Cross-references resolve: Both source sheets exist ✓
- Data type: Monetary amount in Millions ✓
- Cache status: Fresh (up-to-date) ✓
- No errors detected ✓

---

## How This Cell Fits in the Model

This cell is a **consolidated projected debt figure** that:
1. Combines debt data from two independent business groups (RK and BFP)
2. Represents the planned total debt for FY 2029
3. Is part of the "Summary Financials" region (Candidate 23)
4. Feeds into downstream financial ratio calculations (likely D/E ratio, debt covenants, etc.)
5. Bridges the gap between detailed group-level financials and consolidated reporting

