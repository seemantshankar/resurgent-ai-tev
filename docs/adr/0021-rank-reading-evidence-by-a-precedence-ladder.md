# Rank reading evidence by a precedence ladder

A reading of a workbook cell can be right in shape and wrong in fact. Across the
Om Arham work, the same failure recurred: a defect hidden behind a defect. A percent
was typed from the percent factor alone because a money operand would not resolve;
the group that read it then refused, so the refusal looked like the problem and the
wrong kind underneath went unnoticed. `kind_conflict` rising 1,772 → 2,305 in the
scale-provenance change is the same shape: cells that had been reporting a scale
conflict now report the kind conflict it was masking. Total unbound is unchanged.

Each time, the fix came from the same habit, not from a new heuristic: check the
reading against a source that is independent of the labels that produced it, and rank
the evidence so a weaker cue can never outvote a stronger one. This decision names
that habit as four principles in precedence order, and names the existing rules as
instances of them so the next rule is written as another instance rather than as
another exception.

**Decision. The reading-evidence ladder, strongest first.**

1. **The workbook's own arithmetic outranks every reading of it.**
   A number and the expression that produces it are two independent statements; when
   they disagree, the reading is wrong. The extension idiom is the smallest instance:
   `97650 × 600 = 58,590,000` confirms the label reading against the value, because
   the multiplication is the workbook's own claim about what its numbers mean.
   `AggregationReconciliation` is the general form for scale: an additive head is
   defined by its formula, so after normalising every member and the head by its
   assigned scale the two sides must agree. A group that does not reconcile is proof
   that at least one assigned scale is wrong — it exposed `ASSETS!J54`, where
   `I54 = 120.2999616` and `J54 = 120.3` are the same number to seven figures typed
   100,000× apart, and whose net `K54 ≈ 0` is only possible if both are lakh.

2. **Structure outranks labels.** The operator, not the prose, says what a cell is.
   A `SUM` over a range proves its members share a kind (H7), so the group is typed
   from what the formula composes rather than from words that happen to repeat.
   The operator gives the role: a summand is `add`, a subtracted term is `deduct`,
   taken from `+`/`−` and never from a `Less:` prefix (ADR 0019). The aggregation
   scopes the invariant: units and scale resolve once per group, not per cell
   (ADR 0019), which is why a single stray scale in a group is a conflict and not a
   per-cell judgement.

3. **A confident reading outranks a guessed one.** A weak predicate marks a kind the
   arithmetic could only guess — a count times hardcoded constants may be a count or a
   per-unit amount — so a weak dissenter yields to agreeing strong members, and a
   winner with no supporter beyond the bare money default yields to nothing.
   `ScaleProvenance{STATED, ADOPTED, UNSTATED}` is the reference instance: a scale a
   cue named is **STATED**; a scale nothing named but an additive consumer proves is
   **ADOPTED** (`K54 = I54 - J54` proves `J54` is lakh when `I54` is); and a scale
   nothing supplied is **UNSTATED**. It is persisted on `cell_type` and reported
   through `CellMeaning`, so the unit default travels as an unqualified carrier value
   rather than as a confident claim. The rule is adopt, never assert: an unstated
   scale may be *upgraded* by a consumer, never *overwritten*, and only a claimed
   scale may source a further adoption, so a chain of unstated cells cannot invent a
   scale between them.

4. **A unit-of-measure cue outranks a subject-matter cue.** "Months'" outranks
   "Sales": a row led by a possessive period noun measures a holding period, so
   "Months' Export Sales" is a quantity, not money. "(Rs. In Lacs)" outranks
   "Room Sales": a row that states its own unit is authoritative for its own numbers
   over the column (ADR 0020), and a formula states its own scale in its divisor
   (`/10^5` → lakh, ADR 0020). A currency mark states the unit scale, so
   "Amount in Rs" is `STATED`/unit and a bare literal under a rupee banner is not.
   Subject-matter nouns (`Sales`, `Rooms`) name the entity, not the dimension.

**The ladder is a precedence, not a checklist.** Rules are read in order and a
stronger rung settles the question; a weaker rung is only consulted when the stronger
ones are silent. This is what stops "unit-of-measure beats subject-matter" from
degenerating into the abandoned presentation stack: the ban in ADR 0019 is on
optional, author-specific presentation used as *structure*, while a unit-of-measure
cue is explicit and authored for the purpose.

