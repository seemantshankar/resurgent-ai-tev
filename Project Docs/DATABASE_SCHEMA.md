# TEV Parser Database Schema

## Database Name and Location
- **Database File:** `workspace.db`
- **Full Path:** `/Users/seemantshankar/tev-runs/bspl-cma-verified-1791367028/workspace.db`
- **Purpose:** TEV (Technical Excel Validator) pipeline - ingest, discover, and classify financial model data from BSPL_CMA_Revised_Sep2026.xlsx
- **Type:** SQLite 3
- **Size:** 2.1 GB
- **Parse Run ID:** 1

---

## Table Hierarchy and Relationships

### Core Metadata Tables

#### 1. **schema_migration**
Tracks database schema versions applied.
```sql
CREATE TABLE schema_migration (
    version INTEGER PRIMARY KEY,
    applied_at TEXT NOT NULL
);
```

#### 2. **source_file**
Records of ingested source files (Excel workbooks).
```sql
CREATE TABLE source_file (
    source_file_id INTEGER PRIMARY KEY AUTOINCREMENT,
    mandate_id INTEGER NOT NULL,
    file_name TEXT NOT NULL,
    file_hash TEXT NOT NULL,
    file_type TEXT NOT NULL,          -- 'fm_xlsx' | 'fm_xls' | 'fm_csv'
    ingested_at TEXT NOT NULL,
    parser_version TEXT NOT NULL,
    raw_metadata TEXT,
    UNIQUE (mandate_id, file_hash)
);
```

#### 3. **parse_run**
High-level record of each ingest/classify pipeline execution.
```sql
CREATE TABLE parse_run (
    parse_run_id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_file_id INTEGER NOT NULL REFERENCES source_file (source_file_id),
    mandate_id INTEGER NOT NULL,
    parser_version TEXT NOT NULL,
    config_hash TEXT,
    started_at TEXT NOT NULL,
    finished_at TEXT,
    status TEXT NOT NULL,               -- 'success' | 'partial' | 'failed' | 'rejected'
    metrics TEXT,                       -- JSON object
    warnings TEXT,                      -- JSON array
    errors TEXT,                        -- JSON array
    UNIQUE (source_file_id, parser_version, config_hash)
);
```

---

### Workbook Structure Tables

#### 4. **workbook**
Excel workbook metadata and calculation settings.
```sql
CREATE TABLE workbook (
    workbook_id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_file_id INTEGER NOT NULL REFERENCES source_file (source_file_id),
    application_name TEXT,
    application_version TEXT,
    sheet_count INTEGER NOT NULL,
    sheet_names TEXT,                  -- JSON array of sheet names
    defined_names TEXT,                -- JSON array
    properties TEXT,                   -- JSON object
    is_protected INTEGER NOT NULL DEFAULT 0,
    created_at TEXT,
    modified_at TEXT,
    calculation_mode TEXT,
    full_calc_on_load INTEGER,
    calc_chain_present INTEGER,
    iterative_calc INTEGER,
    iterative_count INTEGER,
    error_cell_count INTEGER,
    calc_is_circular INTEGER NOT NULL DEFAULT 0,
    calc_circular_group_count INTEGER,
    calc_max_cycle_length INTEGER
);
```

#### 5. **external_link**
External link references within the workbook.
```sql
CREATE TABLE external_link (
    external_link_id INTEGER PRIMARY KEY AUTOINCREMENT,
    workbook_id INTEGER NOT NULL REFERENCES workbook (workbook_id),
    link_type TEXT NOT NULL,           -- 'external' | 'hyperlink' | 'ole'
    target_path TEXT NOT NULL,
    status TEXT NOT NULL,              -- 'active' | 'broken' | 'unchecked'
    checked_at TEXT,
    link_index INTEGER
);
```

#### 6. **worksheet**
Individual worksheets within the workbook.
```sql
CREATE TABLE worksheet (
    worksheet_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    sheet_name TEXT NOT NULL,
    sheet_index INTEGER NOT NULL,
    sheet_state TEXT,
    bbox_min_row INTEGER,
    bbox_min_col INTEGER,
    bbox_max_row INTEGER,
    bbox_max_col INTEGER,
    dimensions_declared TEXT,
    real_content_rows INTEGER,
    declared_merged INTEGER
);
```

#### 7. **worksheet_column**
Column width metadata for worksheets.
```sql
CREATE TABLE worksheet_column (
    worksheet_id INTEGER NOT NULL REFERENCES worksheet (worksheet_id),
    col_num INTEGER NOT NULL,
    width INTEGER NOT NULL,
    PRIMARY KEY (worksheet_id, col_num)
);
```

