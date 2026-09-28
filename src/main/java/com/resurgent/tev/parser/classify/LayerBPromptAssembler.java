package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.BindCellRow;
import com.resurgent.tev.parser.redact.DummyValueMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Assembles the one-per-Candidate Layer B prompt. Numbers are redacted. */
final class LayerBPromptAssembler {

    static final String SYSTEM = """
            You bind the remaining cells of one financial-model island.
            Layer A already decided what the island is. Read the about paragraph
            first: it is the region brief that tells you what this island is for
            and how its cells should be read.
            Prefer an existing catalog leaf whenever the about and the row fit
            one. Add a new leaf only when none of the offered paths fits. A new
            leaf must sit under a known parent already in the catalog, for
            example "Cash Flow > Drawings". The leaf is a short category, not a
            supplier name, a person's name, a formula error, or a coordinate.
            Return a single JSON object:
              {"rows":[{"row":11,"root":"economic","path":"Profit & Loss"}],
               "cells":[{"coord":"E5","root":"frame","path":"Frame > Schedule Title"}]}
            root is economic, identity, or frame.
            Rules:
            - One path per cell. A row entry covers every still-unbound cell on
              that row: the label and the amounts.
            - A row label shares the path of the amounts it names.
            - Period headers are already bound. Do not put amounts on
              Frame > Period. Amounts keep their own economic path.
            - A formula that only copies one fact from another sheet keeps that
              fact's economic path. The graph will mark it helper.
            - A formula that places the firm name into an address is the address,
              Project Identity > Address. It is not a second legal name.
            - Identity is only legal name, constitution, partners, and address.
            - Frame paths are only: Frame > Schedule Title, Frame > Annexure,
              Frame > Section Banner, Frame > Period, Frame > Scale,
              Frame > Field Mark, Frame > Blank.
            - Section banners, the schedule title, an annexure locator, and a
              scale marker are frame. They are not money lines.
            - Rows marked kept already have a path. Return paths for rows and
              cells marked bind. A row entry applies only to bind cells.
            Numeric literals are dummy stand-ins. Formulas and labels are real.
            Respond with the JSON object only.
            """;

    private LayerBPromptAssembler() {}

    static String userMessage(LayerBPrompt prompt) {
        StringBuilder body = new StringBuilder();
        body.append("sheet: ").append(prompt.sheetName()).append('\n');
        if (prompt.scheduleFamily() != null) {
            body.append("scheduleFamily: ").append(prompt.scheduleFamily()).append('\n');
        }
        body.append("about: ").append(prompt.about()).append('\n');
        body.append(
                "Choose an existing catalog path when the about and the row fit one."
                        + " Mint a new leaf under a known parent only when none fits.\n");
        if (prompt.retry()) {
            body.append("These coords are still unbound. Bind every one of them.\n");
        }
        body.append("catalog:\n");
        for (String path : prompt.allowedPaths()) {
            body.append("- ").append(path).append('\n');
        }
        body.append("grid:\n");
        body.append(prompt.grid());
        return body.toString();
    }

    static String grid(List<BindCellRow> cells, Set<String> unbound) {
        StringBuilder grid = new StringBuilder();
        for (BindCellRow cell : cells) {
            if (cell.error()) {
                continue;
            }
            String coord = cell.coord().toUpperCase(Locale.ROOT);
            grid.append(unbound.contains(coord) ? "bind" : "kept")
                    .append('\t')
                    .append(cell.coord())
                    .append('\t')
                    .append(cell.rowNum())
                    .append('\t')
                    .append(shown(cell))
                    .append('\n');
        }
        return grid.toString();
    }

    private static String shown(BindCellRow cell) {
        if (cell.formulaText() != null && !cell.formulaText().isBlank()) {
            String formula = cell.formulaText().trim();
            return formula.startsWith("=") ? formula : "=" + formula;
        }
        if (cell.textValue() != null && !cell.textValue().isBlank()) {
            String text = cell.textValue().replace('\n', ' ').trim();
            return text.length() > 120 ? text.substring(0, 120) : text;
        }
        if ("number".equals(cell.valueType())) {
            double dummy = DummyValueMapper.dummyNumeric(1.0d, cell.coord());
            return BigDecimal.valueOf(dummy).stripTrailingZeros().toPlainString();
        }
        return "<" + (cell.valueType() != null ? cell.valueType() : "empty") + ">";
    }
}
