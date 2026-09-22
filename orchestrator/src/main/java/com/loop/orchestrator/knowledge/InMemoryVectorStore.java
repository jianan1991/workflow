package com.loop.orchestrator.knowledge;

import com.loop.orchestrator.knowledge.Knowledge.Chunk;
import com.loop.orchestrator.knowledge.Knowledge.Hit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cosine search over a map. Not a toy for its own sake — it is what lets the whole
 * retrieval path be tested in milliseconds with no container running, and it makes the
 * Qdrant implementation falsifiable: both are run against the same assertions, so a
 * difference in results is a bug in one of them rather than a mystery.
 */
public class InMemoryVectorStore implements VectorStore {

    private final Map<String, Chunk> chunks = new LinkedHashMap<>();
    private final Map<String, float[]> vectors = new LinkedHashMap<>();
    private int dimensions;

    @Override
    public void ensureCollection(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public void upsert(List<Chunk> toAdd, List<float[]> toAddVectors) {
        if (toAdd.size() != toAddVectors.size()) {
            throw new IllegalArgumentException(
                    "got %d chunks and %d vectors".formatted(toAdd.size(), toAddVectors.size()));
        }
        for (int i = 0; i < toAdd.size(); i++) {
            Chunk chunk = toAdd.get(i);
            chunks.put(chunk.id(), chunk);
            vectors.put(chunk.id(), toAddVectors.get(i));
        }
    }

    @Override
    public List<Hit> search(float[] query, int limit) {
        List<Hit> hits = new ArrayList<>(chunks.size());
        vectors.forEach((id, vector) -> {
            double score = cosine(query, vector);
            if (score > 0) {
                hits.add(new Hit(chunks.get(id), score));
            }
        });
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.size() <= limit ? hits : new ArrayList<>(hits.subList(0, limit));
    }

    @Override
    public long count() {
        return chunks.size();
    }

    @Override
    public String describe() {
        return "in-memory (" + chunks.size() + " chunks, dim " + dimensions + ")";
    }

    /** Both sides are already L2-normalised, so the dot product is the cosine. */
    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "dimension mismatch: %d vs %d".formatted(a.length, b.length));
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }
}
