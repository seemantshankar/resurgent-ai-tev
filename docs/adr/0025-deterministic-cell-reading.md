# Deterministic cell reading

Kind and scale are a fact about a numeric cell, not a question for the model. The rebuild dropped the code that wrote them, and `V27` dropped `cell_type`. Classify still owns meaning (ADR 0009, ADR 0024): the formula graph is structure, and this pass is what that structure means.

**Decision.**

1. After classify, and again at the start of bind, replace `cell_reading` for the parse run. Every numeric cell gets a row: a number, or a formula whose cache is a number. Formula-error cells are skipped. Bind does not need the reading to name a path; a bind-only rerun still refreshes it. No model is asked.
2. An input (a hardcoded number, or a formula with no precedents) is typed from its row label, then from the Candidate-scoped column header only when the row is an entity name, then from its number format and display (ADR 0020). `%` is percent. A formula divisor such as `/10^5` states scale before either label. A cell outside any Candidate is typed from its format, display, and formula only.
3. A formula with precedents is typed from `formula_link` and the operators in `formula_text`, recomputed until a fixpoint. Quantity times rate is money. Money over money is a ratio. Money divided by 100,000 stays money and moves to lakhs. A product waits until every factor is typed. A partial product is not a reading.
4. Currency, in order: an ISO code the cell, its label, or its number format states; else a bare `$` takes the parse run's home currency; else money stays money with an empty currency. `₹`, `Rs.`, and `INR` are INR. A bare `$` is not invented as USD.
5. Home currency is one fact per parse run, read from text on any sheet. A country name, an Indian state, or a 6-digit PIN names India, which is what an address states when it says Guwahati, Assam, PIN-781122 and never the word India. The same reading maps Australia, Canada, the United States, Saudi Arabia, and the currency names already used for a cell. Two different currencies leave home unresolved, so a bare `$` stays unresolved too.
6. Money stores an absolute amount: the cached figure times the scale. A rate keeps its unit and currency and does not get an absolute total. The cached display figure is left as stored.
7. A number whose labels, format, and formula state nothing stays untyped. A conflict, a cycle, or an external dependency is a refusal (`kind_conflict`, `cycle`, `external_dependency`, `untypable`). No row is written as a guess. Aggregation membership and amount roles are not restored.

**Consequences.** A lakh figure becomes an absolute rupee amount without asking a model. A line marked USD stays USD in Guwahati. A formula that mixes kinds refuses instead of inheriting one factor. The graph remains structure; this pass remains classify's.
