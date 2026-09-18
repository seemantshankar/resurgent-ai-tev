# Address `target/TYPING_VALIDATION_REPORT.md` observations

## Goal

Fix the real cell-graph parser defects behind the report, empirically diagnose the
refusals the report mis-attributed, correct the report's inaccurate findings, and
turn the typing validation into assertions so the fixes are locked in.

## Verified findings (checked against code + workbook artifacts)

- **Gap 1 is real.** `CellGraphBuilder.shapeOf()` applies `stripSheetPrefix()` to the
  whole term *before* testing for top-level `*`/`/` (line 342). A term such as
  `B46*'CAPITAL COST'!D20` strips to `D20`, matches `SINGLE_CELL`, and is classified
  `BARE_REFERENCE` instead of `MULTIPLICATIVE`. Evidence:
  `Project Docs/om_arham_xlsx_output/formula-role-mismatch-sample.txt:21` and the
  `P L!D46` formula `B46*'CAPITAL COST'!D20+(...)`.
- **Gap 2 is real.** `topLevelConstants()` / `constantRole()` (lines 285–332) match
  `10^5` as two separate numerals (`10`, `5`), both `DIVISOR`, so scale moves by
  `/50` instead of `/100000`. Evidence:
  `assets-regions-only-report-v2.4.json:36` (`.../10^5`) plus the ASSETS cells listed
  in the report.
- **Gap 3 as written is not a defect.** `parse()` places every edge whose offset lies
  inside the term span (lines 176–180, 186–211), including references inside
  parentheses. `I75*(1+D75)` already yields `const(1.0) FACTOR`, `I75 FACTOR`,
  `D75 FACTOR`; the edge to `D75` is not lost. The report's 27 "Gap 3" cells come
  from a regex (`\(1[+-]` + `*`) in `OmArhamRandomTypingValidationIT`, not from a
  verified parser failure. The true cause of the remaining refusals must be diagnosed.
- **Report cell identities are partly wrong.** `live-pl-classify-report.txt:319,326,403`
  shows `P L!J45` = "Building" and `P L!J33` = "F & B sales", not "Insurance
  Premium" / "Depreciation"; the real Gap 1 evidence sits on row 46, not 45. Treat
  every specific claim in the report as unverified until the diagnostic reproduces it.
- **Latent bug found while scoping Gap 2.** `constantRole()` re-locates a constant by
  value, so repeated equal constants (e.g. `=A1/2*2`) both take the first
  occurrence's role. The Gap 2 refactor must make constant roles positional.
- **Latent bug in `hasTopLevelOperator()`.** It toggles quotes only on `"`, not `'`
  (lines 381–399), while `topLevelTerms()` toggles both. A quoted sheet name
  containing `/` or `*` (e.g. `'P/L'!A1`) is misread as `MULTIPLICATIVE`.

## Decision

Evidence-driven (user-selected): fix the two verified defects, diagnose before any
Gap 3 code change, correct the report, then assert outcomes. Do not implement the
report's Gap 3 "extract refs from parentheses" change — the code already does it.

## Tasks (ordered)

### 1. Gap 1 — fix `shapeOf()` and quote handling
File: `src/main/java/com/resurgent/tev/parser/classify/CellGraphBuilder.java`
- Reorder `shapeOf()` so the top-level `*/` test runs before the `SINGLE_CELL` test;
  keep `stripSheetPrefix()` only for the bare-reference test. Order:
  `MULTIPLICATIVE` → `BARE_REFERENCE` (with strip) → `SUM_CALL` → `OTHER`.
- Confirm `SUM(A1:A2)` stays `SUM_CALL`, `SUM(A1:A2)*2` stays `MULTIPLICATIVE`, and
  `'SHEET'!A1` stays `BARE_REFERENCE`.
- In `hasTopLevelOperator()`, toggle `inQuotes` on `'` as well as `"`, mirroring
  `topLevelTerms()`.

### 2. Gap 2 — positional numeric atoms with powers
File: same.
- Add a numeric-atom pattern covering `10^5` (integer exponent) and existing
  `1E-5` notation, e.g.
  `(?<![A-Za-z$\d.])\d+(?:\.\d+)?(?:[eE][-+]?\d+|\^\s*-?\d+)?(?![\d.])`.
- Add `record NumericAtom(double value, int start)` and `numericAtoms(String)`:
  evaluate `base^exp` via `Math.pow`; skip atoms whose value is not finite.
- Change `topLevelConstants(term, shape)` to return atoms (only for
  `MULTIPLICATIVE`). Change `constantRole` to take the atom's `start` offset and do
  the depth-0 `/` scan from there, instead of searching by value. Update the
  constant loop in `parse()` (lines 182–185).
- Remove `NUMBER_LITERAL` if it becomes unused.

