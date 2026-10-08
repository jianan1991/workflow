package com.loop.orchestrator.knowledge;

import com.loop.orchestrator.knowledge.Knowledge.Chunk;
import com.loop.orchestrator.knowledge.Knowledge.Hit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real Qdrant, against the same assertions the in-memory store passes.
 *
 * <p><b>Why this test is worth its runtime.</b> Two implementations of a store are only
 * useful if they agree. Without this, the fast suite would keep passing against the
 * in-memory one while production retrieval quietly did something else — the exact failure
 * the offline path was introduced to avoid, moved one layer down.
 *
 * <p>Named {@code *IT} so surefire ignores it. Run with {@code mvn verify}; needs Docker.
 */
@Testcontainers
class QdrantVectorStoreIT {

    @Container
    private static final GenericContainer<?> QDRANT =
            new GenericContainer<>(DockerImageName.parse("qdrant/qdrant:v1.12.4"))
                    .withExposedPorts(6333)
                    .waitingFor(Wait.forHttp("/readyz").forPort(6333).forStatusCode(200));

    private static final Embedder EMBEDDER = new HashingEmbedder(256);

    private static final List<Chunk> CORPUS = List.of(
            chunk("throttling-policy.md", "Which identifier the limiter keys on",
                    "Rate limits key on tenant identifier rather than the api key, because "
                            + "several keys may belong to one tenant and share a single bucket."),
            chunk("testing-conventions.md", "Mutation score is the gate",
                    "Branch coverage sits at 90 percent and the pitest mutation score at 70 "
                            + "percent. Mutation is the threshold that matters."),
            chunk("tenant-isolation.md", "Every query is scoped",
                    "Every read path filters on tenant identifier at the repository layer, "
                            + "never in the controller and never by convention."));

    private QdrantVectorStore qdrant;
    private InMemoryVectorStore reference;

    @BeforeEach
    void setUp() {
        String url = "http://%s:%d".formatted(QDRANT.getHost(), QDRANT.getMappedPort(6333));
        // A fresh collection per test — a leftover one from a previous dimension would
        // fail in a way that looks like a code bug.
        qdrant = new QdrantVectorStore(url, "it-" + UUID.randomUUID());
        reference = new InMemoryVectorStore();

        List<float[]> vectors = CORPUS.stream().map(c -> EMBEDDER.embed(c.embeddable())).toList();
        for (VectorStore store : List.of(qdrant, reference)) {
            store.ensureCollection(EMBEDDER.dimensions());
            store.upsert(CORPUS, vectors);
        }
    }

    @Test
    @DisplayName("qdrant and the in-memory store rank the same corpus the same way")
    void bothStoresAgree() {
        float[] query = EMBEDDER.embed("rate limits tenant identifier api key bucket");

        List<String> fromQdrant = headings(qdrant.search(query, 3));
        List<String> fromMemory = headings(reference.search(query, 3));

        assertThat(fromQdrant)
                .as("if these diverge, the fast suite is proving something production does not do")
                .isEqualTo(fromMemory);
        assertThat(fromQdrant.getFirst()).isEqualTo("Which identifier the limiter keys on");
    }

    @Test
    @DisplayName("payload survives the round trip, so a hit still carries its date and author")
    void payloadRoundTrips() {
        Hit hit = qdrant.search(EMBEDDER.embed("mutation score pitest threshold"), 1).getFirst();

        assertThat(hit.chunk().file()).isEqualTo("testing-conventions.md");
        assertThat(hit.chunk().date()).isEqualTo("2026-09-02");
        assertThat(hit.chunk().author()).isEqualTo("jianan");
        assertThat(hit.chunk().text()).contains("70");
    }

    @Test
    @DisplayName("re-upserting an edited chunk replaces it rather than adding a second copy")
    void upsertReplaces() {
        long before = qdrant.count();

        Chunk edited = chunk("throttling-policy.md", "Which identifier the limiter keys on",
                "Rate limits key on tenant identifier. Revised wording, same decision.");
        qdrant.upsert(List.of(edited), List.of(EMBEDDER.embed(edited.embeddable())));

        assertThat(qdrant.count())
                .as("stable ids are what stop a re-index from doubling the knowledge base")
                .isEqualTo(before);
        assertThat(qdrant.search(EMBEDDER.embed("revised wording same decision"), 1)
                .getFirst().chunk().text()).contains("Revised wording");
    }

    @Test
    @DisplayName("replaceAll leaves the store holding exactly what was passed, and nothing older")
    void replaceAllRemovesWhatIsGone() {
        Chunk only = chunk("throttling-policy.md", "Fail closed",
                "When Redis is unavailable the limiter rejects every request, reversing "
                        + "the September decision.");

        qdrant.replaceAll(EMBEDDER.dimensions(), List.of(only),
                List.of(EMBEDDER.embed(only.embeddable())));

        assertThat(qdrant.count())
                .as("upsert alone could never remove a renamed heading or a deleted "
                        + "section, so an agent went on citing decisions that were reversed")
                .isEqualTo(1);
        assertThat(qdrant.search(EMBEDDER.embed("70 percent mutation score threshold"), 5))
                .as("the testing note was not passed in, so it must be gone")
                .isEmpty();
    }

    @Test
    @DisplayName("ensureCollection is safe to call again, because it runs on every start")
    void ensureCollectionIsIdempotent() {
        qdrant.ensureCollection(EMBEDDER.dimensions());
        qdrant.ensureCollection(EMBEDDER.dimensions());

        assertThat(qdrant.count()).isEqualTo(CORPUS.size());
    }

    private static List<String> headings(List<Hit> hits) {
        List<String> out = new ArrayList<>();
        hits.forEach(h -> out.add(h.chunk().heading()));
        return out;
    }

    private static Chunk chunk(String file, String heading, String text) {
        return new Chunk(MarkdownChunker.idFor(file, heading, 0), file, heading, text,
                file.startsWith("testing") ? "2026-09-02" : "2026-09-18", "jianan");
    }
}
