package com.resurgent.tev.parser.classify;

/** Assembles the region-layout system/user messages (Model2 probe that worked). */
final class RegionLayoutPromptAssembler {

    static final String SYSTEM = """
            You classify regions on ONE Excel worksheet for a TEV clean financial-model extract.
            Return ONLY JSON (no markdown):
            {
              "main": [{"bbox":"A1:F10","label":"...","why":"..."}],
              "helper": [{"bbox":"A1:F10","label":"...","why":"..."}],
              "scratch": [{"bbox":"A1:F10","label":"...","why":"..."}]
            }

            DEFINITIONS
            - main = RETAIN for the model
            - helper = EXCLUDE from core model (audit / breakout / variance) — do not double-count
            - scratch = OMIT (floating orphans only)

            CRITICAL — DO NOT OVER-SPLIT MAINS
            - Prefer a SMALL number of large mains (ideally ~4 section mains + optional sheet title band).
            - A section MAIN must be ONE bbox that includes, together:
              section header + item/detail rows + official section total / Lacs summary figures
              for that section.
            - NEVER emit a main that is only a header row.
            - NEVER emit a main that is only a total row detached from its section.
            - Document title / units may be one small main OR absorbed into the first section main
              — but do NOT put floating scratch digits into main.

            HELPER (keep separate from mains)
            - Inline BoQ / vendor quote / green-style breakout blocks.
            - Side variance/scenario pads: alternate + difference columns. Prefer one helper bbox
              (or few) for that pad band, not dozens of singletons.
            - Official cost lines on the primary estimate column belong on the SECTION MAIN;
              only the breakout math under them is helper.
            - NEVER widen a section MAIN into side pad columns. If a column sits to the right of
              the primary amount column and looks like another amount band, treat it as helper
              unless you are sure it is part of the official section schedule.

            WHEN UNSURE — READ THE FORMULAS
            - The dump shows formulas (not only cached values). Use them before deciding.
            - Difference / variance / scenario formulas (e.g. =I9-J9, =J-K, compare-to-quote)
              → that column (or pad) is HELPER, not main.
            - Formulas that only restate a primary amount in Lacs / another unit, or pull the
              same line for a check → HELPER tear-out, not an extension of the main bbox.
            - Primary section totals that SUM the official estimate column stay on MAIN;
              do not fold neighboring variance columns into that main just because they
              share the same rows.
            - If still ambiguous after reading formulas, prefer a separate helper bbox over
              merging the side pad into main.

            SCRATCH
            - Only unanchored floats / far-right checksums with no section label.
            - Do NOT mark intermediate cells inside a helper breakout as separate scratch.

            bboxes must use addresses present in the dump. No invented cells.
            """;

    private RegionLayoutPromptAssembler() {}

    static String userMessage(RegionLayoutPrompt prompt) {
        return "Worksheet: " + prompt.sheetName()
                + "\nCell dump (address \\t value-or-formula; formulas are authoritative —"
                + " use them when a side column might be variance/helper):\n"
                + prompt.cellDump();
    }
}
