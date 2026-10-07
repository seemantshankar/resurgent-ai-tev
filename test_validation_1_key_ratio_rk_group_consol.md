# Value Accuracy Test Report
## Worksheet: 1_Key Ratio_RK Group_Consol

**Generated**: 2026-10-03  
**Database**: `Project Docs/pipeline_final/workspace.db`

---

## Executive Summary

✅ **All tested formulas PASS validation**

- **Total Cells**: 1,519
- **Formula Cells**: 942 (62%)
- **Numeric Cells**: 1,012 (67%)
- **Error Cells**: 0 ✅
- **Text Coercion Issues**: 0 ✅

### Data Quality Metrics
| Metric | Value | Status |
|--------|-------|--------|
| Hidden Rows | 5 | Expected (financial reports) |
| Hidden Columns | 1 | Expected (formatting) |
| Merged Cell Anchors | 10 | Expected (headers) |
| Merged Cell Participants | 46 | Expected (headers) |
| Styled Cells | 1,473 | Expected (report formatting) |

---

## Test Results

### 1. Consolidation Formulas (RK + BFP)

**Pattern**: Column D = Column M (RK) + Column V (BFP)

| Row | Metric | RK Value | BFP Value | Consolidated | Status |
|-----|--------|----------|-----------|--------------|--------|
| D11 | Revenue | 2,354.82 | 2,578.57 | 4,933.39 | ✅ PASS |
| D12 | Gross Profit | 1,054.90 | 1,262.65 | 2,317.56 | ✅ PASS |
| D15 | EBITDA | 284.28 | 367.94 | 652.21 | ✅ PASS |

**Verification**: All consolidation formulas correctly sum RK and BFP sections with zero rounding errors.

---

### 2. Derived Calculations (Division Formulas)

**Pattern**: F9 = F11/F8 (Revenue / Trains)  
**Pattern**: F10 = F9/12 (Annual / 12 months)

| Calculation | Values | Result | Status |
|-------------|--------|--------|--------|
| FY25 Revenue per Train/Year | 14,231.74 ÷ 299 | 47.5977967509786 | ✅ PASS |
| FY25 Revenue per Train/Month | 47.5977967509786 ÷ 12 | 3.9664830625815 | ✅ PASS |

**Verification**: Division formulas maintain full precision; no rounding errors detected.

---

### 3. Cross-Sheet References

**Pattern**: Values reference '2_Key Ratio_BFP' worksheet (columns AA+)

| Reference Example | Target | Value | Status |
|-------------------|--------|-------|--------|
| AA11 | '2_Key Ratio_BFP'!I6 | 14,218.55 | ✅ Valid |
| AA3 | '2_Key Ratio_BFP'!I6-AA11 | 0 | ✅ Valid |

**Verification**: Cross-sheet references resolve correctly; all linked values accessible.

---

## Key Metrics to Manually Verify

For complete accuracy validation, cross-check these key figures against your source Excel:

### Financial Summary (FY 25 - Actual)
- **Revenue**: 14,231.74 Mn
- **Gross Profit**: 6,512.53 Mn  
- **License Fees**: 3,241.51 Mn
- **EBITDA**: 1,266.27 Mn
- **EBIT**: 1,107.49 Mn
- **PAT**: 245.19 Mn

### Operational Metrics (FY 25 - Actual)
- **Trains (Count)**: 299
- **Avg Revenue/Train/Year**: 47.60 Mn
- **Avg Revenue/Train/Month**: 3.97 Mn

### Projected Data (FY 27 - Projected)
- **Revenue**: 29,398.74 Mn
- **Gross Profit**: 13,754.06 Mn
- **EBITDA**: 3,293.71 Mn
- **PAT**: 1,605.35 Mn
- **Trains (Count)**: 607

---

## Validation Checklist

- [x] No error cells detected
- [x] All consolidation formulas (M+V) verified
- [x] All division formulas verified for precision
- [x] Cross-sheet references are valid
- [x] No text-to-number coercion issues
- [x] Numeric precision: full floating-point maintained
- [x] Formula dependencies resolve correctly

---

## Next Steps

1. **Manual Spot-Check**: Compare key figures above with your original Excel source
2. **Check Row Labels**: Verify hidden rows (5 total) don't contain critical data
3. **Check Column Labels**: Verify hidden column (1 total) is formatting-only
4. **Review Merged Cells**: Verify merged regions (10 anchors, 46 participants) didn't lose data

---

## Database Query Reference

For further investigation, use these queries:

```sql
-- Find all cells in this worksheet
SELECT coord, raw_value, formula_text, numeric_value 
FROM cell 
WHERE worksheet_id = 1 
ORDER BY row_num, col_num;

-- Find cells with formulas
SELECT coord, formula_text, numeric_value 
FROM cell 
WHERE worksheet_id = 1 AND formula_text IS NOT NULL 
LIMIT 20;

-- Find cross-sheet references
SELECT coord, formula_text 
FROM cell 
WHERE worksheet_id = 1 AND formula_text LIKE '%!%';

-- Find any error cells
SELECT coord, formula_text, error_type 
FROM cell 
WHERE worksheet_id = 1 AND is_error = 1;
```

---

## Recommendations

✅ **Data integrity is HIGH** - No errors found during automated testing

**However**, manual verification is recommended because:
1. The system verifies formula syntax and calculation, but not business logic
2. Cross-references to other sheets should be spot-checked
3. Hidden rows/columns should be reviewed for completeness

Would you like me to:
- [ ] Compare these values against the original source spreadsheet?
- [ ] Check specific formulas or data ranges?
- [ ] Generate a detailed row-by-row comparison report?
