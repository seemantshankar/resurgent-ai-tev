package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resurgent.tev.parser.discover.Packet;
import com.resurgent.tev.parser.discover.PacketCell;
import com.resurgent.tev.parser.nomenclature.NomenclatureAlias;
import com.resurgent.tev.parser.nomenclature.NomenclatureNode;
import com.resurgent.tev.parser.nomenclature.OntologySlice;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Assembles Layer B messages. Input is amount cells (literal + formula) with
 * row/column labels plus bounded nearby context; the model returns compact
 * indices resolved locally by {@link LayerBResponseParser}.
 */
final class LayerBPromptAssembler {

    static final String SYSTEM = """
            You bind money lines in one financial-model Packet at Layer B only.
            Do not invent mid-level nomenclature paths. Bind to an indexed ontology leaf,
            or propose a soft leaf under a known mid-level parent index.
            Return a single JSON object:
              lines: array of [cellIndex, pathIndex, roleCode]
              soft: array of {c, pp, n, a, r} for new soft leaves only
            cellIndex indexes amounts[]; pathIndex / pp index paths[].
            roleCode: 0=add, 1=deduct, 2=total, 3=helper.
            soft fields: c=cellIndex, pp=parentPathIndex (mid-level), n=new leaf name,
            a=aliases (empty if none), r=roleCode.
            Each amounts[] entry has kind: money | quantity | rate | percent | unknown.
            Only kind=money may use roleCode 0=add, 1=deduct, or 2=total.
            quantity/rate/percent may use 3=helper only (supporting drivers), never cost roles.
            Formula=true cells may only use 2=total or 3=helper.
            Prefer lines over soft when a leaf already exists. Empty lines/soft allowed.
            Use context[] (headers, units, section labels) to disambiguate quantity/rate/total.
            Optional peers on a line: peers:[{coord,reason}] with reason=anti_double_count only.
            Peer coords may be sheet-qualified (SHEET!F31) and may sit outside this Packet.
            Deduct lines use the economic leaf path (same as the add), not a geometric Civil leaf.
            Numeric literals are dummy stand-ins; labels/formulas are real.
            When amounts[] is a chunk of a larger Packet, bind only the listed amounts.
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LayerBPromptAssembler() {}

    static LayerBPromptIndex index(LayerBPrompt prompt) {
        Packet packet = prompt.packet();
        List<LayerBPromptIndex.AmountRow> amounts = new ArrayList<>();
        int i = 0;
        for (PacketCell cell : LayerBAmountSupport.amountCells(packet)) {
            amounts.add(new LayerBPromptIndex.AmountRow(
                    i++,
                    cell,
                    LayerBAmountSupport.resolveRowLabel(packet, cell),
                    LayerBAmountSupport.resolveColumnHeader(packet, cell),
                    LayerBAmountSupport.isFormulaNumeric(cell),
                    LayerBAmountSupport.classifyKind(packet, cell)));
        }
        return new LayerBPromptIndex(amounts, prompt.ontologySlice().nodes());
    }

    static String userMessage(LayerBPrompt prompt) {
        return assemble(prompt).userMessage();
    }

    static Assembled assemble(LayerBPrompt prompt) {
        LayerBPromptIndex index = index(prompt);
        try {
            ObjectNode root = MAPPER.createObjectNode();
            Packet packet = prompt.packet();
            ObjectNode packetNode = root.putObject("packet");
            packetNode.put("candidateId", packet.candidateId());
            packetNode.put("candidateKind", packet.candidateKind());
            ArrayNode amounts = packetNode.putArray("amounts");
            for (LayerBPromptIndex.AmountRow row : index.amounts()) {
                ObjectNode node = amounts.addObject();
                node.put("i", row.index());
                node.put("coord", row.coord());
                node.put("label", row.label());
                putIfPresent(node, "colHeader", row.columnHeader());
                node.put("kind", row.kind().name().toLowerCase(Locale.ROOT));
                node.put("formula", row.formula());
                putIfPresent(node, "numeric", row.cell().numericValue());
                if (row.formula()) {
                    putIfPresent(node, "formulaText", row.cell().formulaText());
                }
            }
            ArrayNode context = packetNode.putArray("context");
            for (PacketCell cell : LayerBAmountSupport.contextCells(packet)) {
                ObjectNode node = context.addObject();
                node.put("coord", cell.coord());
                node.put("row", cell.rowNum());
                node.put("col", cell.colNum());
                putIfPresent(node, "text", LayerBAmountSupport.labelText(cell));
                putIfPresent(node, "role", cell.role());
            }
            OntologySlice slice = prompt.ontologySlice();
            ObjectNode ontology = root.putObject("ontologySlice");
            ontology.put("industryTag", slice.industry().industryTag());
            ArrayNode paths = ontology.putArray("paths");
            int pathIndex = 0;
            for (NomenclatureNode node : index.paths()) {
                ObjectNode pathNode = paths.addObject();
                pathNode.put("i", pathIndex++);
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
            return new Assembled(MAPPER.writeValueAsString(root), index);
        } catch (Exception e) {
            throw new IllegalStateException("failed to assemble Layer B prompt: " + e.getMessage(), e);
        }
    }

    record Assembled(String userMessage, LayerBPromptIndex index) {
        Assembled {
            Objects.requireNonNull(userMessage, "userMessage");
            Objects.requireNonNull(index, "index");
        }
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }
}
