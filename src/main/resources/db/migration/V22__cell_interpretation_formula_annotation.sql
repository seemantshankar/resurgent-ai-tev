-- Formula dependency annotations for Cell interpretations (#118).
-- Ordered annotated references cascade with interpretation replacement.

CREATE TABLE cell_interpretation_formula_annotation (
    annotation_id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_run_id INTEGER NOT NULL,
    cell_id INTEGER NOT NULL,
    ordinal INTEGER NOT NULL,
    raw_token TEXT NOT NULL,
    ref_kind TEXT NOT NULL,
    target_sheet_name TEXT,
    target_range TEXT,
    completeness TEXT NOT NULL CHECK (completeness IN (
        'complete', 'incomplete', 'truncated', 'unresolved', 'external')),
    enclosing_function TEXT,
    shared_dependency_path TEXT,
    shared_dependency_kind TEXT CHECK (
        shared_dependency_kind IS NULL
            OR shared_dependency_kind IN ('leaf', 'ancestor')),
    FOREIGN KEY (parse_run_id, cell_id)
        REFERENCES cell_interpretation (parse_run_id, cell_id) ON DELETE CASCADE
);

CREATE INDEX idx_cell_interp_formula_ann_cell
    ON cell_interpretation_formula_annotation (parse_run_id, cell_id, ordinal);

CREATE TABLE cell_interpretation_formula_annotation_member (
    member_id INTEGER PRIMARY KEY AUTOINCREMENT,
    annotation_id INTEGER NOT NULL
        REFERENCES cell_interpretation_formula_annotation (annotation_id)
        ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    target_cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    target_coord TEXT NOT NULL,
    nomenclature_path TEXT,
    nomenclature_status TEXT
);

CREATE INDEX idx_cell_interp_formula_ann_member
    ON cell_interpretation_formula_annotation_member (annotation_id, ordinal);
