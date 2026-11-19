package dev.llmgateway.gateway.cache;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls a Hugging Face text-embeddings-inference (TEI) service: {@code POST /embed} with
 * {@code {"inputs": "...", "normalize": true, "truncate": false}}, answer {@code [[0.01, ...]]}.
 *
 * <p>{@code truncate: false} on purpose: the model reads at most 512 tokens, and silently
 * cutting longer prompts would make two prompts that differ only near the end look identical,
 * which is a wrong answer from the cache. Too long is an error instead, so the semantic layer
 * is skipped.
 */
public class TeiEmbedder implements Embedder {

    private final RestClient http;
    private final int dimensions;

    public TeiEmbedder(RestClient http, int dimensions) {
        this.http = http;
        this.dimensions = dimensions;
    }

    @Override
    public float[] embed(String text) {
        JsonNode answer;
        try {
            answer = http.post()
                    .uri("/embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("inputs", text, "normalize", true, "truncate", false))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new EmbeddingException("Embedding service call failed: " + e.getMessage(), e);
        }
        return toVector(answer);
    }

    private float[] toVector(JsonNode answer) {
        JsonNode values = answer == null ? null : answer.path(0);
        if (values == null || !values.isArray() || values.size() != dimensions) {
            throw new EmbeddingException("Expected one vector of " + dimensions + " numbers, got: "
                    + abbreviate(answer));
        }
        float[] vector = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            vector[i] = (float) values.get(i).asDouble();
        }
        return vector;
    }

    private static String abbreviate(JsonNode node) {
        String text = String.valueOf(node);
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }
}
