package com.loop.orchestrator.agent;

import com.loop.orchestrator.domain.Domain.Coverage;
import com.loop.orchestrator.domain.Domain.Epic;
import com.loop.orchestrator.domain.Domain.JourneyStage;
import com.loop.orchestrator.domain.Domain.Testability;
import com.loop.orchestrator.knowledge.HashingEmbedder;
import com.loop.orchestrator.knowledge.InMemoryVectorStore;
import com.loop.orchestrator.knowledge.KnowledgeIndexer;
import com.loop.orchestrator.llm.ScriptedChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class BaAgentTest {

    private static final Epic EPIC = new Epic("LOOP-14", "Per-tenant API rate limiting",
            "Developers on the Starter plan can exhaust the API.");

    private static final JourneyStage STAGE = new JourneyStage(4, "Blocked",
            "Requests start returning 429 once the ceiling is reached.", Coverage.KNOWN);

    private static final String GOOD_REPLY = """
            {"criteria":[
              {"text":"The 101st request in 60s returns 429 with Retry-After.",
               "testability":"AUTOMATED","sources":["throttling-policy.md"]},
              {"text":"The message is clear enough to act on.",
               "testability":"HUMAN_ONLY","sources":[]}
            ],"unanswerable":[]}
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

                Starter is 100 requests per minute, returning 429 once the ceiling is
                reached. Limits key on tenant identifier rather than api key.
                """);

        knowledge = new KnowledgeIndexer(
                new HashingEmbedder(256), new InMemoryVectorStore(), 0.25, 3);
        knowledge.indexDirectory(knowledgeBase);
    }

    private BaAgent agentReplying(String reply) {
        return new BaAgent(ScriptedChatModel.of(Map.of(BaAgent.NAME, reply)), knowledge, 4096);
    }

    @Test
    @DisplayName("parses criteria and keeps each one's testability")
    void parsesCriteria() {
        BaAgent.Result result = agentReplying(GOOD_REPLY).writeCriteria(EPIC, STAGE);

        assertThat(result.criteria()).hasSize(2);
        assertThat(result.criteria().get(0).testability()).isEqualTo(Testability.AUTOMATED);
        assertThat(result.criteria().get(1).testability()).isEqualTo(Testability.HUMAN_ONLY);
        assertThat(result.settled()).isTrue();
    }

    @Test
    @DisplayName("keeps the citation, so a criterion can be traced to what it rests on")
    void keepsCitations() {
        BaAgent.Result result = agentReplying(GOOD_REPLY).writeCriteria(EPIC, STAGE);

        assertThat(result.criteria().get(0).kbSources()).containsExactly("throttling-policy.md");
    }

    @Test
    @DisplayName("reports the testability split, which is the exposure shown at gate 1")
    void reportsSplit() {
        BaAgent.Result result = agentReplying(GOOD_REPLY).writeCriteria(EPIC, STAGE);

        assertThat(result.split().automated()).isEqualTo(1);
        assertThat(result.split().humanOnly()).isEqualTo(1);
        assertThat(result.split().total()).isEqualTo(2);
        assertThat(result.split().automatedShare()).isCloseTo(0.5, within(0.001));
    }

    @Nested
    @DisplayName("knowing when it does not know")
    class Unanswerable {

        @Test
        @DisplayName("an unanswerable question leaves the stage unsettled, so it cannot become a story")
        void unanswerableBlocksTheStage() {
            String reply = """
                    {"criteria":[],
                     "unanswerable":["Does anything warn the developer before they are throttled?"]}
                    """;

            BaAgent.Result result = agentReplying(reply).writeCriteria(EPIC, STAGE);

            assertThat(result.settled())
                    .as("a stage with an open question must not be decomposed")
                    .isFalse();
            assertThat(result.unanswerable()).hasSize(1);
        }

        @Test
        @DisplayName("criteria plus an open question is still unsettled — partial is not done")
        void partialIsNotSettled() {
            String reply = """
                    {"criteria":[{"text":"Returns 429.","testability":"AUTOMATED","sources":[]}],
                     "unanswerable":["What should the body contain?"]}
                    """;

            assertThat(agentReplying(reply).writeCriteria(EPIC, STAGE).settled()).isFalse();
        }

        @Test
        @DisplayName("warns the agent off guessing when retrieval found nothing")
        void emptyContextIsStated() {
            KnowledgeIndexer empty = new KnowledgeIndexer(
                    new HashingEmbedder(256), new InMemoryVectorStore(), 0.25, 3);

            String prompt = new BaAgent(ScriptedChatModel.of(Map.of(BaAgent.NAME, GOOD_REPLY)),
                    empty, 4096).buildPrompt(EPIC, STAGE, List.of());

            assertThat(prompt)
                    .as("silence here produces confident criteria invented out of nothing")
                    .contains("Nothing was found", "unanswerable rather than guessing");
        }
    }

    @Nested
    @DisplayName("the prompt")
    class Prompt {

        @Test
        @DisplayName("names the one stage being worked on, not the whole journey")
        void namesTheStage() {
            String prompt = agentReplying(GOOD_REPLY)
                    .buildPrompt(EPIC, STAGE, knowledge.search("429 ceiling", 6));

            assertThat(prompt).contains("Stage 4", "Blocked", "429");
        }

        @Test
        @DisplayName("asks for citations by filename, so a source can actually be checked")
        void asksForCitations() {
            String prompt = agentReplying(GOOD_REPLY)
                    .buildPrompt(EPIC, STAGE, knowledge.search("429 ceiling", 6));

            assertThat(prompt).contains("Cite these by filename", "throttling-policy.md");
        }
    }

    @Nested
    @DisplayName("an unusable reply fails loudly")
    class BadReplies {

        @Test
        @DisplayName("an unreadable testability is rejected, never defaulted to AUTOMATED")
        void unreadableTestability() {
            String reply = """
                    {"criteria":[{"text":"Returns 429.","testability":"probably testable",
                                  "sources":[]}],"unanswerable":[]}
                    """;

            assertThatThrownBy(() -> agentReplying(reply).writeCriteria(EPIC, STAGE))
                    .as("defaulting would have QA report a pass on something it never checked")
                    .isInstanceOf(AgentReplyException.class)
                    .hasMessageContaining("expected AUTOMATED, OBSERVABLE");
        }

        @Test
        @DisplayName("nothing at all — no criteria and nothing marked unanswerable")
        void emptyReply() {
            assertThatThrownBy(() ->
                    agentReplying("{\"criteria\":[],\"unanswerable\":[]}")
                            .writeCriteria(EPIC, STAGE))
                    .isInstanceOf(AgentReplyException.class)
                    .hasMessageContaining("no criteria and nothing marked unanswerable");
        }

        @Test
        @DisplayName("a criterion with no text")
        void criterionWithNoText() {
            String reply = """
                    {"criteria":[{"text":"  ","testability":"AUTOMATED","sources":[]}],
                     "unanswerable":[]}
                    """;

            assertThatThrownBy(() -> agentReplying(reply).writeCriteria(EPIC, STAGE))
                    .isInstanceOf(AgentReplyException.class)
                    .hasMessageContaining("had no text");
        }

        @Test
        @DisplayName("the failure names the epic and stage, so the log alone is enough to debug")
        void failureIsLocatable() {
            assertThatThrownBy(() -> agentReplying("not json").writeCriteria(EPIC, STAGE))
                    .isInstanceOf(AgentReplyException.class)
                    .hasMessageContaining("LOOP-14 stage 4");
        }
    }
}
