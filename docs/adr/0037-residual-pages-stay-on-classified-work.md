# Residual file pages stay on the classified work, not an unknown sibling

When one file holds a classified work plus leftover pages that still look inducible, classify was minting a second logical document typed `unknown`. Extract and checklist then treated that tail as its own work, splitting shareholding notes and similar schedules away from the audited financials they belong with.

**Status.** Accepted.

**Decision.** Residual pages that pass the inducible gate attach to the open or preceding classified work on the same file ([ADR 0036](0036-labeled-line-widens-the-matched-pack.md): entity names stay row values, not new scalar field keys). When at least one classified work exists on the file, classify does not call `insertWork` for an unknown sibling from leftover pages. Pack growth for new repeating rows uses `tables[]` extension on the matched type (for example `shareholding` on `financials.audited_summary`), not an induced unknown sidecar document. A file with no classified work may still mint unknown when inducible pages remain.

**Consequences.** Multi-document membership and mandate-wide extract ([issue #210](https://github.com/seemantshankar/resurgent-ai-tev/issues/210)) read facts on the financial document id, not a spurious unknown sibling. Schedule widening via PackWidener can follow when table induction is automated; the seed table defines the shape first.
