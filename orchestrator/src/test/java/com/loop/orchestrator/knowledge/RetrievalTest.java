package com.loop.orchestrator.knowledge;

import com.loop.orchestrator.knowledge.Knowledge.Chunk;
import com.loop.orchestrator.knowledge.Knowledge.Hit;
import com.loop.orchestrator.knowledge.Knowledge.SearchOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Retrieval, end to end, with no container running and no API key.
 *
 * <p>That is deliberate. Retrieval is the component most likely to go untested because
 * "it needs Qdrant" — and it is also the one whose silent failure is most expensive, since
 * an agent given bad context produces a plausible wrong pull request rather than an error.
 */
class RetrievalTest {

    private static final String THROTTLING = """
            ---
            date: 2026-09-18
            author: jianan
            ---

            # Throttling policy

            ## Which identifier the limiter keys on

            Rate limits key on tenant identifier, not on the api key. Several keys may belong
            to one tenant and they share a single bucket, so a tenant cannot buy more headroom
            by minting extra keys.

            ## Plan ceilings

            Starter is 100 requests per minute. Anything above 250 needs internal approval,
            which usually takes about a day.
            """;

    private static final String TESTING = """
            ---
            date: 2026-09-02
            author: jianan
            ---

            # Testing conventions

            ## Mutation score is the gate

            Branch coverage sits at 90 percent and the pitest mutation score at 70 percent.
            Mutation is the threshold that matters because a test with no assertions kills no
            mutants and cannot fake it.
            """;

    @TempDir
    Path knowledgeBase;

    private KnowledgeIndexer indexer;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(knowledgeBase.resolve("throttling-policy.md"), THROTTLING);
        Files.writeString(knowledgeBase.resolve("testing-conventions.md"), TESTING);

        indexer = new KnowledgeIndexer(
                new HashingEmbedder(256), new InMemoryVectorStore(), 0.25, 3);
    }

    @Test
    @DisplayName("a query lands on the note that actually answers it")
    void findsTheRightNote() {
        indexer.indexDirectory(knowledgeBase);

        List<Hit> hits = indexer.search("rate limits tenant identifier api key bucket", 3);

        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().chunk().file()).isEqualTo("throttling-policy.md");
        assertThat(hits.getFirst().chunk().heading()).contains("identifier the limiter keys on");
    }

    @Test
    @DisplayName("every hit carries a date and an author, so a stale answer can be spotted")
    void hitsAreAttributed() {
        indexer.indexDirectory(knowledgeBase);

        Hit hit = indexer.search("mutation score pitest assertions threshold", 1).getFirst();

        assertThat(hit.chunk().date()).isEqualTo("2026-09-02");
        assertThat(hit.chunk().author()).isEqualTo("jianan");
    }

    @Test
    @DisplayName("re-indexing an edited note replaces it instead of leaving the old text beside it")
    void reindexingUpdatesRatherThanDuplicates() throws IOException {
        indexer.indexDirectory(knowledgeBase);

        InMemoryVectorStore store = new InMemoryVectorStore();
        KnowledgeIndexer counting = new KnowledgeIndexer(new HashingEmbedder(256), store, 0.25, 3);

        int first = counting.indexDirectory(knowledgeBase);
        Files.writeString(knowledgeBase.resolve("throttling-policy.md"),
                THROTTLING.replace("100 requests", "150 requests"));
        int second = counting.indexDirectory(knowledgeBase);

        assertThat(second).isEqualTo(first);
        assertThat(store.count())
                .as("stable chunk ids mean an edit updates the entry, not doubles it")
                .isEqualTo(first);
    }

    @Nested
    @DisplayName("the attempt cap")
    class AttemptCap {

        @Test
        @DisplayName("stops at the first phrasing that works, without burning the rest")
        void stopsAtTheFirstSufficientQuery() {
            indexer.indexDirectory(knowledgeBase);

            SearchOutcome outcome = indexer.searchUntilSufficient(List.of(
                    "rate limits tenant identifier api key bucket",
                    "throttling",
                    "limiter"), 5);

            assertThat(outcome.sufficient()).isTrue();
            assertThat(outcome.queriesTried()).hasSize(1);
        }

        @Test
        @DisplayName("gives up after the cap and reports what it tried, which is what proves the gap is real")
        void givesUpAfterTheCap() {
            indexer.indexDirectory(knowledgeBase);

            SearchOutcome outcome = indexer.searchUntilSufficient(List.of(
                    "webhook delivery guarantees",
                    "idempotent receiver semantics",
                    "at most once billing callback",
                    "a fourth phrasing it must never reach"), 5);

            assertThat(outcome.sufficient()).isFalse();
            assertThat(outcome.hits()).isEmpty();
            assertThat(outcome.queriesTried())
                    .as("the cap is a hard stop, not a suggestion")
                    .hasSize(3);
            assertThat(outcome.attemptsAllowed()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("failure modes worth being loud about")
    class FailureModes {

        @Test
        @DisplayName("a missing knowledge base indexes nothing rather than throwing")
        void missingDirectoryIsSurvivable() {
            int chunks = indexer.indexDirectory(knowledgeBase.resolve("does-not-exist"));

            assertThat(chunks).isZero();
        }

        @Test
        @DisplayName("an empty index answers nothing found, not a wrong answer")
        void emptyIndexReturnsNothing() {
            assertThat(indexer.search("anything at all", 5)).isEmpty();
        }

        @Test
        @DisplayName("mismatched vector dimensions fail loudly instead of scoring nonsense")
        void dimensionMismatchThrows() {
            InMemoryVectorStore store = new InMemoryVectorStore();
            store.ensureCollection(256);
            store.upsert(
                    List.of(new Chunk("id", "f.md", "h", "text", "2026-01-01", "someone")),
                    List.of(new HashingEmbedder(256).embed("text")));

            assertThatThrownBy(() -> store.search(new HashingEmbedder(64).embed("text"), 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dimension mismatch");
        }
    }
}
