package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Claim-call usage is added to the local extract stats, including when no claim is kept. */
class StatedClaimReaderStatsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void emptyClaimPayloadKeepsLocalCreditsPlusTheCall() {
        ExtractClient client = emptyClaims();
        ExtractResult local = new ExtractResult("local", null, List.of(), List.of(),
                new ExtractStats(1, 0, 10, 0, 3.0, 5));
        ExtractResult out = StatedClaimReader.apply(client, request(), local,
                List.of("The site sits north of Bhiwadi."), Set.of());
        assertThat(out.stats().credits()).isEqualTo(5.0);
        assertThat(out.stats().calls()).isEqualTo(2);
        assertThat(out.facts()).isEmpty();
    }

    private ExtractClient emptyClaims() {
        return new ExtractClient() {
            @Override
            public String id() {
                return "claims";
            }

            @Override
            public ExtractResult extractFieldSchema(ExtractRequest req,
                    com.fasterxml.jackson.databind.JsonNode schema) {
                throw new AssertionError("schema extract is not this call");
            }

            @Override
            public ExtractResult extractStatedClaims(ExtractRequest req) {
                ObjectNode payload = MAPPER.createObjectNode();
                payload.set("claims", MAPPER.createArrayNode());
                return new ExtractResult(id(), payload, List.of(), List.of(),
                        new ExtractStats(1, 0, 4, 2, 2.0, 7));
            }
        };
    }

    private ExtractRequest request() {
        return new ExtractRequest(temp, null, List.of(), null, "report.dpr", "claims", Map.of());
    }
}
