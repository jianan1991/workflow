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
     * Insert or replace by id. Used for incremental writes.
     *
     * <p>Not enough on its own for a reindex: an id is derived from the file, heading and
     * occurrence, so a renamed heading or a deleted section leaves its old chunk behind
     * with text that no longer exists anywhere in the repository. An agent then retrieves
     * and cites a decision that was reversed, dated and attributed, with nothing to
     * suggest it is stale.
     */
    void upsert(List<Chunk> chunks, List<float[]> vectors);

    /**
     * Make the store hold exactly these chunks and nothing else.
     *
     * <p>What a reindex needs, because the knowledge base on disk is the source of truth:
     * after this the index says what the directory says, including about the sections that
     * are no longer in it.
     *
     * <p>It empties the store before writing, so a failure in between leaves it empty
     * rather than stale. That is the right way round — an empty index makes agents ask
     * people questions, which is the system working as designed, and a reindex is one
     * cheap, repeatable call.
     */
    void replaceAll(int dimensions, List<Chunk> chunks, List<float[]> vectors);

    List<Hit> search(float[] query, int limit);

    /** Total points held. Used by the reindex endpoint to report what happened. */
    long count();

    String describe();
}
