package com.loop.orchestrator.config;

import com.loop.orchestrator.knowledge.Embedder;
import com.loop.orchestrator.knowledge.HashingEmbedder;
import com.loop.orchestrator.knowledge.InMemoryVectorStore;
import com.loop.orchestrator.knowledge.KnowledgeIndexer;
import com.loop.orchestrator.knowledge.LiteLlmEmbedder;
import com.loop.orchestrator.knowledge.QdrantVectorStore;
import com.loop.orchestrator.knowledge.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KnowledgeConfig {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeConfig.class);

    /**
     * {@code hashing} needs no key and no network — start there, prove the plumbing, then
     * switch to {@code litellm} to find out whether retrieval is any <em>good</em>.
     */
    @Bean
    public Embedder embedder(@Value("${knowledge.embedder:hashing}") String kind,
                             @Value("${knowledge.dimensions:256}") int dimensions,
                             @Value("${knowledge.embedding-model:text-embedding-3-small}") String model,
                             @Value("${llm.base-url}") String baseUrl,
                             @Value("${llm.api-key}") String apiKey) {

        if ("litellm".equalsIgnoreCase(kind)) {
            log.info("embedding with {} via {}", model, baseUrl);
            return new LiteLlmEmbedder(baseUrl, apiKey, model, dimensions);
        }

        log.warn("using the offline hashing embedder — it proves the plumbing, not retrieval "
                + "quality. Set knowledge.embedder=litellm before judging whether agents can "
                + "find what they need.");
        return new HashingEmbedder(dimensions);
    }

    @Bean
    public VectorStore vectorStore(@Value("${knowledge.store:qdrant}") String kind,
                                   @Value("${qdrant.url}") String url,
                                   @Value("${qdrant.collection}") String collection) {

        if ("memory".equalsIgnoreCase(kind)) {
            log.warn("using the in-memory vector store — nothing survives a restart");
            return new InMemoryVectorStore();
        }
        return new QdrantVectorStore(url, collection);
    }

    @Bean
    public KnowledgeIndexer knowledgeIndexer(
            Embedder embedder,
            VectorStore store,
            @Value("${knowledge.relevance-floor:0.25}") double relevanceFloor,
            @Value("${knowledge.max-search-attempts:3}") int maxAttempts) {

        return new KnowledgeIndexer(embedder, store, relevanceFloor, maxAttempts);
    }
}
