# Parse formulas with POI, and salvage references when it fails

The parser strategy specifies a formula lexer/parser supporting quoted sheet names with embedded
spaces (`'P  L '!D23`), ranges, postfix `%`, `^`, `[n]` external references, defined names, and
error literals. We decided to **drive formula tokenization off Apache POI's `FormulaParser`** rather
than hand-writing a grammar, and to fall back on **reference salvage**, not on a second grammar,
when POI cannot parse a formula.

POI's grammar already covers the entire required list and is tested against far dirtier workbooks
than ours. Hand-writing an equivalent is roughly a sprint of work for parity at best, and every
quoted-sheet-name edge case in the findings document is a case POI already handles. The trade-off is
real, though: POI parses in workbook context and throws `FormulaParseException` on references it
cannot bind — a damaged external link part, an unknown defined name — where a bespoke parser could
have produced a syntax tree regardless.

That failure mode is what the fallback addresses. On `FormulaParseException` the parser scans the
formula text for reference tokens and records them as unresolved reference edges, marks the cell
`formula_state='parse_error'`, and produces no syntax tree, no skeleton, and no constant evaluation.
A damaged formula still tells us *what it points at*, which is what the discrepancy engine needs;
the salvage path keeps that without a second grammar to maintain. Sprint 1's regex-based reference
extractor, already written and tested, becomes exactly this path and is retired from the main one.

**Consequences**

- `formula_normalized` stays with our own normalizer: POI's token stream discards whitespace
  fidelity, and the strategy requires normalization that provably never alters quoted string
  literals.
- `parse_error` is an accounted-for outcome, not a failure: the QA gates reconcile
  `tokenized + parse_error + unavailable` against total formula cells, and parse errors are queued
  for review rather than flipping the run status.
- `unavailable` (the file withheld the formula, chiefly some legacy `.xls` records) is tracked as its
  own bucket. Folding it into `parse_error` would misreport where the failure happened.
- Salvaged edges are unresolved by construction, so they surface in the reference-reconciliation gate
  and the review queue instead of silently looking like resolved graph.
- The fallback has no instance in the reference workbook, so it is proven by a synthetic fixture
  built in-test.