---

### Cell Data Tables

#### 8. **cell**
Individual cell data with values, formulas, types, and styling.
```sql
CREATE TABLE cell (
    cell_id INTEGER PRIMARY KEY AUTOINCREMENT,
    worksheet_id INTEGER NOT NULL REFERENCES worksheet (worksheet_id),
    coord TEXT NOT NULL,               -- e.g., "A1", "B5"
    row_num INTEGER NOT NULL,
    col_num INTEGER NOT NULL,
    raw_value TEXT,
    raw_type TEXT NOT NULL,            -- e.g., 'string', 'number', 'date'
    value_type TEXT NOT NULL,
    text_value TEXT,
    display_value TEXT,
    numeric_value NUMERIC,
    bool_value INTEGER,
    date_value TEXT,
    formula_text TEXT,                 -- Original formula with normalized prefix
    formula_state TEXT,
    cached_value TEXT,
    cache_state TEXT,
    coerced_from_text INTEGER NOT NULL DEFAULT 0,
    is_error INTEGER NOT NULL DEFAULT 0,
    error_type TEXT,
    is_merged_anchor INTEGER NOT NULL DEFAULT 0,
    is_merged_participant INTEGER NOT NULL DEFAULT 0,
    merged_range TEXT,
    value_source TEXT NOT NULL DEFAULT 'cell',  -- 'cell' | 'merged_anchor'
    row_hidden INTEGER NOT NULL DEFAULT 0,
    col_hidden INTEGER NOT NULL DEFAULT 0,
    sheet_hidden INTEGER NOT NULL DEFAULT 0,
    style_id INTEGER REFERENCES cell_style (style_id),
    formula_normalized TEXT             -- Normalized formula (without _xlfn. prefix)
);
-- Indexes: idx_cell_ws_coord, idx_cell_numeric, idx_cell_text, idx_cell_error, idx_cell_value_source
```

#### 9. **cell_style**
Formatting and styling for cells.
```sql
CREATE TABLE cell_style (
    style_id INTEGER PRIMARY KEY AUTOINCREMENT,
    is_bold INTEGER,
    number_format TEXT,
    fill_fg_color TEXT,
    fill_pattern TEXT,
    border_top_style TEXT,
    border_top_color TEXT,
    border_right_style TEXT,
    border_right_color TEXT,
    border_bottom_style TEXT,
    border_bottom_color TEXT,
    border_left_style TEXT,
    border_left_color TEXT,
    font_name TEXT,
    font_size INTEGER,
    italic INTEGER,
    underline TEXT,
    font_color TEXT
);
```

#### 10. **cell_comment**
Cell comments and notes.
```sql
CREATE TABLE cell_comment (
    cell_id INTEGER PRIMARY KEY REFERENCES cell (cell_id),
    author TEXT,
    body TEXT NOT NULL
);
```

---

### Formula and Reference Tables

#### 11. **cell_reference**
Formula token references (cells, ranges, external links).
```sql
CREATE TABLE cell_reference (
    cell_reference_id INTEGER PRIMARY KEY AUTOINCREMENT,
    from_cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    token_index INTEGER NOT NULL,
    raw_token TEXT NOT NULL,
    ref_kind TEXT NOT NULL,            -- 'cell' | 'range' | 'external' | 'named' | 'function'
    target_sheet_name TEXT,
    target_worksheet_id INTEGER REFERENCES worksheet (worksheet_id),
    target_range TEXT,
    resolved_cell_id INTEGER REFERENCES cell (cell_id),
    external_link_id INTEGER REFERENCES external_link (external_link_id),
    abs_row INTEGER,                   -- 1 = absolute, 0 = relative
    abs_col INTEGER,
    row_offset INTEGER,
    col_offset INTEGER,
    is_whole_column INTEGER NOT NULL DEFAULT 0,
    is_whole_row INTEGER NOT NULL DEFAULT 0,
    unresolved_reason TEXT             -- Reason if reference could not be resolved
);
-- Indexes: idx_cellref_from, idx_cellref_resolved, idx_cellref_unresolved, idx_cellref_external
```

#### 12. **formula_link**
Direct formula dependencies (A1 cell:  B1→ A1 formulas referencing B1).
```sql
CREATE TABLE formula_link (
    from_cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    to_cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    PRIMARY KEY (from_cell_id, to_cell_id)
);
-- Index: idx_formula_link_to
```

