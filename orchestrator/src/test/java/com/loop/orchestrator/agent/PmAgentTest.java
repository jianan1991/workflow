package com.loop.orchestrator.agent;

import com.loop.orchestrator.domain.Domain.Answer;
import com.loop.orchestrator.domain.Domain.Coverage;
import com.loop.orchestrator.domain.Domain.Epic;
import com.loop.orchestrator.domain.Domain.Journey;
import com.loop.orchestrator.knowledge.HashingEmbedder;
import com.loop.orchestrator.knowledge.InMemoryVectorStore;
import com.loop.orchestrator.knowledge.KnowledgeIndexer;
import com.loop.orchestrator.llm.ChatModel;
import com.loop.orchestrator.llm.ScriptedChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The PM agent, end to end, with no key and no network.
 *
 * <p>What a scripted model lets you test is everything except the answer: that the prompt
 * carries the epic, the retrieved notes and any decisions already made; that a well-formed
 * reply parses; that a malformed one fails loudly rather than producing a half-built
 * journey; and that token usage reaches the cost path.
 *
 * <p>What it cannot test is whether the questions the agent raises are good ones. That is
 * phase 6, it needs a real model, and it needs a person reading the output.
 */
class PmAgentTest {

    private static final Epic EPIC = new Epic("LOOP-14", "Per-tenant API rate limiting",
            "Developers on the Starter plan can exhaust the API and nothing warns them.");

    private static final String GOOD_REPLY = """
            {"stages":[
              {"index":1,"name":"Integrate","coverage":"KNOWN","description":"Wires up the API."},
              {"index":2,"name":"Ask for more","coverage":"GAP","description":"Who grants it?"}
            ]}
            """;

    @TempDir
    Path knowledgeBase;

    private KnowledgeIndexer knowledge;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(knowledgeBase.resolve("throttling-policy.md"), """
                ---
                date: 2026-09-18
                author: jianan
                ---

                ## Plan ceilings

                Starter is 100 requests per minute. Rate limits key on tenant identifier
                rather than api key, because several keys share one bucket.
                """);

