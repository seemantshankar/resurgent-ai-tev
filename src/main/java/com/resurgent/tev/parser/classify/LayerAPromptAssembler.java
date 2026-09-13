package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.parser.discover.Packet;
import com.resurgent.tev.parser.discover.PacketCell;
import com.resurgent.tev.parser.discover.PacketRangeRef;
import com.resurgent.tev.parser.nomenclature.NomenclatureAlias;
import com.resurgent.tev.parser.nomenclature.NomenclatureNode;
import com.resurgent.tev.parser.nomenclature.OntologySlice;
/** Assembles the Layer A system/user messages sent through the LLM port. */
final class LayerAPromptAssembler {

    static final String SYSTEM = """
            You classify one financial-model Packet at Layer A only.
            Do not bind individual money lines (no Layer B paths per cell, no amount roles, no peers).
            Return a single JSON object with keys:
              scheduleFamily: snake_case family (capex_detail, means_of_finance, profit_and_loss,
                balance_sheet, cash_flow, assumptions, project_summary, or another short snake_case name)
              triage: main | scratch | orphan
              relevance: primary | supporting | noise
              rowLabels: array of distinct row-axis labels you can see (empty if none)
              columnHeaders: array of distinct column-axis headers you can see (empty if none)
              packetDefaultHead: optional nomenclature path from the ontology slice, or null
            When triage is scratch or orphan, relevance MUST be noise (soft-triage leftovers).
            If cheapPass is true this is a coverage-parent overview: broad sheet meaning only,
            no line lists. Numeric literals are dummy stand-ins; formulas and labels are real.
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LayerAPromptAssembler() {}

    static String userMessage(LayerAPrompt prompt) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("cheapPass", prompt.cheapPass());
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
                putIfPresent(node, "formula", cell.formulaText());
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
            OntologySlice slice = prompt.ontologySlice();
            ObjectNode ontology = root.putObject("ontologySlice");
            ontology.put("industryTag", slice.industry().industryTag());
            ArrayNode paths = ontology.putArray("paths");
            for (NomenclatureNode node : slice.nodes()) {
                paths.add(node.path());
            }
            ArrayNode aliases = ontology.putArray("aliases");
            for (NomenclatureAlias alias : slice.aliases()) {
                aliases.add(alias.aliasText() + " -> " + alias.leafPath());
            }
            if (prompt.parentDisposition() != null) {
                LayerAJudgment parent = prompt.parentDisposition();
                ObjectNode parentNode = root.putObject("parentLayerA");
                parentNode.put("scheduleFamily", parent.scheduleFamily());
                parentNode.put("triage", parent.triage());
                parentNode.put("relevance", parent.relevance());
                if (parent.packetDefaultHead() != null) {
                    parentNode.put("packetDefaultHead", parent.packetDefaultHead());
                }
            }
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("failed to assemble Layer A prompt: " + e.getMessage(), e);
        }
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }
}
