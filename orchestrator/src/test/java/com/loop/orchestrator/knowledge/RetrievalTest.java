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

    @Test
    @DisplayName("a note with the same heading twice keeps both sections, rather than losing one")
    void repeatedHeadingsBothSurvive() throws IOException {
        Files.writeString(knowledgeBase.resolve("decisions.md"), """
                ---
                date: 2026-10-08
                author: jianan
                ---

                ## Decision

                Rate limits key on the tenant identifier rather than the api key, because
                several keys belonging to one tenant have to draw from a single bucket.

                ## Decision

                The Starter plan ceiling is one hundred requests per minute, and raising it
                is a support action rather than a self-serve one.
                """);

        indexer.indexDirectory(knowledgeBase);

        // The first section is the one that used to disappear: both chunks hashed to the
        // same id from file plus heading, so the second overwrote it in the store and the
        // decision was simply not in the index any more.
        assertThat(indexer.search("tenant identifier single bucket", 5))
                .as("an agent that cannot find an answered decision asks a person a "
                        + "question the knowledge base already answered")
                .isNotEmpty();
        assertThat(indexer.search("Starter plan ceiling support action", 5)).isNotEmpty();
    }

    @Test
    @DisplayName("a section deleted from a note leaves the index, rather than being cited forever")
    void deletedSectionsLeaveTheIndex() throws IOException {
        Files.writeString(knowledgeBase.resolve("reversed.md"), """
                ---
                date: 2026-09-01
                author: jianan
                ---

                ## Fail open

                When Redis is unavailable the limiter lets every request through, which was
                agreed deliberately in September as the lesser of the two risks.
                """);

        indexer.indexDirectory(knowledgeBase);
        assertThat(indexer.search("Redis unavailable lets every request through", 5))
                .isNotEmpty();

        // Reversed in October. The note is rewritten, and the old decision must stop being
        // retrievable - it used to stay in the store forever, dated and attributed, with
        // nothing to suggest an agent should not cite it.
        Files.writeString(knowledgeBase.resolve("reversed.md"), """
                ---
                date: 2026-10-08
                author: jianan
                ---

                ## Fail closed

                When Redis is unavailable the limiter rejects every request, reversing the
                September decision after the incident showed what fail-open costs.
                """);
        indexer.indexDirectory(knowledgeBase);

        assertThat(indexer.search("agreed deliberately in September lesser of the two risks", 5))
                .as("citing a decision that was reversed is worse than finding nothing, "
                        + "because it comes with a date and an author on it")
                .isEmpty();
        assertThat(indexer.search("rejects every request reversing the September decision", 5))
                .isNotEmpty();
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