#### 13. **formula_reach**
Transitive formula dependencies (calculated via graph traversal).
```sql
CREATE TABLE formula_reach (
    from_cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    to_cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    depth INTEGER NOT NULL,             -- Depth in dependency graph
    PRIMARY KEY (from_cell_id, to_cell_id)
);
-- Index: idx_formula_reach_to
```

#### 14. **formula_gap**
Unresolved formula references.
```sql
CREATE TABLE formula_gap (
    from_cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    reason TEXT NOT NULL,               -- e.g., "external_link", "undefined_name"
    raw_token TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (from_cell_id, reason, raw_token)
);
```

---

### Region Identification & Classification Tables

#### 15. **candidate**
Identified financial data regions (parent and child regions).
```sql
CREATE TABLE candidate (
    candidate_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    worksheet_id INTEGER NOT NULL REFERENCES worksheet (worksheet_id),
    candidate_kind TEXT NOT NULL,      -- 'coverage_parent' | 'child' | 'parallel' | 'overlap' | 'related'
    parent_candidate_id INTEGER REFERENCES candidate (candidate_id),
    bbox_min_row INTEGER,
    bbox_min_col INTEGER,
    bbox_max_row INTEGER,
    bbox_max_col INTEGER,
    internal_whitespace TEXT,          -- JSON metadata on whitespace
    anchors TEXT,                      -- JSON array of anchor cells
    structural_signatures TEXT,        -- JSON object of detected patterns
    isolated_hidden_worksheet INTEGER NOT NULL DEFAULT 0,
    structural_confidence REAL,
    structural_confidence_rationale TEXT,
    explanation TEXT,
    created_at TEXT NOT NULL,
    structural_role TEXT                -- 'main' | 'helper' | 'scratch'
);
-- Indexes: idx_candidate_parse_run, idx_candidate_worksheet, idx_candidate_parent
```

#### 16. **candidate_member**
Cell membership in candidates (many-to-many).
```sql
CREATE TABLE candidate_member (
    candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id),
    cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    PRIMARY KEY (candidate_id, cell_id)
);
-- Index: idx_candidate_member_cell
```

#### 17. **candidate_related**
Relationship between candidate regions (overlap, adjacency, etc.).
```sql
CREATE TABLE candidate_related (
    candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id),
    related_candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id),
    relationship_kind TEXT NOT NULL,   -- 'adjacent', 'overlaps', 'parent_child', etc.
    PRIMARY KEY (candidate_id, related_candidate_id, relationship_kind)
);
-- Index: idx_candidate_related_target
```

#### 18. **packet_disposition**
Classification and disposition of each candidate (financial schedule type, triage, relevance).
```sql
CREATE TABLE packet_disposition (
    disposition_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id),
    schedule_family TEXT NOT NULL,      -- Schedule type (e.g., 'cost', 'revenue', 'financing')
    triage TEXT NOT NULL,               -- 'main' | 'scratch' | 'orphan'
    relevance TEXT NOT NULL,            -- 'primary' | 'supporting' | 'noise'
    row_labels TEXT,                   -- JSON array of row labels
    column_headers TEXT,               -- JSON array of column headers
    packet_default_head TEXT,
    about TEXT NOT NULL,               -- Human-readable description
    parent_candidate_id INTEGER REFERENCES candidate (candidate_id),
    cheap_pass INTEGER NOT NULL,       -- 1 = quick classification, 0 = full analysis
    created_at TEXT NOT NULL,
    stated_scale TEXT,                 -- 'unit' | 'thousand' | 'lakh' | 'million' | 'crore' | 'billion'
    scale_evidence_cell TEXT,
    UNIQUE (parse_run_id, candidate_id)
);
-- Indexes: idx_packet_disposition_parse_run, idx_packet_disposition_candidate
```

#### 19. **region_triage_opinion**
LLM model opinion on candidate triage (main/scratch/orphan).
```sql
CREATE TABLE region_triage_opinion (
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id),
    model TEXT NOT NULL,               -- Model ID (e.g., 'liquid/d1')
    choice TEXT NOT NULL,              -- 'main' | 'scratch' | 'orphan'
    confidence REAL NOT NULL,
    p_main REAL,
    p_scratch REAL,
    p_orphan REAL,
    escalated INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (parse_run_id, candidate_id)
);
```

