package com.loop.orchestrator.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Makes sure retrieval works before any agent asks it a question.
 *
 * <p>Without this, a fresh stack fails on the first agent call with
 * {@code Collection `knowledge` doesn't exist}. Every unit test passes regardless, because
 * the in-memory store creates its collection on demand and Qdrant does not — a difference
 * that only shows up when the application actually runs.
 *
 * <p>Indexing happens only when the collection is empty. Re-embedding the whole knowledge
 * base on every restart would be free with the hashing embedder and quietly expensive with
 * a real one, and a cost that only appears in production is the kind nobody budgets for.
 */
@Component
public class KnowledgeBootstrap {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBootstrap.class);

    private final KnowledgeIndexer indexer;
    private final VectorStore store;
    private final Embedder embedder;
    private final Path knowledgeBase;

    public KnowledgeBootstrap(KnowledgeIndexer indexer,
                              VectorStore store,
                              Embedder embedder,
                              @Value("${knowledge.path:../knowledge-base}") String path) {
        this.indexer = indexer;
        this.store = store;
        this.embedder = embedder;
        this.knowledgeBase = Path.of(path);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void prepare() {
        try {
            store.ensureCollection(embedder.dimensions());
        } catch (RuntimeException e) {
            // Loud, but not fatal. The orchestrator is still useful without retrieval —
            // agents will simply get no context, and the prompts say so explicitly rather
            // than inviting them to invent.
            log.error("could not prepare the vector store — every agent will run with no "
                    + "retrieved context. Fix this before reading anything into their output.", e);
            return;
        }

        long existing = store.count();
        if (existing > 0) {
            log.info("knowledge base already indexed: {} chunk(s) in {}", existing, store.describe());
            return;
        }

        int indexed = indexer.indexDirectory(knowledgeBase);
        if (indexed == 0) {
            log.warn("nothing indexed from {} — agents will retrieve nothing, and a question "
                    + "they raise cannot be told apart from one they never looked for",
                    knowledgeBase.toAbsolutePath());
        } else {
            log.info("indexed {} chunk(s) from {} using {}",
                    indexed, knowledgeBase.toAbsolutePath(), embedder.name());
        }
    }
}
