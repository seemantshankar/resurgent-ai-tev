package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.llm.FakeDocLlmClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Default page extractor names stated claims through the document LLM seam. */
class DocLlmExtractClientTest {

    @TempDir
    Path temp;

    @Test
    void offlineClientDoesNotInventClaims() throws Exception {
        ExtractResult result = new DocLlmExtractClient().extractStatedClaims(request("A sentence."));
        assertThat(result.payload().path("claims").isMissingNode()
                || result.payload().path("claims").isEmpty()).isTrue();
        assertThat(result.facts()).isEmpty();
    }

    @Test
    void injectedLlmReturnsClaimsPayload() throws Exception {
        FakeDocLlmClient llm = new FakeDocLlmClient("""
                {"claims":[{"key":"place","value":"north of Bhiwadi, Rajasthan",\
                "quote":"The site sits north of Bhiwadi, Rajasthan.","kind":"text"}]}
                """);
        ExtractResult result = new DocLlmExtractClient(llm).extractStatedClaims(
                request("The site sits north of Bhiwadi, Rajasthan."));
        assertThat(llm.calls()).isEqualTo(1);
        assertThat(result.payload().path("claims").size()).isEqualTo(1);
        assertThat(result.payload().path("claims").get(0).path("key").asText())
                .isEqualTo("place");
    }

    private ExtractRequest request(String sentences) {
        return new ExtractRequest(temp, null, List.of(), null, "report.dpr", "claims",
                Map.of(ExtractRequest.STATED_CLAIM_SENTENCES, sentences));
    }
}