#### 20. **region_header_geometry**
Row/column header structure of regions.
```sql
CREATE TABLE region_header_geometry (
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id),
    geometry_json TEXT NOT NULL,       -- JSON describing row/column label geometry
    created_at TEXT NOT NULL,
    PRIMARY KEY (parse_run_id, candidate_id)
);
```

---

### Cell Interpretation & Nomenclature Tables

#### 21. **nomenclature_node**
Hierarchical taxonomy of financial data concepts (spine, industry-specific, mandate-specific).
```sql
CREATE TABLE nomenclature_node (
    node_id INTEGER PRIMARY KEY AUTOINCREMENT,
    path TEXT NOT NULL,                -- Hierarchical path (e.g., 'economic/revenue/tariff/')
    name TEXT NOT NULL,
    parent_path TEXT,
    layer TEXT NOT NULL,               -- 'spine' | 'industry' | 'mandate_soft'
    frozen INTEGER NOT NULL,           -- 1 = immutable, 0 = editable
    leaf INTEGER NOT NULL,
    industry_tag TEXT,
    mandate_id INTEGER,
    created_at TEXT NOT NULL
);
-- Indexes: idx_nomenclature_node_spine_path, idx_nomenclature_node_industry_path, idx_nomenclature_node_mandate_path, idx_nomenclature_node_parent, idx_nomenclature_node_mandate
```

#### 22. **nomenclature_alias**
Alternative names and spellings for nomenclature concepts.
```sql
CREATE TABLE nomenclature_alias (
    alias_id INTEGER PRIMARY KEY AUTOINCREMENT,
    alias_text TEXT NOT NULL,
    leaf_path TEXT NOT NULL,           -- Path in nomenclature_node
    layer TEXT NOT NULL,               -- 'spine' | 'industry' | 'mandate_soft'
    industry_tag TEXT,
    mandate_id INTEGER,
    created_at TEXT NOT NULL
);
-- Indexes: idx_nomenclature_alias_spine_text, idx_nomenclature_alias_industry_text, idx_nomenclature_alias_mandate_text, idx_nomenclature_alias_leaf
```

#### 23. **nomenclature_binding**
Binding of cells to nomenclature concepts (cell ↔ concept mapping).
```sql
CREATE TABLE nomenclature_binding (
    binding_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    candidate_id INTEGER NOT NULL REFERENCES candidate (candidate_id),
    cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    path_root TEXT NOT NULL,           -- 'economic' | 'identity' | 'frame'
    path TEXT NOT NULL,                -- Nomenclature path
    amount_role TEXT,                  -- 'add' | 'deduct' | 'total' | 'helper'
    verbatim TEXT,
    created_at TEXT NOT NULL,
    UNIQUE (parse_run_id, cell_id)
);
-- Indexes: idx_nomenclature_binding_parse_run, idx_nomenclature_binding_path, idx_nomenclature_binding_cell
```

#### 24. **cell_interpretation**
Cell-level semantic interpretation (value, origin, nomenclature status).
```sql
CREATE TABLE cell_interpretation (
    interpretation_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    value_origin TEXT NOT NULL,        -- 'literal' | 'formula'
    resulting_value TEXT,
    result_source TEXT NOT NULL,       -- 'literal' | 'formula_cache' | 'missing_cache'
    formula_text TEXT,
    formula_state TEXT,
    cache_state TEXT,
    is_error INTEGER NOT NULL DEFAULT 0,
    error_type TEXT,
    nomenclature_path TEXT,
    amount_role TEXT,                  -- 'add' | 'deduct' | 'total' | 'helper'
    soft_leaf INTEGER,                 -- 1 = soft leaf in nomenclature, 0 = hard leaf
    via_alias INTEGER,                 -- 1 = bound via alias, 0 = direct match
    nomenclature_status TEXT NOT NULL, -- 'bound' | 'unbound' | 'not_applicable'
    formula_gloss TEXT,                -- Human-readable formula interpretation
    unbound_reason TEXT,
    created_at TEXT NOT NULL,
    UNIQUE (parse_run_id, cell_id)
);
-- Indexes: idx_cell_interpretation_status, idx_cell_interpretation_path
```

