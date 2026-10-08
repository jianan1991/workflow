package com.loop.orchestrator.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.loop.orchestrator.knowledge.Knowledge.Chunk;
import com.loop.orchestrator.knowledge.Knowledge.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import com.loop.orchestrator.support.TimeoutRestClient;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Qdrant over its REST API.
 *
 * <p>No client library on purpose: three endpoints, and a dependency here would be one
 * more thing to keep aligned with the server version for no benefit at this size.
 */
public class QdrantVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);

    private final RestClient http;
    private final String collection;

    public QdrantVectorStore(String baseUrl, String collection) {
        // A container on the same Docker network: anything slower than this is a failure
        // rather than slowness, and an unbounded wait here stalls knowledge retrieval
        // inside an agent call instead of failing it.
        //
        // The read budget is larger than a local service suggests because upsert() sends
        // every chunk of the knowledge base in one request, and the JDK client treats this
        // as a whole-request deadline rather than an idle timeout.
        this.http = TimeoutRestClient.create(baseUrl,
                Duration.ofSeconds(5), Duration.ofSeconds(60));
        this.collection = collection;
    }

    @Override
    public void ensureCollection(int dimensions) {
        try {
            http.put()
                    .uri("/collections/{name}", collection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("vectors", Map.of("size", dimensions, "distance", "Cosine")))
                    .retrieve()
                    .toBodilessEntity();
            log.info("qdrant collection '{}' ready at {} dimensions", collection, dimensions);
        } catch (RestClientResponseException e) {
            // Qdrant answers 409 when it already exists at the same shape, which is the
            // normal case on every restart.
            if (e.getStatusCode().value() == 409) {
                log.debug("qdrant collection '{}' already exists", collection);
                return;
            }
            throw new IllegalStateException(
                    "could not create the qdrant collection — if the dimensions changed, drop it "
                            + "and reindex: " + e.getResponseBodyAsString(), e);
        }
    }

    @Override
    public void upsert(List<Chunk> chunks, List<float[]> vectors) {
        List<Map<String, Object>> points = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("file", chunk.file());
            payload.put("heading", chunk.heading());
            payload.put("text", chunk.text());
            payload.put("date", chunk.date());
            payload.put("author", chunk.author());

            points.add(Map.of("id", chunk.id(), "vector", boxed(vectors.get(i)), "payload", payload));
        }

        http.put()
                .uri("/collections/{name}/points?wait=true", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("points", points))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public List<Hit> search(float[] query, int limit) {
        JsonNode response = http.post()
                .uri("/collections/{name}/points/search", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("vector", boxed(query), "limit", limit, "with_payload", true))
                .retrieve()
                .body(JsonNode.class);

        if (response == null || !response.has("result")) {
            return List.of();
        }

        List<Hit> hits = new ArrayList<>();
        for (JsonNode point : response.get("result")) {
            JsonNode payload = point.path("payload");
            hits.add(new Hit(new Chunk(
                    point.path("id").asText(),
                    payload.path("file").asText(),
                    payload.path("heading").asText(),
                    payload.path("text").asText(),
                    payload.path("date").asText("undated"),
                    payload.path("author").asText("unattributed")),
                    point.path("score").asDouble()));
        }
        return hits;
    }

    @Override
    public long count() {
        try {
            JsonNode response = http.post()
                    .uri("/collections/{name}/points/count", collection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("exact", true))
                    .retrieve()
                    .body(JsonNode.class);
            return response == null ? 0 : response.path("result").path("count").asLong();
        } catch (RestClientResponseException e) {
            return 0;
        }
    }

    @Override
    public String describe() {
        return "qdrant collection '" + collection + "'";
    }

    private static List<Float> boxed(float[] vector) {
        List<Float> out = new ArrayList<>(vector.length);
        for (float f : vector) {
            out.add(f);
        }
        return out;
    }
}
