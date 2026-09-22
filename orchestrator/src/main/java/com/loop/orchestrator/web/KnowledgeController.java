package com.loop.orchestrator.web;

import com.loop.orchestrator.knowledge.Knowledge.Hit;
import com.loop.orchestrator.knowledge.Knowledge.SearchOutcome;
import com.loop.orchestrator.knowledge.KnowledgeIndexer;
import com.loop.orchestrator.knowledge.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Lets a person drive retrieval by hand.
 *
 * <p>This exists because of the order of operations: index the knowledge base, then
 * <em>prove retrieval quality manually</em>, and only then let an agent depend on it. An
 * agent that silently gets bad context produces a plausible wrong PR, and you will spend
 * far longer working out why than you would spend running a dozen queries here first.
 */
@RestController
@RequestMapping("/knowledge")
public class KnowledgeController {

    private final KnowledgeIndexer indexer;
    private final VectorStore store;
    private final Path knowledgeBase;

    public KnowledgeController(KnowledgeIndexer indexer,
                               VectorStore store,
                               @Value("${knowledge.path:../knowledge-base}") String path) {
        this.indexer = indexer;
        this.store = store;
        this.knowledgeBase = Path.of(path);
    }

    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindex() {
        int chunks = indexer.indexDirectory(knowledgeBase);
        return ResponseEntity.ok(Map.of(
                "source", knowledgeBase.toAbsolutePath().toString(),
                "chunksIndexed", chunks,
                "pointsInStore", store.count(),
                "using", indexer.describe()));
    }

    /** One query, scored. Run a dozen of these before trusting an agent with retrieval. */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestParam String q,
                                                      @RequestParam(defaultValue = "5") int limit) {
        List<Hit> hits = indexer.search(q, limit);
        return ResponseEntity.ok(Map.of(
                "query", q,
                "hits", hits,
                "using", indexer.describe()));
    }

    /**
     * What an agent does: try each phrasing in turn, stop at the first that clears the
     * relevance floor, and give up after the cap.
     *
     * <p>Use it to sanity-check the cap itself. If agents routinely burn all three
     * attempts on questions you would have answered from the notes, the problem is
     * retrieval, not the knowledge base.
     */
    @PostMapping("/ask")
    public ResponseEntity<SearchOutcome> ask(@RequestBody Map<String, List<String>> body) {
        List<String> reformulations = body.getOrDefault("queries", List.of());
        return ResponseEntity.ok(indexer.searchUntilSufficient(reformulations, 5));
    }
}
