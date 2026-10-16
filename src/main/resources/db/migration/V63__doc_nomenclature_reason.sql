-- M3-17 (#153): a bound document fact records why it sits on that path — the alias or
-- living path that matched, or the model's own reason. Unbound facts keep their code in
-- unbound_reason; this column is the counterpart for the facts that did bind.
ALTER TABLE fact ADD COLUMN nomenclature_reason TEXT;