**The next rung, designed and not built: BANNER.** `ASSETS!I5 = '(Rs. In Lacs)'` is a
sheet-block banner that states a unit for a whole region. Cell-level label resolution
never sees it, because it is neither this cell's row label nor its column header.
Adoption already recovers every *bound* cell it consumes, which is why the four
project-cost deductions are correct; the banner would instead upgrade the **UNSTATED**
cells nothing consumes — the eight `BN_2!B7:I7` "F & B Sales" literals and
`'P  L '!N23`, which today report unstated. The rung is therefore
**STATED > ADOPTED > BANNER > UNSTATED**: it may upgrade a cell that is `UNSTATED`,
and it may never override a cell-level cue or an adoption. The caveat is ADR 0019's:
mixed scales can sit inside one block, so a banner is the weakest *claiming* rung and
is settled last among the claiming rungs. The check that says when it is safe is
principle 1 — if a banner-assigned scale breaks reconciliation for that block, the
banner does not apply to it.

**Consequences.** On Om Arham, `ScaleProvenance` plus additive adoption moved
bindings 141 → 143, `scale_conflict` 583 → 42, and
`unboundNumericInABoundRow` 11 → 9, with the project-cost deduction rollup reporting
₹5.9568 crore instead of ₹595.68. `AggregationReconciliation` verifies 71 additive
triplets with zero mismatches. It skips 2,897, and the skip is structural, not a
coverage gap in the check: 1,148 heads carry no numeric value, 764 heads never typed
(mostly kind conflicts and external dependencies), 800 heads have an unstated scale,
and only 185 skip on a member. The check therefore verifies the groups whose head is
itself a typed, claimed-scale money figure, which is what it is for. Adding the
additive-literal extraction to `CellGraphBuilder` fixed the one false positive it
found (`ASSETS!M66`, whose `-72 + 3675.2` never reached the dependency list).

**The ladder generalises; the graph does not yet.** Run offline (fake model, so only
graph-proven bindings) over three client models these rules were never written
against — Jettwings (25 tabs), Solar Consolidated (28), SA Hospitalities (44):

| | Jettwings | Solar | SA resort |
| --- | --- | --- | --- |
| numeric / typed | 19,186 / 14,782 | 42,830 / 26,333 | 13,143 / 7,815 |
| money STATED / ADOPTED / UNSTATED | 4,347 / 823 / 9,129 | 1,738 / 8,374 / 14,156 | 3,366 / 1,385 / 1,602 |
| triplets verified / mismatches | 544 / 0 | 1,803 / 2 | 933 / 22 |
| graph-proven bindings | 0 | 0 | 0 |

Adoption carries the scale work on unseen models: on Solar, `ADOPTED` (8,374)
outnumbers `STATED` (1,738) nearly five to one, because that workbook states its
scales once in a divisor or a banner and leaves the rest to be proven by its own
arithmetic. That is principle 1 and principle 3 doing exactly what they were written
to do on a workbook nobody tuned them on.

The 24 mismatches are not scale-rule failures. They are three defects the check
surfaced, and each is a place where the graph's reading of a formula is wrong rather
than a place where a scale was guessed:

- **A parenthesised subtraction is not sign-distributed.**
  `DSCR2!B37 = 'P&L'!B44-('P&L'!B9-DSCR2!B36)` is `A-(B-C) = A-B+C`, but both
  operands inside the parentheses are read as subtracted. 18 of SA's 22 mismatches
  are this one shape, repeated down a period series. It is worse than a total: the
  same sign drives the `add`/`deduct` role, so `B36` is filed as a deduction.
- **`=SUM(range) - X` drops the trailing term from the tracked members.**
  `stress2!C22 = SUM(C12:C21) - C13` reads back head 505.93 against members 597.40,
  a difference of exactly `C13`. The dependency list has the term; `members()` does
  not, so the head and its own member relation disagree.
- **A head typed at unit whose members are crore.** Solar `EMI chart!L19` and `M19`
  are `I123+I106+I107` across sheets: head 13.365 against members 1.3365e8, exactly
  10⁷. This is the `ASSETS!J54` class on a new workbook, and the check names it
  without being told what to look for.

A fourth pattern was a false positive in the check, now fixed: an addend that is a
function call — `TOTAL CURRENT ASSETS` as `ROUND(SUM(I261:I273),2)+I275+...` — is not
representable in the member relation, so the check skips any head with a non-summand
operand rather than reporting a difference it cannot explain. Solar went 74 → 2.

**What is still untested.** All three models produced zero graph-proven bindings:
every candidate queued for a name (`llm_declined` 10,735 / 17,770 / 3,899), so no
label resolved to a hard leaf of the ontology. The scale ladder is therefore proven
on unseen workbooks; the binding rate is not, because these workbooks' vocabulary is
outside the catalog. That is a nomenclature-coverage question, not a typing one, and
it is the next thing the census should measure.
