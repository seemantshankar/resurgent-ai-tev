# Layer B types the hardcoded inputs and propagates through formulas

Layer B bound money lines by asking an LLM per cell, over packets chunked 40 amount
cells at a time. On the working client FM (8,791 numeric cells) that produced 419
bindings, 8 of 60 binding-bearing rows carrying more than one path, 8 of 64 labels
bound to conflicting paths, 5 labels given conflicting roles, and 30% of numeric
cells left unbound inside rows that already had bindings. An insurance premium was
bound to `Project Cost > Civil Works > Building`; a Building block's depreciation
was filed under Furniture & Fixtures.

**Presentation is explicitly not used, and this is not up for rediscovery.** The
first replacement inferred structure from how the sheet looks: bold as section
headers, double borders as totals, blank rows as section ends, column position as
period band, header wording as column kind. Prototyped across all 46 tabs, it failed
differently on each — a title banner read as a header row, a row projection that
bound 380 guests and a ₹5,000 tariff as money, a dimensionless power factor bound as
a cost, a label column detected one column off. Reach went 51%, 62%, 80%, then 69%
once an error was corrected; every fix broke another tab. The flaw is not in any
rule. Bold, borders, blank rows and header wording are optional and author-specific,
so such a rule set can only grow and never converge.

**Decision.** A worksheet has only two kinds of numeric cell: inputs, which are
hardcoded, and everything else, which a formula derives from them. Layer B types the
inputs from their own row labels and lets the formulas carry that forward, because a
formula states exactly what it does. On the working FM that is roughly 600 input
decisions instead of 8,791 cell judgments.

Composition, sign and role come from the formula with no string matching. A `SUM`
over a range or a plus-minus chain is an aggregation head, and its members are read
from the formula rather than guessed from layout: `SUM(J33:J54)` *is* the
operating-cost group. A summand is `add`; a subtracted term is `deduct`, taken from
the operator and not from a `Less:` prefix. An operand of `*` or `/` is a driver, so
it can only ever be a helper. Identical R1C1-relative formulas identify a period
series without needing a header row at all.

**Units and scale are resolved once per aggregation, not per cell.** A cell that
adds into a guest-count total is correctly `add` within a group that is entirely
guests, and a non-money group can never carry a cost role. Scale propagates with the
dimension, so a figure divided by 100,000 is money in lakhs, and two members of one
aggregation at conflicting scale are refused rather than silently added. Rupees and
lakhs sit in one block on this FM, so this is not hypothetical.

**Refusal is the default.** Nothing incorrect is ever written to
`nomenclature_binding`. A kind or scale conflict, a chain through a `#REF!` or an
external workbook, a reference cycle, a driver, and a number no label names all end
as an explicit `unbound_reason`. That is a principled boundary, not an accumulating
pile of exceptions.

**The LLM is asked once per group, with a request-scoped cache.** Where the graph
proves the role but the catalog does not hold the name, the question is queued by
qualified label — the group's label plus the member's — and asked once. The key
excludes candidate, chunk, column and coordinate, which are exactly the axes along
which the same line used to get different answers. Answers are cached for the
request only; the graph is what gets persisted, and that is what makes a run
reproducible. Every synthesised packet is number-redacted at send time and carries
one Candidate, per ADR 0008.

**The formula role gate is replaced by the aggregation-head test.** 71% of numeric
cells are formulas, and the gate forced every one of them to `helper` or `total`, so
every `add` in a run was a literal and the binding table contributed nothing to any
`SUM(path)`. Its real purpose was anti-double-counting, which the graph tests
directly: a cell is a rollup exactly when it heads an aggregation.

This follows ADR 0012's precedent that a structure-finding pass is deterministic Java
and stays small. It is not the pre-LLM heuristic stack: no cost-head rollup, no
worksheet-role scoring, no trusted totals, no golden snapshots.

**Cost.** Coverage now depends on what can be proven plus what one naming question
per label answers. A run whose model answers nothing binds only what the catalog
already names — on the working FM, 47 bindings across 9 rows, with 845 rows waiting
on a name. That is the intended trade: fewer bindings, none of them wrong, and the
remaining judgment reduced to a few hundred named groups instead of thousands of
cells.
