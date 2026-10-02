-- Money whose scale nothing states is no longer written as 'unit'. When no stated sheet reads
-- it either, it has no scale and no absolute amount. scale_basis says where a money scale came
-- from: 'stated' (its labels, formula, region or sheet, or cells that do) or 'inferred' (the
-- stated sheets that read it agree). SQLite has no ALTER CONSTRAINT, so the table is rebuilt.
-- Rows written before this migration keep a NULL scale_basis.

CREATE TABLE cell_reading_new (
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

INSERT INTO cell_reading_new (parse_run_id, cell_id, kind, scale, unit, currency,
        absolute_amount, type_source, refusal)
SELECT parse_run_id, cell_id, kind, scale, unit, currency,
        absolute_amount, type_source, refusal FROM cell_reading;

DROP TABLE cell_reading;
ALTER TABLE cell_reading_new RENAME TO cell_reading;

CREATE INDEX idx_cell_reading_kind ON cell_reading (parse_run_id, kind);
