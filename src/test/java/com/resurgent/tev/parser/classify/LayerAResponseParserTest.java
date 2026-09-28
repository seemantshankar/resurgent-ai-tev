package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class LayerAResponseParserTest {

    @Test
    void parsesAboutWithStructuredFields() {
        LayerAJudgment judgment = LayerAResponseParser.parse("""
                {
                  "scheduleFamily": "capex_detail",
                  "suggestedFamily": null,
                  "triage": "main",
                  "relevance": "primary",
                  "rowLabels": ["Civil Works"],
                  "columnHeaders": ["Amount"],
                  "packetDefaultHead": null,
                  "about": "Floor-by-floor civil cost table with section totals in Lacs."
                }
                """);
        assertThat(judgment.scheduleFamily()).isEqualTo("capex_detail");
        assertThat(judgment.triage()).isEqualTo("main");
        assertThat(judgment.relevance()).isEqualTo("primary");
        assertThat(judgment.rowLabels()).containsExactly("Civil Works");
        assertThat(judgment.about()).contains("civil cost");
    }

    @Test
    void rejectsMissingAbout() {
        assertThatThrownBy(() -> LayerAResponseParser.parse("""
                {
                  "scheduleFamily": "capex_detail",
                  "suggestedFamily": null,
                  "triage": "main",
                  "relevance": "primary",
                  "rowLabels": [],
                  "columnHeaders": [],
                  "packetDefaultHead": null
                }
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("about=");
    }

    @Test
    void softTriageForcesNoiseRelevance() {
        LayerAJudgment judgment = LayerAResponseParser.parse("""
                {
                  "scheduleFamily": "assumptions",
                  "suggestedFamily": null,
                  "triage": "scratch",
                  "relevance": "primary",
                  "rowLabels": [],
                  "columnHeaders": [],
                  "packetDefaultHead": null,
                  "about": "Floating check figure outside the main schedule."
                }
                """);
        assertThat(judgment.triage()).isEqualTo("scratch");
        assertThat(judgment.relevance()).isEqualTo("noise");
    }
}