#### 25. **cell_interpretation_evidence**
Supporting evidence for cell interpretations (row headers, column headers, periods, scales, currencies).
```sql
CREATE TABLE cell_interpretation_evidence (
    evidence_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL,
    cell_id INTEGER NOT NULL,
    role TEXT NOT NULL,                -- 'row_header' | 'column_header' | 'period' | 'basis' | 'currency' | 'scale' | 'unit'
    source_cell_id INTEGER REFERENCES cell (cell_id),
    source_text TEXT,
    ordinal INTEGER NOT NULL,          -- Order of evidence
    resolution TEXT NOT NULL,          -- 'resolved' | 'missing' | 'ambiguous'
    normalized_value TEXT,
    rule_id TEXT,
    FOREIGN KEY (parse_run_id, cell_id) REFERENCES cell_interpretation (parse_run_id, cell_id)
);
-- Index: idx_cell_interpretation_evidence_cell
```

#### 26. **cell_reading**
Typed numeric and monetary amounts extracted from cells.
```sql
CREATE TABLE cell_reading (
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    kind TEXT,                         -- 'money' | 'quantity' | 'rate' | 'percent' | 'count' | 'ratio'
    scale TEXT,                        -- 'unit' | 'thousand' | 'lakh' | 'million' | 'crore' | 'billion'
    unit TEXT NOT NULL DEFAULT '',
    currency TEXT NOT NULL DEFAULT '',
    absolute_amount TEXT,              -- Standardized monetary amount
    type_source TEXT,                  -- 'input' | 'derived'
    refusal TEXT,                      -- 'untypable' | 'kind_conflict' | 'cycle' | 'external_dependency'
    scale_basis TEXT,                  -- 'stated' | 'inferred'
    PRIMARY KEY (parse_run_id, cell_id)
);
-- Index: idx_cell_reading_kind
```

---

### Provenance & Quality Tables

#### 27. **provenance**
Audit trail of data source and derivation.
```sql
CREATE TABLE provenance (
    provenance_id INTEGER PRIMARY KEY AUTOINCREMENT,
    entity_type TEXT NOT NULL,         -- 'cell' | 'worksheet' | 'workbook' | 'parse_run'
    entity_id INTEGER NOT NULL,
    source_file_id INTEGER NOT NULL REFERENCES source_file (source_file_id),
    parse_run_id INTEGER REFERENCES parse_run (parse_run_id),
    location TEXT NOT NULL,
    raw_value TEXT,
    confidence REAL,
    is_derived INTEGER NOT NULL DEFAULT 0,
    notes TEXT
);
-- Indexes: idx_provenance_entity, idx_provenance_source_file
```

#### 28. **review_queue**
Issues, anomalies, and items requiring human review.
```sql
CREATE TABLE review_queue (
    review_queue_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    category TEXT NOT NULL,            -- e.g., 'formula_reference', 'unbound_cell', 'conflict'
    summary TEXT NOT NULL,
    detail TEXT,                       -- JSON object with details
    status TEXT NOT NULL DEFAULT 'Pending',  -- 'Pending' | 'Reviewed' | 'Resolved' | 'Ignored'
    is_escalated INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    resolved_at TEXT,
    subject_kind TEXT,
    subject_key TEXT,
    confidence REAL,
    carried_from_decision_id INTEGER
);
-- Index: idx_review_queue_parse_run
```

#### 29. **ingest_rejection**
Records of files rejected during ingest.
```sql
CREATE TABLE ingest_rejection (
    ingest_rejection_id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_file_id INTEGER REFERENCES source_file (source_file_id),
    mandate_id INTEGER NOT NULL,
    file_name TEXT NOT NULL,
    file_hash TEXT NOT NULL,
    reason TEXT NOT NULL,
    detail TEXT,                       -- JSON object
    rejected_at TEXT NOT NULL
);
-- Indexes: idx_ingest_rejection_source_file, idx_ingest_rejection_file
```

#### 30. **audit_log**
Event log for debugging and auditing.
```sql
CREATE TABLE audit_log (
    audit_log_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER REFERENCES parse_run (parse_run_id),
    event_type TEXT NOT NULL,
    event_at TEXT NOT NULL,
    payload TEXT,                      -- JSON object
    severity TEXT NOT NULL              -- 'info' | 'warning' | 'error'
);
-- Index: idx_audit_log_parse_run
```

---

### Performance & Metadata Tables

#### 31. **run_timing**
Execution time for each pipeline stage.
```sql
CREATE TABLE run_timing (
    timing_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    stage TEXT NOT NULL,               -- 'ingest' | 'discover' | 'region-layout' | 'layer-a' | 'layer-b'
    started_at TEXT NOT NULL,
    finished_at TEXT NOT NULL,
    duration_millis INTEGER NOT NULL,
    item_count INTEGER,                -- Items processed in this stage
    recorded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (parse_run_id, stage)
);
-- Indexes: idx_run_timing_parse_run, idx_run_timing_stage
```

