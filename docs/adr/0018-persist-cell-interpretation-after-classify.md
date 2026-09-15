# Persist cell interpretation after classification

Accepted. Persist a replaceable Cell interpretation for every cell in a parse run's coverage universe after classification. Header and comparison-context evidence land in a follow-up (#117). This supports BRD Phase 1 comparisons and later FM generation while preserving the meaning-free ingest boundary in ADRs 0009 and 0013.

## Ownership and lifecycle

The cell graph remains the source of workbook facts and Layer B remains the source of nomenclature bindings. Interpretation denormalizes presentation and lean binding fields as one consistent snapshot. Classification replaces that snapshot atomically with its Layer A/B outputs. Successful rediscovery deletes interpretations in the same transaction that replaces Candidates. A failed classify or failed rediscovery leaves the prior snapshot intact. Parse runs stay isolated.

Interpretation is not another authoritative amount store and not a history of analyst decisions. A binding is a model judgment, not validation or analyst approval. Snapshots must not store future approved edits.

## Coverage

Coverage is every persisted cell in the parse run — including hidden cells, styled blanks, and merged participants. No second occupancy filter. Merged participants do not invent economic amounts; they still receive an interpretation with `nomenclature_status = not_applicable` when unbound.

## Status rules

1. Existing Layer B binding → `bound` (lean path/role/soft/alias snapshot).
2. No binding + clearly non–Layer-B cells (merged participants, blanks, nonnumeric literals) → `not_applicable`.
3. Remaining numeric cells and formulas → `unbound`.

## Application seam

`InterpretationWriter` accepts the caller's repository/transaction and does not commit. Classify writes interpretations after Layer A/B/peer/fact rows and before commit. Cell-meaning returns the optional interpretation when present.