        knowledge = new KnowledgeIndexer(
                new HashingEmbedder(256), new InMemoryVectorStore(), 0.25, 3);
        knowledge.indexDirectory(knowledgeBase);
    }

    private PmAgent agentReplying(String reply) {
        return new PmAgent(ScriptedChatModel.of(Map.of(PmAgent.NAME, reply)), knowledge, 4096);
    }

    @Test
    @DisplayName("parses a journey and keeps the gap, because a gap is what blocks decomposition")
    void parsesJourney() {
        Journey journey = agentReplying(GOOD_REPLY).mapJourney(EPIC, List.of());

        assertThat(journey.stages()).hasSize(2);
        assertThat(journey.holdsTogether()).isFalse();
        assertThat(journey.gaps()).singleElement()
                .satisfies(gap -> assertThat(gap.name()).isEqualTo("Ask for more"));
    }

    @Test
    @DisplayName("strips a code fence, because models add one even when told not to")
    void stripsCodeFence() {
        Journey journey = agentReplying("```json\n" + GOOD_REPLY + "\n```")
                .mapJourney(EPIC, List.of());

        assertThat(journey.stages()).hasSize(2);
    }

    @Nested
    @DisplayName("the prompt")
    class Prompt {

        @Test
        @DisplayName("carries the epic and the notes retrieved for it")
        void carriesEpicAndContext() {
            String prompt = agentReplying(GOOD_REPLY)
                    .buildPrompt(EPIC, List.of(), knowledge.search("rate limits tenant", 6));

            assertThat(prompt).contains("LOOP-14", "Per-tenant API rate limiting", "exhaust the API");
            assertThat(prompt)
                    .as("retrieval is pointless if what it finds never reaches the prompt")
                    .contains("Plan ceilings", "100 requests per minute");
        }

        @Test
        @DisplayName("dates and attributes every retrieved note, so a stale one reads as stale")
        void attributesContext() {
            String prompt = agentReplying(GOOD_REPLY)
                    .buildPrompt(EPIC, List.of(), knowledge.search("rate limits tenant", 6));

            assertThat(prompt).contains("2026-09-18", "jianan", "throttling-policy.md");
        }

        @Test
        @DisplayName("puts human decisions last and marks them settled, because they outrank everything else")
        void answersOutrankEverything() {
            String prompt = agentReplying(GOOD_REPLY).buildPrompt(EPIC,
                    List.of(new Answer("Q-101", "Self-serve, with approval above 250 req/min.")),
                    knowledge.search("rate limits tenant", 6));

            assertThat(prompt).contains("Self-serve, with approval above 250");
            assertThat(prompt).contains("Do not raise them as gaps again");
            assertThat(prompt.indexOf("Decisions already made"))
                    .as("a decision the person made must come after the notes it overrides")
                    .isGreaterThan(prompt.indexOf("What we already know"));
        }

        @Test
        @DisplayName("omits the context heading entirely when retrieval found nothing")
        void noContextHeadingWhenEmpty() {
            KnowledgeIndexer empty = new KnowledgeIndexer(
                    new HashingEmbedder(256), new InMemoryVectorStore(), 0.25, 3);
            String prompt = new PmAgent(ScriptedChatModel.of(Map.of(PmAgent.NAME, GOOD_REPLY)),
                    empty, 4096).buildPrompt(EPIC, List.of(), List.of());

            assertThat(prompt).doesNotContain("What we already know");
        }
    }

    @Nested
    @DisplayName("an unusable reply fails loudly")
    class BadReplies {

        @Test
        @DisplayName("not JSON at all")
        void notJson() {
            assertThatThrownBy(() -> agentReplying("Sure! Here is the journey:")
                    .mapJourney(EPIC, List.of()))
                    .isInstanceOf(AgentReplyException.class)
                    .hasMessageContaining("not valid JSON")
                    .hasMessageContaining("LOOP-14");
        }

        @Test
        @DisplayName("JSON, but no stages")
        void noStages() {
            assertThatThrownBy(() -> agentReplying("{\"journey\":[]}").mapJourney(EPIC, List.of()))
                    .isInstanceOf(AgentReplyException.class)
                    .hasMessageContaining("no stages array");
        }

        @Test
        @DisplayName("an unreadable coverage is rejected, never defaulted to KNOWN")
        void unreadableCoverage() {
            String reply = """
                    {"stages":[{"index":1,"name":"Ask","coverage":"probably fine",
                                "description":"..."}]}
                    """;

            assertThatThrownBy(() -> agentReplying(reply).mapJourney(EPIC, List.of()))
                    .as("defaulting here would release an epic with an open question inside it")
                    .isInstanceOf(AgentReplyException.class)
                    .hasMessageContaining("expected KNOWN, PARTIAL or GAP");
        }

        @Test
        @DisplayName("an empty reply")
        void emptyReply() {
            assertThatThrownBy(() -> agentReplying("").mapJourney(EPIC, List.of()))
                    .isInstanceOf(AgentReplyException.class);
        }
    }

    @Nested
    @DisplayName("cost accounting")
    class Cost {

        @Test
        @DisplayName("reports non-zero token usage, so the cost path is exercised on every run")
        void reportsTokens() {
            List<ChatModel.ChatResponse> seen = new ArrayList<>();
            ChatModel recording = new ChatModel() {
                private final ChatModel delegate =
                        ScriptedChatModel.of(Map.of(PmAgent.NAME, GOOD_REPLY));

                @Override
                public ChatResponse complete(ChatRequest request) {
                    ChatResponse response = delegate.complete(request);
                    seen.add(response);
                    return response;
                }

                @Override
                public String describe() {
                    return "recording";
                }
            };

            new PmAgent(recording, knowledge, 4096).mapJourney(EPIC, List.of());

            assertThat(seen).singleElement().satisfies(response -> {
                assertThat(response.inputTokens())
                        .as("zero would let a cost bug survive until the first real invoice")
                        .isGreaterThan(0);
                assertThat(response.outputTokens()).isGreaterThan(0);
                assertThat(response.costUsd(5.00, 25.00)).isGreaterThan(0.0);
            });
        }

        @Test
        @DisplayName("an unscripted agent fails rather than silently receiving nothing")
        void unscriptedAgentFails() {
            ScriptedChatModel model = ScriptedChatModel.of(Map.of("ba", GOOD_REPLY));

            assertThatThrownBy(() -> new PmAgent(model, knowledge, 4096)
                    .mapJourney(EPIC, List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no scripted reply for agent 'pm'");
        }
    }
}
