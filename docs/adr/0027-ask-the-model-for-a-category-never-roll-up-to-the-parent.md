# Ask the model for a category, never roll up to the parent

_Extends [ADR 0022](0022-close-schedule-family-and-refuse-transcribed-leaves.md)._

Commit `271197f` (issue #154) implemented ADR 0022 §2: a newly minted soft leaf that
copies its own row — `Supplier - Kone Brothers`, `GST @ 18%`, `Freight charges - Blue Dart` —
is refused as `transcribed_label` and never staged into the one shared nomenclature.

However, `doc/bind/LlmLeafAssignment.java:25-26` told the model "the label itself becomes the
leaf name", which was then immediately refused by `PackPathBinder.java:208`, causing
guaranteed wasted coverage. The Excel prompt (`LayerBPromptAssembler.java:18-22`) also
under-specified the prohibition and provided no examples. To recover coverage, rolling up
unbound lines to their parent head was suggested, but analysis of the codebase rejected
the premise.

**Decision.**

1. **Ask for a reusable category, not a row copy.** Both pipelines (document binding in
   `LlmLeafAssignment` and financial model classification in `LayerBPromptAssembler`)
   instruct the model to name a reusable category, explicitly stripping supplier, vendor or
   make names, tax rates, digits, `%`, quantities or "No. of", model numbers, specifications,
   and leading `Less:` / `Add:` prefixes. The `transcribed_label` refusal check
   (`OntologyVocabulary.transcribedLeaf`) remains as an assertion gate, not the primary mechanism.

2. **Roll-up to the parent is rejected.** The doc↔Excel join is exact full-path equality
   (`ConflictScan.java:163`, key at `FmConflictSources.java:62-64`) with no `SUM` or `GROUP BY`
   over paths in `src/main`. A roll-up either finds nothing or compares an individual line item
   component against the Excel total at that parent (`ConflictScan.java:210`). Furthermore,
   identity grouping by `"path:" + ontologyPath` (`ConflictScan.java:289-297`) would merge
   unrelated line items into fabricated doc↔doc conflicts.

3. **Dictate the naming convention in the prompt.** No normalizer in the codebase equates `&`
   with `and`: `OntologySlice.normalize` (`OntologySlice.java:255-258`) preserves `&`, `'`, `/`,
   `-`, and `OntologyVocabulary.norm` (`:60-70`) preserves `&` and `/`.
   `NomenclatureCatalog.leafPathForNormalizedAlias` uses the weak normalizer.
   While `Diesel Generators` matches `Diesel generators`, `Freight & Forwarding` will never match
   `Freight and Forwarding`. The convention is therefore explicitly dictated: Title Case; join
   words with `&` not `and`; a slash only as ` / ` with spaces on both sides; no trailing
   punctuation; first character a letter; at most 48 characters; always the full head chain under
   a known parent, never a bare leaf.

4. **An alias collision refuses the leaf but keeps the binding.** As pinned by
   `NomenclatureBindTest.anAliasCollisionIsRefusedAndRecordedWithoutFailingTheRun` (:212-235),
   when a newly minted leaf collides with an alias of another head, the leaf creation throws
   and is recorded (`alias_collisions = 1.0`), but the binding stands. The comparison join is
   a string lookup against FM bindings (`ConflictScan.java:163`) and never consults
   `nomenclature_node`.

5. **Consequences.** The document pipeline makes a single batched model call per bind run
   (`llmCalls = 1` at `PackPathBinder.java:167, 177`) against a frozen snapshot, and Excel
   classify runs regions in parallel within a round (`ClassifyService.java:1443-1444, 1456, 1518`).
   Without an in-flight duplicate guard or controlled vocabulary, canonical naming can produce
   sibling synonyms. A near-duplicate guard on minting and widening the shared spine are
   deferred to epic #128's M4 note.
