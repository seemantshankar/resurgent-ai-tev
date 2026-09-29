-- Deterministic cell reading for one parse run. One row per numeric cell.
-- A typed row has kind, scale, and type_source. A refusal has no kind.
-- An untyped number has neither: nothing was stated, and nothing is guessed.

CREATE TABLE cell_reading (
    parse_run_id INTEGER NOT NULL REFERENCES parse_run (parse_run_id),
    cell_id INTEGER NOT NULL REFERENCES cell (cell_id),
    kind TEXT,
    scale TEXT,
    unit TEXT NOT NULL DEFAULT '',
    currency TEXT NOT NULL DEFAULT '',
    absolute_amount TEXT,
    type_source TEXT,
    refusal TEXT,
    PRIMARY KEY (parse_run_id, cell_id),
    CHECK (kind IS NULL OR kind IN (
        'money', 'quantity', 'rate', 'percent', 'count', 'ratio')),
    CHECK (scale IS NULL OR scale IN (
        'unit', 'thousand', 'lakh', 'million', 'crore', 'billion')),
    CHECK (type_source IS NULL OR type_source IN ('input', 'derived')),
    CHECK (refusal IS NULL OR refusal IN (
        'untypable', 'kind_conflict', 'cycle', 'external_dependency')),
    CHECK (absolute_amount IS NULL OR kind = 'money'),
    CHECK (
        (refusal IS NULL AND kind IS NOT NULL AND scale IS NOT NULL AND type_source IS NOT NULL)
        OR (refusal IS NOT NULL AND kind IS NULL AND scale IS NULL
            AND type_source IS NULL AND absolute_amount IS NULL)
        OR (refusal IS NULL AND kind IS NULL AND scale IS NULL
            AND type_source IS NULL AND absolute_amount IS NULL)
    )
);

CREATE INDEX idx_cell_reading_kind ON cell_reading (parse_run_id, kind);
