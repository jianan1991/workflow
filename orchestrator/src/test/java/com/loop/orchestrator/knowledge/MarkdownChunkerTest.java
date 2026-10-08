package com.loop.orchestrator.knowledge;

import com.loop.orchestrator.knowledge.Knowledge.Chunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownChunkerTest {

    private final MarkdownChunker chunker = new MarkdownChunker();

    @Test
    @DisplayName("splits on headings, so a decision stays attached to its reasoning")
    void splitsBySection() {
        List<Chunk> chunks = chunker.chunk("policy.md", """
                ---
                date: 2026-09-18
                author: jianan
                ---

                # Throttling policy

                ## Which identifier the limiter keys on

                Rate limits key on tenant identifier rather than on the api key, because
                several keys may belong to one tenant and they share a single bucket.

                ## Algorithm

                A token bucket rather than a fixed window, because fixed windows allow a
                caller to burst to twice the limit across the window boundary.
                """);

        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(Chunk::heading)
                .containsExactly("Which identifier the limiter keys on", "Algorithm");
        assertThat(chunks.getFirst().text())
                .as("the reason must travel with the ruling — it is the half an agent needs")
                .contains("share a single bucket");
    }

    @Test
    @DisplayName("reads the date and author off the frontmatter")
    void readsFrontmatter() {
        List<Chunk> chunks = chunker.chunk("note.md", """
                ---
                date: 2026-09-18
                author: jianan
                ---

                ## A heading

                Body text long enough to stand on its own as an indexable section here.
                """);

        assertThat(chunks.getFirst().date()).isEqualTo("2026-09-18");
        assertThat(chunks.getFirst().author()).isEqualTo("jianan");
    }

    @Test
    @DisplayName("a note with no frontmatter is marked undated rather than silently trusted")
    void missingFrontmatterIsMarked() {
        List<Chunk> chunks = chunker.chunk("legacy.md", """
                ## Old note

                Something somebody wrote down years ago and never signed or dated at all.
                """);

        assertThat(chunks.getFirst().date()).isEqualTo("undated");
        assertThat(chunks.getFirst().author()).isEqualTo("unattributed");
    }

    @Test
    @DisplayName("the chunk id is stable across runs, so an edit updates rather than duplicates")
    void idsAreStable() {
        assertThat(MarkdownChunker.idFor("policy.md", "Algorithm", 0))
                .isEqualTo(MarkdownChunker.idFor("policy.md", "Algorithm", 0))
                .isNotEqualTo(MarkdownChunker.idFor("policy.md", "Ceilings", 0))
                .as("a repeated heading used to produce one id for both sections, and the "
                        + "second silently overwrote the first in every store")
                .isNotEqualTo(MarkdownChunker.idFor("policy.md", "Algorithm", 1));
    }

    @Test
    @DisplayName("the heading is part of what gets embedded, because it carries real signal")
    void headingIsEmbedded() {
        Chunk chunk = new Chunk("id", "f.md", "Plan ceilings", "Starter is 100 rpm.",
                "2026-09-18", "jianan");

        assertThat(chunk.embeddable()).startsWith("Plan ceilings");
    }

    @Test
    @DisplayName("a stub section too short to stand alone is folded in, not indexed as noise")
    void tinySectionsAreFoldedForward() {
        List<Chunk> chunks = chunker.chunk("note.md", """
                ## TODO

                Fill in.

                ## The real section

                A properly written section with enough substance to be worth retrieving on
                its own when an agent goes looking for it.
                """);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().text()).contains("Fill in.");
    }
}
