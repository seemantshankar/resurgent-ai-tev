-- Layer A may report the unit a region's money is shown in ("Rs. In Lacs") and the cell that
-- states it. Both are verified against the cell before use. Additive and nullable.
ALTER TABLE packet_disposition ADD COLUMN stated_scale TEXT
    CHECK (stated_scale IS NULL OR stated_scale IN (
        'unit', 'thousand', 'lakh', 'million', 'crore', 'billion'));
ALTER TABLE packet_disposition ADD COLUMN scale_evidence_cell TEXT;
