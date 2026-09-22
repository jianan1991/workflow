package com.loop.orchestrator.knowledge;

import com.loop.orchestrator.knowledge.Knowledge.Chunk;
import com.loop.orchestrator.knowledge.Knowledge.Hit;

import java.util.List;

/**
 * Two implementations on purpose: {@link QdrantVectorStore} for real use, and
 * {@link InMemoryVectorStore} so the indexing and search path can be tested without
 * Docker. Both are exercised by the same tests.
 */
public interface VectorStore {

    /** Idempotent. Safe to call on every start. */
    void ensureCollection(int dimensions);

    /**
     * Insert or replace. Chunk ids are derived from file plus heading, so re-indexing
     * after an edit updates the entry rather than leaving a stale duplicate beside it.
     */
    void upsert(List<Chunk> chunks, List<float[]> vectors);

    List<Hit> search(float[] query, int limit);

    /** Total points held. Used by the reindex endpoint to report what happened. */
    long count();

    String describe();
}
