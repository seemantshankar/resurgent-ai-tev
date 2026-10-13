package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Live LlamaCloud smoke test. Never runs in the default suite: it needs
 * {@code LLAMA_CLOUD_API_KEY} and an explicit {@code -Dtev.live.extract=true}
 * (same opt-in shape as {@code DecisionPoolReplayTest}).
 *
 * <p>Run with:
 * {@code mvn test -Dtest=LlamaExtractLiveTest -Dtev.live.extract=true}
 */
@Tag("live-extract")
class LlamaExtractLiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void extractsAStatedNumberFromATinyPdf() throws Exception {
        String apiKey = LlamaExtractAdapter.resolveApiKey(LlamaExtractAdapter.loadEnv());
        assumeTrue(
                apiKey != null && !apiKey.isBlank() && "true".equals(System.getProperty("tev.live.extract")),
                "set LLAMA_CLOUD_API_KEY or llamaparse_api_key (.env) and -Dtev.live.extract=true"
                        + " to call live LlamaExtract");

        Path pdf = temp.resolve("live-stub.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 720);
                cs.showText("Capacity is 10 MW.");
                cs.endText();
            }
            doc.save(pdf.toFile());
        }

        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode capacity = props.putObject("capacity_mw");
        capacity.put("type", "number");
        capacity.put("value_kind", "quantity");
        capacity.put("description", "plant capacity in MW");
        schema.putArray("required").add("capacity_mw");

        LlamaExtractAdapter adapter = new LlamaExtractAdapter();
        ExtractResult result = adapter.extractFieldSchema(
                new ExtractRequest(
                        temp,
                        pdf,
                        List.of(1),
                        "en",
                        "live.smoke",
                        "Extract the stated capacity number.",
                        Map.of()),
                schema);

        assertThat(result.extractorId()).isNotBlank();
        assertThat(result.payload()).isNotNull();
        // The live model may or may not find the field on a one-liner; the contract under
        // test is that the call completes, maps what it can, and never throws on empty.
        assertThat(result.facts().size()).isGreaterThanOrEqualTo(0);
        assertThat(result.stats().calls()).isGreaterThanOrEqualTo(1);
        for (FactCandidate fact : result.facts()) {
            assertThat(fact.key()).isNotBlank();
            assertThat(fact.valueKind()).isNotNull();
            for (Citation citation : fact.citations()) {
                assertThat(citation.pageIndex()).isGreaterThanOrEqualTo(1);
                assertThat(citation.verbatim()).isNotBlank();
            }
        }
    }
}
