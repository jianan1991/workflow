package com.loop.orchestrator.knowledge;

import com.loop.orchestrator.knowledge.Knowledge.Chunk;
import com.loop.orchestrator.knowledge.Knowledge.Hit;
import com.loop.orchestrator.knowledge.Knowledge.SearchOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Indexes the knowledge-base repository into a vector store, and searches it.
 *
 * <p>The search side enforces the rule that makes the question queue trustworthy: an agent
 * gets a fixed number of reformulations, and if none of them clears the relevance floor it
 * must ask a person rather than guess. Both the queries it tried and the cap are returned,
 * because "three of three attempts used, no usable hit" is a different signal from "gave up
 * after one".
 */
public class KnowledgeIndexer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexer.class);

    /**
     * Below this cosine score a hit is noise. Deliberately a constant you can move and
     * re-measure — the right value is a property of your corpus and your embedder, and
     * anyone who tells you otherwise has not tried it on their own notes.
     */
    private final double relevanceFloor;
    private final int maxAttempts;

    private final Embedder embedder;
    private final VectorStore store;
    private final MarkdownChunker chunker = new MarkdownChunker();

    public KnowledgeIndexer(Embedder embedder, VectorStore store,
                            double relevanceFloor, int maxAttempts) {
        this.embedder = embedder;
        this.store = store;
        this.relevanceFloor = relevanceFloor;
        this.maxAttempts = maxAttempts;
    }

    /** @return how many chunks were written */
    public int indexDirectory(Path root) {
        if (!Files.isDirectory(root)) {
            log.warn("knowledge base not found at {} — retrieval will return nothing", root);
            return 0;
        }

        store.ensureCollection(embedder.dimensions());

        List<Chunk> chunks = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .forEach(p -> chunks.addAll(readChunks(root, p)));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the knowledge base at " + root, e);
        }

        if (chunks.isEmpty()) {
            log.warn("no markdown found under {}", root);
            return 0;
        }

        List<float[]> vectors = chunks.stream().map(c -> embedder.embed(c.embeddable())).toList();
        store.upsert(chunks, vectors);

        log.info("indexed {} chunk(s) from {} using {}", chunks.size(), root, embedder.name());
        return chunks.size();
    }

    private List<Chunk> readChunks(Path root, Path file) {
        try {
            String relative = root.relativize(file).toString().replace('\\', '/');
            return chunker.chunk(relative, Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    /** One query, no judgement about sufficiency. */
    public List<Hit> search(String query, int limit) {
        return store.search(embedder.embed(query), limit).stream()
                .filter(h -> h.score() >= relevanceFloor)
                .toList();
    }

    /**
     * What an agent actually calls. Tries each reformulation in turn and stops at the
     * first that clears the floor.
     *
     * @param reformulations the agent's own phrasings, best first. More than
     *                       {@code maxAttempts} of them is a bug in the agent, not a
     *                       licence to keep searching.
     */
    public SearchOutcome searchUntilSufficient(List<String> reformulations, int limit) {
        List<String> tried = new ArrayList<>();

        for (String query : reformulations) {
            if (tried.size() >= maxAttempts) {
                break;
            }
            tried.add(query);

            List<Hit> hits = search(query, limit);
            if (!hits.isEmpty()) {
                return new SearchOutcome(true, hits, List.copyOf(tried), maxAttempts);
            }
        }

        // Nothing cleared the floor. This is the signal to ask a person — and the queries
        // above are what proves the gap is real rather than a lazy agent.
        return new SearchOutcome(false, List.of(), List.copyOf(tried), maxAttempts);
    }

    public String describe() {
        return embedder.name() + " over " + store.describe();
    }
}
