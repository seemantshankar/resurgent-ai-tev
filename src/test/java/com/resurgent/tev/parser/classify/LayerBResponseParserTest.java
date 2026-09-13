package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class LayerBResponseParserTest {

    @Test
    void parsesLinesArray() {
        List<LayerBLineJudgment> lines = LayerBResponseParser.parse("""
                {
                  "lines": [
                    {
                      "coord": "B12",
                      "verbatim": "Civil Works",
                      "path": "Project Cost > Civil Works > Structure",
                      "amountRole": "add",
                      "aliases": [],
                      "confidence": 0.9
                    },
                    {
                      "coord": "F31",
                      "verbatim": "Less : AC",
                      "path": "Project Cost > Plant & Machinery > Air Conditioning",
                      "amountRole": "deduct",
                      "aliases": ["AC"],
                      "confidence": null
                    }
                  ]
                }
                """);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).amountRole()).isEqualTo(AmountRole.ADD);
        assertThat(lines.get(1).amountRole()).isEqualTo(AmountRole.DEDUCT);
        assertThat(lines.get(1).aliases()).containsExactly("AC");
        assertThat(lines.get(1).confidence()).isNull();
    }

    @Test
    void rejectsMissingLines() {
        assertThatThrownBy(() -> LayerBResponseParser.parse("{\"ok\":true}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lines");
    }
}
