package com.loop.orchestrator.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import com.loop.orchestrator.support.TimeoutRestClient;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import java.util.Map;

/**
 * Real embeddings, through the LiteLLM proxy like every other model call — so the key, the
 * spend cap and the cost accounting all stay in one place.
 *
 * <p>Note the dimension is configured rather than discovered. Change the embedding model
 * and the collection has to be dropped and rebuilt; Qdrant will refuse the mismatch, which
 * is the correct and least confusing failure.
 */
public class LiteLlmEmbedder implements Embedder {

    private final RestClient http;
    private final String model;
    private final int dimensions;

    public LiteLlmEmbedder(String baseUrl, String apiKey, String model, int dimensions) {
        // Embedding a chunk is a short call. If it has not answered in a minute, something
        // is wrong, and waiting forever on it parks the thread that was indexing.
        this.http = TimeoutRestClient.createWithBearer(baseUrl, apiKey,
                Duration.ofSeconds(10), Duration.ofSeconds(60));
        this.model = model;
        this.dimensions = dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String name() {
        return model + " (" + dimensions + "d, via litellm)";
    }

    @Override
    public float[] embed(String text) {
        JsonNode response = http.post()
                .uri("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", model, "input", text))
                .retrieve()
                .body(JsonNode.class);

        if (response == null || !response.path("data").isArray() || response.path("data").isEmpty()) {
            throw new IllegalStateException("no embedding came back for a " + text.length()
                    + " character input — check the proxy and the model id");
        }

        JsonNode values = response.path("data").get(0).path("embedding");
        if (values.size() != dimensions) {
            throw new IllegalStateException(("embedding model returned %d dimensions but the "
                    + "collection is configured for %d — set knowledge.dimensions to %d and "
                    + "reindex from scratch").formatted(values.size(), dimensions, values.size()));
        }

        float[] vector = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            vector[i] = (float) values.get(i).asDouble();
        }
        return vector;
    }
}
