package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.parser.discover.Packet;
import com.resurgent.tev.parser.discover.PacketCell;
import com.resurgent.tev.parser.discover.PacketRangeRef;
import java.util.List;

/** Assembles the Layer A system/user messages sent through the LLM port. */
final class LayerAPromptAssembler {

    static final String SYSTEM = """
            You classify one financial-model Packet at Layer A only.
            Do not bind individual money lines.
            Return a single JSON object with keys:
              scheduleFamily: one of the names in scheduleFamilies on the user message.
                If none of them fits, set scheduleFamily to "none" and suggestedFamily
                to a new short snake_case category (for example manpower). Do not invent
                a name when a listed family fits.
              triage: main | scratch | orphan
              relevance: primary | supporting | noise
              rowLabels: array of distinct row-axis labels from CORE cells only (empty if none)
              columnHeaders: array of distinct column-axis headers from CORE cells only
                (empty if none). Do not copy CONTEXT-only labels into these arrays.
              packetDefaultHead: optional short heading grounded in CORE cells, or null
              suggestedFamily: a new category when scheduleFamily is "none", otherwise null
              statedScale: the unit the money in this island is shown in, ONLY if a
                cell in the packet (CORE or CONTEXT) literally says so, e.g. "Rs. In Lacs"
                -> "lakh", "(Rs. in crore)" -> "crore", "Amount in Rs" -> "unit".
                One of unit|thousand|lakh|million|crore|billion, else null. Never guess
                from the size of the numbers.
              scaleCell: the coord (e.g. "J6") of the cell that states it, else null
              about: one short paragraph (~4–8 sentences, roughly 80–200 words) that a
                later cell-level nomenclature pass and a natural-language report writer
                can use without seeing the grid. Grounded in CORE; concrete nouns, not
                vague adjectives; no invented amounts. Must cover:
                (1) identity — what schedule/section this island is (use CORE headings
                    and labels when present);
                (2) function — what role it plays in the financial model (e.g. civil
                    capex build-up, plant & machinery item list, side variance/check pad);
                (3) contents — what kinds of rows and columns it holds (line items,
                    areas, rates, suppliers, section totals, formulas — name them when
                    they appear in CORE);
                (4) use of amounts — whether figures here are the primary schedule or a
                    helper tear-out / orphan check column a reader should not double-count.
                Write enough that embedding this text alone would retrieve the right
                table for a question like "where is civil works capex?" Do not pad with
                filler; do not invent sections that only live in CONTEXT.
            CORE vs CONTEXT (critical):
              Each packet cell has role "core" or "context". CORE cells are the Candidate
              members — they define the region. CONTEXT cells are helpers for orientation
              only (nearby labels/headers). about / rowLabels / columnHeaders /
              packetDefaultHead MUST be grounded in CORE. CONTEXT may clarify a label that
              already attaches to a core amount, but must not invent a full section table
              that is not present in CORE.
              If CORE is mostly totals, variances, or sparse amount cells with no core
              labels, say that plainly and still cover function + use-of-amounts
              (e.g. helper tear-out of civil section totals and variance formulas)
              — do not narrate a labeled schedule whose titles live only in CONTEXT.
            When triage is scratch or orphan, relevance MUST be noise.
            structuralRole on the user message is a deterministic geometry tag
            (main|helper) — keep it in mind, but triage is your own judgment.
            Numeric literals are dummy stand-ins; formulas and labels are real.
            Respond with the JSON object only, no markdown fences.
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LayerAPromptAssembler() {}

    static String userMessage(LayerAPrompt prompt) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            putIfPresent(root, "structuralRole", prompt.structuralRole());
            putIfPresent(root, "sheetName", prompt.sheetName());
            ArrayNode families = root.putArray("scheduleFamilies");
            for (String family : prompt.scheduleFamilies()) {
                families.add(family);
            }
            Packet packet = prompt.packet();
            ObjectNode packetNode = root.putObject("packet");
            packetNode.put("candidateId", packet.candidateId());
            packetNode.put("candidateKind", packet.candidateKind());
            packetNode.put("contextClosureSucceeded", packet.contextClosureSucceeded());
            ArrayNode cells = packetNode.putArray("cells");
            for (PacketCell cell : packet.cells()) {
                ObjectNode node = cells.addObject();
                node.put("coord", cell.coord());
                node.put("role", cell.role());
                putIfPresent(node, "valueType", cell.valueType());
                putIfPresent(node, "text", cell.textValue());
                putIfPresent(node, "display", cell.displayValue());
                putIfPresent(node, "numeric", cell.numericValue());
                putFormula(node, cell.formulaText());
                if (cell.rowHidden()) {
                    node.put("rowHidden", true);
                }
                if (cell.colHidden()) {
                    node.put("colHidden", true);
                }
            }
            if (!packet.largeRangeRefs().isEmpty()) {
                ArrayNode ranges = packetNode.putArray("largeRangeRefs");
                for (PacketRangeRef ref : packet.largeRangeRefs()) {
                    ObjectNode node = ranges.addObject();
                    node.put("targetRange", ref.targetRange());
                    node.put("persistedCellCount", ref.persistedCellCount());
                }
            }
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("failed to assemble Layer A prompt: " + e.getMessage(), e);
        }
    }

    private static void putFormula(ObjectNode node, String formula) {
        if (formula != null && !formula.isBlank()) {
            node.put("formula", formula);
        }
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }
}
