package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.parser.discover.Packet;
import com.resurgent.tev.parser.discover.PacketCell;
import com.resurgent.tev.parser.nomenclature.NomenclatureAlias;
import com.resurgent.tev.parser.nomenclature.NomenclatureNode;
import com.resurgent.tev.parser.nomenclature.OntologySlice;

/** Assembles the Layer B system/user messages sent through the LLM port. */
final class LayerBPromptAssembler {

    static final String SYSTEM = """
            You bind money lines in one financial-model Packet at Layer B only.
            Do not invent mid-level nomenclature paths. Paths must be ontology leaves, or a new
            soft leaf under a known mid-level (parentPath > LeafName).
            Return a single JSON object: { "lines": [ ... ] }.
            Each line object has:
              coord: Packet cell coord of the amount (e.g. B12) — numbers only, no formula totals unless helper
              verbatim: client label evidence for that line
              path: nomenclature leaf path from the ontology slice (or new soft leaf under a known parent)
              amountRole: add | deduct | total | helper
              aliases: optional synonym strings for a new soft leaf (empty array if none)
              confidence: number 0..1 or null
            Bind only amount cells you can ground in the Packet. Empty lines array is allowed.
            Peers are out of scope — do not emit peer fields.
            Numeric literals in the Packet are dummy stand-ins; labels and formulas are real.
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LayerBPromptAssembler() {}

    static String userMessage(LayerBPrompt prompt) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            Packet packet = prompt.packet();
            ObjectNode packetNode = root.putObject("packet");
            packetNode.put("candidateId", packet.candidateId());
            packetNode.put("candidateKind", packet.candidateKind());
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
            }
            OntologySlice slice = prompt.ontologySlice();
            ObjectNode ontology = root.putObject("ontologySlice");
            ontology.put("industryTag", slice.industry().industryTag());
            ArrayNode paths = ontology.putArray("paths");
            for (NomenclatureNode node : slice.nodes()) {
                ObjectNode pathNode = paths.addObject();
                pathNode.put("path", node.path());
                pathNode.put("leaf", node.leaf());
            }
            ArrayNode aliases = ontology.putArray("aliases");
            for (NomenclatureAlias alias : slice.aliases()) {
                aliases.add(alias.aliasText() + " -> " + alias.leafPath());
            }
            ObjectNode layerA = root.putObject("layerA");
            layerA.put("scheduleFamily", prompt.layerA().scheduleFamily());
            layerA.put("triage", prompt.layerA().triage());
            layerA.put("relevance", prompt.layerA().relevance());
            if (prompt.parentDisposition() != null) {
                ObjectNode parent = root.putObject("parentLayerA");
                parent.put("scheduleFamily", prompt.parentDisposition().scheduleFamily());
                parent.put("triage", prompt.parentDisposition().triage());
                parent.put("relevance", prompt.parentDisposition().relevance());
            }
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("failed to assemble Layer B prompt: " + e.getMessage(), e);
        }
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }
}
