-- M6-1 (#189): a mandate spend ceiling. NULL means no ceiling. Zero means no cloud call.
-- The platform default lives on the platform registry, not in this workspace.

ALTER TABLE mandate_policy ADD COLUMN spend_ceiling_usd REAL;