### 3. Gap 3 — diagnose before changing anything
File: `src/test/java/com/resurgent/tev/parser/classify/ScratchOmTypingDiagnoseIT.java`
- Retarget the walk to the report's real cells: `P L` `J45`/`J53`/`J33`/`D46`,
  SALESPROJECTION `*(1+...)` cells, and the `depreciation!J33` chain.
- For each, print formula, row label, number format, each dependency's role /
  constant / barrier / target, and the unit or refusal reason.
- Run it and record the actual cause of each refusal. Candidate real causes to check
  (do not assume): a scale conflict from a `10^5` / `100000` divisor upstream
  (Gap 2), an input typed `PERCENT` from a row that is money, an unresolved or broken
  reference, or an unlabelled input.
- Fix only the cause the dump proves. If the dump confirms the `D75` edge is present,
  record that Gap 3 needs no code change.

### 4. Validation IT — assert outcomes, correct gap detection
File: `src/test/java/com/resurgent/tev/parser/classify/OmArhamRandomTypingValidationIT.java`
- Once Task 3 establishes true outcomes, replace `validateCellForDiagnostics` with
  `validateCellType(..., shouldType = true, expectedKind = ...)` for cells that must
  type. Leave genuine external/broken-dependency cells as diagnostics.
- Fix `logParserGapCandidates`: drop or relabel the Gap 3 regex as informational
  (growth-factor pattern, not a defect). Keep Gap 1/Gap 2 detection; keep Gap 1
  precise (`*`/`/` adjacent to a `'Sheet'!ref`).
- Recalibrate the input-coverage assertion to the measured post-fix value with a
  floor at/above the current 79.9%, not an unbacked 85%. Document why the remainder
  is legitimate (external links, orphaned labels).
- Update the class Javadoc and inline comments: remove the false Gap 3 claim and the
  wrong cell descriptions; state the corrected, verified status of each gap.

### 5. Unit regression tests
File: `src/test/java/com/resurgent/tev/parser/classify/CellGraphBuilderTest.java`
- Add helpers to create cells on a second worksheet and a sheet-qualified edge
  (`refKind="cell"`, `targetWorksheetId`, `targetRange`, optional `resolvedCellId`).
- Add:
  1. `=B1*'CAPITAL COST'!D20` → roles `FACTOR`,`FACTOR`; no aggregation.
  2. `='CAPITAL COST'!D20*B1` → same.
  3. `='CAPITAL COST'!D20` alone → `SUMMAND_PLUS`; no aggregation.
  4. `='P/L'!A1` → `BARE_REFERENCE` (locks the quote fix).
  5. `=B1/10^5` → exactly one constant `100000.0`, role `DIVISOR`.
  6. `=(B1+B2)/10^5` → same.
  7. `=A1/2*2` → constant roles `DIVISOR`,`FACTOR` (locks positional fix).
  8. `=A1*(1+B1)` → `B1` `FACTOR` present plus `const(1.0) FACTOR` (regression guard
     against the report's false Gap 3 claim).

### 6. Correct the report
- Update `target/TYPING_VALIDATION_REPORT.md` in place with a clearly marked
  "Correction (verified)" block: Gap 1/Gap 2 confirmed with evidence, Gap 3 refuted
  with the code path, corrected cell identities, and measured post-fix coverage.
- Because `target/` is gitignored, also record the durable correction in the
  `OmArhamRandomTypingValidationIT` Javadoc. Optionally add a short
  `docs/typing-validation-corrections.md` if the correction should survive builds.

## Validation

- `./mvnw -q -Dtest=CellGraphBuilderTest test` — new unit tests pass.
- `./mvnw -q test` — full suite; workbook ITs self-skip without fixtures.
- `./mvnw -q -Dtest=ScratchOmTypingDiagnoseIT test` — chain dump before/after.
- `./mvnw -q -Dtest=OmArhamRandomTypingValidationIT -Dtev.validateTyping=true test`
  — critical cells and input coverage.
- `./mvnw -q -Dtest=RealWorkbookLiveClassifyIT -Dtev.liveLlm=true test` — only with
  LLM env provisioned; self-skips otherwise.

## Risks

- Reordering `shapeOf()` turns D46-like terms from summands into factors, changing
  aggregation membership and downstream roles/bindings. Intended, but the full suite
  and live classify must confirm no regression.
- Fractional/huge exponents: support integer exponents only; skip non-finite results.
- The report's 85% input-coverage target is not evidence-backed; do not force it.

## Out of scope

- Multi-book validation (only `fixtures/private/OM Arham Ventures.xlsx` is present).
- Percent literals (`D63*10%`) and `^` as a general arithmetic operator in
  `shapeOf()` / `TypePropagation`.
- Ingest/tokenizer changes and any persisted schema change (graph is rebuilt per
  parse run).
