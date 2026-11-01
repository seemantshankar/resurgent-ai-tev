# A labeled line or a stated claim the pack does not name is a fact, and that document widens the pack

A match on a document-type pack was being treated as a complete schema. Pages that stated TAN, a registered office, or a signatory produced no fact unless the pack already had a schema field key, so a thin checked-in pack never grew and a richer later document of the same type never widened it.

**Status.** Accepted.

**Decision.**

A labeled line is a fact, with its verbatim quote taken from layout text, whether or not the matched pack already names that label. Local layout is read first. Schema-guided cloud extract fills schema field keys that reading still left empty, and only when data residency allows it. A labeled line whose label is already one of those fields is that field's fact. Any other value-bearing labeled line is stored beside them. Page furniture is not a fact. An unexplained figure stays a fact and does not become a leaf.

Fact binding then places each new fact on the living master, or leaves it unbound. A bound fact whose key passes the existing induction gates — no entity token in the key, path under a spine root — becomes one optional leaf on the matched pack. That leaf is published before the run ends. One document is enough. Prior paths stay, and a new leaf does not become required. The next document of that type loads the highest version it can see. A fact binding leaves unbound does not add a leaf. A label that restates a leaf the pack or the living master already has is that leaf. The stored fact is the new leaf's fact; the run does not extract the new key a second time. The checked-in pack file is not edited.

A standard attribute the page never states does not become a leaf. An unfamiliar document still starts a pack under [ADR 0030](0030-autonomous-web-grounded-pack-induction.md); labeled lines that pack missed follow this same rule before the run ends. Publishing follows [ADR 0032](0032-publish-learned-packs-by-copy.md): a gate failure publishes nothing, and the mandate keeps the version it has.

This pass widens scalar leaves only. A label beside its value, including a two-column list with one identity pair per row, is a labeled line. A schedule line is a repeating record — a facility, a month, a transaction, a parcel — and stays on the schedule-line reader. A clause stays on clause reading, including open extraction. Neither a schedule line nor a clause adds a table or a clause type to the pack in this pass.

A sentence that is not a labeled line may assert several stated claims. Each asserted value is its own fact, and the verbatim quote is that sentence. A word with no recorded value, and the entity the sentence is about, are not stated claims. The sentence “the land is marked agricultural and conversion to industrial has been initiated” is three facts: present marking agricultural, conversion initiated, and target use industrial. A place phrase is one stated claim, as a registered office is one value. A stated claim that restates a labeled line already stored is that fact. Local reading runs first. When it leaves the sentence unread and data residency allows a cloud call, that call names the stated claims. A local-only mandate keeps the unread sentence as the words on the page. A bound stated claim adds an optional leaf under the same gates as a labeled line.

On a document type that carries clauses, clause reading takes its sentences first. A sentence it keeps is not split into stated claims. Other prose on that document may still yield stated claims. A force majeure provision stays one clause. A land paragraph on a project report is stated claims.

A fact joins the comparison only when fact binding stores it on the living master, the same nomenclature a financial-model cell is bound to. Document-to-document and document-to-model comparison use that path, so two schema field keys on one path are one data point. A real value that matches no existing head is given a new head on this mandate's living master and bound there. The head is a group and a measure, created with the first measure: BOD becomes `Environment > BOD`, and COD from the same report becomes `Environment > COD`. A later wording of the same measure, such as biochemical oxygen demand, uses that same head. BOD is not filed under Project Cost. An existing head wins when it means the same thing. The new head stays in this mandate, so this mandate's documents and model can share it. A later mandate is given the published pack field and binds again. A label that is not a real value stays unbound, is still stored with its quote, and is not compared. A results row that states BOD 12 mg/L gets that same kind of name. The table stays a schedule. When a cloud call is not allowed, the measure is named from the words on the page and still bound; two different wordings may stay apart until a cloud call can judge that they are the same measure.

**Considered options.**

- Mining names the new leaves, then schema-guided extract fills them. Rejected: a label the miner does not propose never becomes a fact, which is the certificate-of-incorporation miss moved onto the miner.
- The labeled-line read replaces schema-guided extract. Rejected: a field the pack already names must keep its schema field key so fact binding and the financial-model join stay as they are.
- Wait for a second document of the type before widening. Rejected: the first document is enough, and a later mandate's first document of that type should already load the leaf.
- Store every quoted line and keep the refused ones as unbound facts. Rejected: page furniture is not a fact. A real value with no existing head is given a new head.
- Leave BOD unbound because the financial spine has no head for it. Rejected: the fact is given its own head on this mandate's living master and compared from there. It is not filed under Project Cost.
- Name the measure alone, with no group. Rejected: the head is a group and a measure, so COD sits beside BOD.
- Widen schedule tables and clause types in this same pass. Rejected: those readers already exist, and the gap was a scalar label.
- Leave a narrative sentence as page words, or keep only its figure and its date. Rejected: each value the sentence asserts is a fact. A conversion sentence yields the present marking, the initiation, and the target use.
- Store a word with no recorded value, or the entity the sentence is about, as a fact. Rejected: “large” has nothing to record, and the company is who the facts are about.
- Split a clause sentence into stated claims as well. Rejected: a force majeure provision stays one clause.

**Consequences.**

- [ADR 0030](0030-autonomous-web-grounded-pack-induction.md) §§1 and 4 still govern an unknown document and the preservation of prior paths. A classify hit no longer means the schema is complete.
- The local label pass and the cloud schema extract, as written, keep a line only when it matches a pack field. This decision says a value-bearing labeled line the pack does not name is stored.
- The binder, as written, leaves a fact with no pack field unbound and refuses a path whose parent is not already on the financial spine. This decision gives a real value with no head a new head on the mandate's living master and compares on that path.
- Two mandates can hold different pack versions until the next publish ([ADR 0032](0032-publish-learned-packs-by-copy.md)).
