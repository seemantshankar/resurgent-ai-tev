# Typing validation corrections (durable)

`target/TYPING_VALIDATION_REPORT.md` is gitignored, so this file records the
verified correction durably. The report's in-place "Correction (verified)" block
holds the full detail.

- **Gap 1 confirmed and fixed** (`CellGraphBuilder.shapeOf()`: test
  `MULTIPLICATIVE` before `BARE_REFERENCE`; `hasTopLevelOperator()` toggles
  quotes on `'` as well as `"`). Evidence: `P L!D46`
  `B46*'CAPITAL COST'!D20+...`, now `MONEY/UNIT`.
- **Gap 2 confirmed and fixed** (positional `NumericAtom` with `^` powers:
  `/10^5` is one `100000.0` `DIVISOR`; `=A1/2*2` roles are positional).
  Evidence: `ASSETS!I9`, now `MONEY/LAKH`.
- **Gap 3 refuted — no code change.** `parse()` already places edges inside
  parentheses; `I75*(1+D75)` yields `const(1.0)` + both edges. The 27 hits were
  the validation regex, now `GROWTH` (informational). Remaining refusals are
  genuine upstream `KIND_CONFLICT`s plus the `PERCENT` rate driver
  `P L!B45`/`B46`.
- **Cell identities:** `P L!J45` = "Building", `P L!J33` = "F & B sales";
  Gap 1 evidence is row 46. `SALESPROJECTION!J75` exists; `P L!J75` does not.
- **Coverage 79.9% (2,105/2,633)** holds post-fix; remainder is external links
  and orphaned labels. Locked by `CellGraphBuilderTest` (8 regression tests)
  and asserted critical cells (`P L!D46`, `ASSETS!I9`, `ASSETS!F21`) in
  `OmArhamRandomTypingValidationIT`.