#### 32. **llm_usage**
LLM API calls and token usage per stage/model.
```sql
CREATE TABLE llm_usage (
    usage_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    stage TEXT NOT NULL,               -- 'region-layout' | 'layer-a' | 'layer-b' | 'other'
    model_id TEXT NOT NULL,            -- e.g., 'openai/gpt-6-luna', 'xiaomi/mimo-v2.6-flash'
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
-- Indexes: idx_llm_usage_parse_run, idx_llm_usage_model
```

#### 33. **run_stat**
Generic statistics logged during pipeline execution.
```sql
CREATE TABLE run_stat (
    stat_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    stage TEXT NOT NULL,               -- 'run' | 'ingest' | 'discover' | 'region-layout' | 'layer-a' | 'layer-b'
    name TEXT NOT NULL,                -- Statistic name
    value_num REAL,
    value_text TEXT,
    recorded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (parse_run_id, stage, name)
);
-- Index: idx_run_stat_parse_run
```

#### 34. **mandate_industry**
Industry classification for a mandate.
```sql
CREATE TABLE mandate_industry (
    mandate_id INTEGER PRIMARY KEY,
    industry_tag TEXT NOT NULL,
    confirmed INTEGER NOT NULL,
    inferred INTEGER NOT NULL,
    updated_at TEXT NOT NULL
);
```

#### 35. **schedule_family**
Catalog of financial schedule family names.
```sql
CREATE TABLE schedule_family (
    name TEXT PRIMARY KEY,
    created_at TEXT NOT NULL
);
```

#### 36. **project_fact_field**
Catalog of standard project fact fields.
```sql
CREATE TABLE project_fact_field (
    field_id INTEGER PRIMARY KEY AUTOINCREMENT,
    path TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    created_at TEXT NOT NULL
);
```

---

## Key Statistics

| Metric | Value |
|--------|-------|
| Total Worksheets | 10 |
| Total Cells Ingested | 19,470 |
| Total Candidates | 77 (66 with dispositions, 11 skipped) |
| Total Rows Analyzed | 727 |
| Coverage Parents | 10 (1 per worksheet) |
| Child Regions | 66 |
| Cells with Formulas | ~14,900 |
| Classified Dispositions | 66 |
| Primary Dispositions | 26 |
| Supporting Dispositions | 33 |
| Noise/Orphan Dispositions | 7 |
| Review Queue Items | 426 (formula reference issues) |
| LLM Total Cost | $1.194576 USD |
| Total Classification Time | 1317s (~22 min) |

---

## Query Examples for Analysis

### Get all classified regions with their types
```sql
SELECT w.sheet_name, c.candidate_kind, pd.about, pd.triage, pd.relevance
FROM packet_disposition pd
JOIN candidate c ON pd.candidate_id = c.candidate_id
JOIN worksheet w ON c.worksheet_id = w.worksheet_id
WHERE pd.parse_run_id = 1
ORDER BY w.sheet_name, c.candidate_id;
```

### Find unresolved formula references
```sql
SELECT COUNT(*) as unresolved_count, summary
FROM review_queue
WHERE parse_run_id = 1 AND category = 'formula_reference'
GROUP BY summary;
```

### Get cell interpretation status by nomenclature binding
```sql
SELECT ci.nomenclature_status, COUNT(*) as cell_count
FROM cell_interpretation ci
WHERE ci.parse_run_id = 1
GROUP BY ci.nomenclature_status;
```

### List cells with formula errors
```sql
SELECT c.coord, c.formula_text, c.error_type, w.sheet_name
FROM cell c
JOIN worksheet w ON c.worksheet_id = w.worksheet_id
WHERE c.is_error = 1 AND w.parse_run_id = 1
ORDER BY w.sheet_name, c.coord;
```

---

## Notes on Data Quality

- **_xlfn Prefix Handling:** Formulas containing newer Excel function prefixes (e.g., `_xlfn.MINIFS`) have been normalized in both `cell.formula_text` and `cell.formula_normalized` to remove the prefix.
- **Parser Version:** Fixed parser applied to handle `_xlfn.*` function prefixes from Excel 2019+ formulas.
- **Unresolved References:** 426 items flagged in review_queue for unresolved function references (mostly EOMONTH, EDATE, COUNTIFS, MINIFS, SUMIFS).
- **Memory Configuration:** Pipeline executed with 8GB JVM heap (-Xmx8g) to accommodate large formula graph construction.
