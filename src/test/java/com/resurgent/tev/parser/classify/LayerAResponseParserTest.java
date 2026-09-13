package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class LayerAResponseParserTest {

    @Test
    void parsesCanonicalJson() {
        LayerAJudgment judgment = LayerAResponseParser.parse("""
                {
                  "scheduleFamily": "capex_detail",
                  "triage": "main",
                  "relevance": "primary",
                  "rowLabels": ["Particulars"],
                  "columnHeaders": ["Amount"],
                  "packetDefaultHead": "Project Cost"
                }
                """);
        assertThat(judgment.scheduleFamily()).isEqualTo(ScheduleFamily.CAPEX_DETAIL);
        assertThat(judgment.triage()).isEqualTo(Triage.MAIN);
        assertThat(judgment.relevance()).isEqualTo(Relevance.PRIMARY);
        assertThat(judgment.rowLabels()).containsExactly("Particulars");
        assertThat(judgment.columnHeaders()).containsExactly("Amount");
        assertThat(judgment.packetDefaultHead()).isEqualTo("Project Cost");
    }

    @Test
    void extractsJsonFromMarkdownFenceAndNormalizesAliases() {
        LayerAJudgment judgment = LayerAResponseParser.parse("""
                ```json
                {"schedule_family":"CapEx Detail","triage":"main","relevance":"Secondary"}
                ```
                """);
        assertThat(judgment.scheduleFamily()).isEqualTo(ScheduleFamily.CAPEX_DETAIL);
        assertThat(judgment.triage()).isEqualTo(Triage.MAIN);
        assertThat(judgment.relevance()).isEqualTo(Relevance.SUPPORTING);
        assertThat(judgment.rowLabels()).isEqualTo(List.of());
        assertThat(judgment.packetDefaultHead()).isNull();
    }

    @Test
    void normalizesScratchpadAliasAndForcesNoise() {
        LayerAJudgment judgment = LayerAResponseParser.parse("""
                {"scheduleFamily":"assumptions","triage":"Scratchpad","relevance":"Secondary"}
                """);
        assertThat(judgment.triage()).isEqualTo(Triage.SCRATCH);
        assertThat(judgment.relevance()).isEqualTo(Relevance.NOISE);
    }

    @Test
    void rejectsUnknownTriageAfterNormalization() {
        assertThatThrownBy(() -> LayerAResponseParser.parse("""
                {"scheduleFamily":"capex_detail","triage":"maybe","relevance":"primary"}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("triage=maybe");
    }

    @Test
    void rejectsUncertainRelevanceInsteadOfMappingToNoise() {
        assertThatThrownBy(() -> LayerAResponseParser.parse("""
                {"scheduleFamily":"capex_detail","triage":"main","relevance":"unknown"}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("relevance=unknown");
    }

    @Test
    void softTriageForcesRelevanceNoise() {
        LayerAJudgment judgment = LayerAResponseParser.parse("""
                {"scheduleFamily":"assumptions","triage":"scratch","relevance":"primary"}
                """);
        assertThat(judgment.triage()).isEqualTo(Triage.SCRATCH);
        assertThat(judgment.relevance()).isEqualTo(Relevance.NOISE);
    }

    @Test
    void rejectsCompletionWithoutJsonObject() {
        assertThatThrownBy(() -> LayerAResponseParser.parse("no json here"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON object");
    }
}
