package com.resurgent.tev.doc.embed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The embedding client is a local unit vectoriser unless an endpoint is configured
 * and the caller is not local-only. The HTTP adapter is stubbed; nothing here
 * opens a socket.
 */
class EmbeddingClientTest {

    private static final String DSCR_A =
            "The borrower shall maintain a minimum debt service coverage ratio of not less than"
                    + " 1.25 times for the entire tenor of the facility.";
    private static final String DSCR_B =
            "The borrower must keep a minimum debt service coverage ratio of not less than"
                    + " 1.25 times for the entire tenor of the facility.";
    private static final String ASSIGNMENT =
            "The lender may assign this agreement to an affiliate and the borrower shall be"
                    + " notified within ten business days.";

    @Test
    void localVectoriserIsDeterministicUnitLengthAndOffline() {
        EmbeddingClient client = new LocalHashEmbeddingClient();
        float[] first = client.embed(DSCR_A);
        float[] second = client.embed(DSCR_A);

        assertThat(client.model()).isEqualTo("local-ngram-hash-v1");
        assertThat(client.dims()).isEqualTo(128);
        assertThat(first).hasSize(128);
        assertThat(first).containsExactly(second);
        assertThat(norm(first)).isCloseTo(1.0, within(1e-5));
        assertThat(client.embed("")).containsOnly(0f);
        assertThat(client.embed("   ")).containsOnly(0f);
        assertThat(client.embed(DSCR_A)).isNotEqualTo(client.embed(ASSIGNMENT));
    }

    @Test
    void paraphrasedCovenantIsCloserThanAnUnrelatedClause() {
        EmbeddingClient client = new LocalHashEmbeddingClient();
        double paraphrased = Cosine.similarity(client.embed(DSCR_A), client.embed(DSCR_B));
        double unrelated = Cosine.similarity(client.embed(DSCR_A), client.embed(ASSIGNMENT));

        assertThat(paraphrased).as("paraphrase %.4f vs unrelated %.4f", paraphrased, unrelated)
                .isGreaterThan(unrelated)
                .isGreaterThan(0.72);
        assertThat(unrelated).isLessThan(0.72);
    }

    @Test
    void httpAdapterIsUsedOnlyWhenAnEndpointIsConfiguredAndResidencyAllowsIt() {
        StubTransport stub = new StubTransport();
        Map<String, String> configured = Map.of(
                "DOC_EMBEDDING_BASE_URL", "http://embeddings.test",
                "DOC_EMBEDDING_API_KEY", "secret",
                "DOC_EMBEDDING_MODEL", "unit-embed");

        EmbeddingClient remote = EmbeddingClients.select(configured, stub);
        float[] vector = remote.embed("debt service coverage");

        assertThat(stub.calls).isEqualTo(1);
        assertThat(stub.uri).isEqualTo(URI.create("http://embeddings.test/v1/embeddings"));
        assertThat(stub.bearer).isEqualTo("secret");
        assertThat(stub.body).contains("unit-embed").contains("debt service coverage");
        assertThat(vector).hasSize(2);
        assertThat(vector[0]).isCloseTo(0.6f, within(0.0001f));
        assertThat(vector[1]).isCloseTo(0.8f, within(0.0001f));

        stub.calls = 0;
        EmbeddingClient locked = EmbeddingClients.select(Map.of(
                "DOC_EMBEDDING_BASE_URL", "http://embeddings.test",
                "TEV_DATA_RESIDENCY", "local_only"), stub);
        float[] local = locked.embed("debt service coverage");
        assertThat(stub.calls).isZero();
        assertThat(locked.model()).isEqualTo(LocalHashEmbeddingClient.MODEL);
        assertThat(local).containsExactly(new LocalHashEmbeddingClient().embed("debt service coverage"));

        EmbeddingClient unset = EmbeddingClients.select(Map.of("DOC_EMBEDDING_API_KEY", "secret"), stub);
        unset.embed("debt service coverage");
        assertThat(stub.calls).isZero();
    }

    private static double norm(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += (double) value * value;
        }
        return Math.sqrt(sum);
    }

    private static final class StubTransport implements EmbeddingTransport {
        private int calls;
        private URI uri;
        private String bearer;
        private String body;

        @Override
        public String post(URI uri, String bearer, String jsonBody, long timeoutMs) {
            calls++;
            this.uri = uri;
            this.bearer = bearer;
            this.body = jsonBody;
            return "{\"data\":[{\"embedding\":[3.0,4.0]}],\"model\":\"stub-embed\"}";
        }
    }
}
