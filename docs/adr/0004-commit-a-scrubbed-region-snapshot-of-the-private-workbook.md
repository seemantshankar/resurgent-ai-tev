# Commit a scrubbed region snapshot derived from the private reference workbook

Sprint 3 is the first sprint whose output is not a pure function of the file. Sprints 1 and 2
produced artifacts reproducible from the bytes alone, and their tests could assert exact values
(`raw_token = '[15]Manpower!F35'`). Region detection produces judgements — whether `depreciation`
rows 10–55 are one region or three — and a single change to a break weight moves dozens of them at
once. We decided to **commit a golden snapshot of region output derived from the private reference
workbook**, scrubbed of all workbook-derived free text, and to diff it on every run.

The reference workbook is client-confidential and gitignored (`fixtures/private/`), so the obvious
alternative was to assert only the ten cases the strategy document names by hand. The workbook has
47 sheets. Ten hand-written assertions catch ten cases and give no cover at all for the other 46
sheets, which means the break weights could be retuned and a regression would stay invisible until
someone noticed a wrong total in an appraisal months later. Region detection is a tuned system with
no oracle; without a diffable baseline, tuning it is guesswork.

What makes this acceptable is the line between structure and content. The snapshot carries sheet
slugs, bounding boxes, region types, confidences, serial patterns, region keys, cost-head codes, and
detection reasons as stable enum codes with numeric parameters. It carries **no** labels, no header
token strings, no `cost_head_label`, and no values. Sheet names already appear in the strategy
document; a bounding box and a reason code disclose the shape of a spreadsheet, not a client's
financials. This constraint is what forces `detection_reasons` to be `{code, weight, params}` with a
read-time formatter rather than prose — prose would leak workbook text into a committed file.

We considered and rejected a **pseudonymised structural twin** of the workbook: same sheets, same
formulas, same merges and hidden flags, every literal replaced with a dummy. It would have let CI run
the whole pipeline instead of skipping. But formulas, merges and geometry survive pseudonymisation
while region *classification* keys on label text — header tokens, serial patterns, cost-head aliases.
A twin would validate roughly half of Sprint 3a and diverge silently on the rest. A half-valid
fixture that looks fully valid is a worse trap than an honest skip. Keeping the label text and
scrubbing only the numbers would have made the twin work, at the cost of committing every label
string in the workbook — a far larger disclosure than this snapshot, and not one worth making for
test cover.

**Consequences**

- The decision is effectively irreversible. Once the snapshot is in git history, removing it later
  requires rewriting history, so the scrubbing rule has to hold from the first commit rather than be
  tightened afterwards.
- The snapshot test skips wherever the workbook is absent, **including CI**. The regression net fires
  only on machines holding the workbook. This is the weakest link in the Sprint 3 test story, and it
  is weak for a reason we chose not to remove.
- The control against a rubber-stamped re-baseline is reviewer discipline, not automation. A CI check
  for "snapshot changed without justification" is unenforceable, because a legitimate re-baseline and
  a careless one are both a snapshot change committed alongside detector work. What we do instead is
  keep diffs readable: one file per sheet, so a change affecting one sheet is a one-file diff rather
  than 47-sheet churn, and regeneration is opt-in via `-Dsnapshot.update=true` so a test run can
  never silently bless a regression.
- Weights live in a hashed resource file rather than in code, partly so that a weights diff and a
  snapshot diff land in the same pull request and explain each other.
- Region output must be deterministic and canonically ordered for the snapshot to be diffable at
  all, which is a constraint on the detector, not just on the test.
