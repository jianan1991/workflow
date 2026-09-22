package com.loop.orchestrator.agent;

import com.loop.orchestrator.domain.Domain.Coverage;
import com.loop.orchestrator.domain.Domain.Epic;
import com.loop.orchestrator.domain.Domain.Journey;
import com.loop.orchestrator.domain.Domain.JourneyStage;
import com.loop.orchestrator.domain.Domain.Story;
import com.loop.orchestrator.knowledge.HashingEmbedder;
import com.loop.orchestrator.knowledge.InMemoryVectorStore;
import com.loop.orchestrator.knowledge.KnowledgeIndexer;
import com.loop.orchestrator.llm.ScriptedChatModel;
import com.loop.orchestrator.workflow.EpicWorkflowImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectAgentTest {

    private static final Epic EPIC = new Epic("LOOP-14", "Per-tenant API rate limiting",
            "Developers on the Starter plan can exhaust the API.");

    private static final Journey JOURNEY = new Journey(List.of(
            new JourneyStage(1, "Integrate", "Wires up the API.", Coverage.KNOWN),
            new JourneyStage(2, "Blocked", "Requests return 429.", Coverage.KNOWN)));

    private static final String GOOD_REPLY = """
            {"structural":true,
             "stories":[
              {"id":"LOOP-14-1","title":"Tenant config lookup","tier":1,"dependsOn":[]},
              {"id":"LOOP-14-2","title":"Token bucket","tier":1,"dependsOn":[]},
              {"id":"LOOP-14-3","title":"Redis-backed limiter","tier":2,"dependsOn":["LOOP-14-2"]}
            ]}
            """;

    private ArchitectAgent agentReplying(String reply) {
        KnowledgeIndexer knowledge = new KnowledgeIndexer(
                new HashingEmbedder(256), new InMemoryVectorStore(), 0.25, 3);
        return new ArchitectAgent(
                ScriptedChatModel.of(Map.of(ArchitectAgent.NAME, reply)), knowledge, 4096);
    }

    @Test
    @DisplayName("parses stories with their tiers and dependencies")
    void parsesStories() {
        ArchitectAgent.Result result = agentReplying(GOOD_REPLY).decompose(EPIC, JOURNEY);

        assertThat(result.stories()).hasSize(3);
        assertThat(result.stories()).extracting(Story::tier).containsExactly(1, 1, 2);
        assertThat(result.stories().get(2).dependsOn()).containsExactly("LOOP-14-2");
    }

    @Test
    @DisplayName("a structural change flags the epic for the architecture gate before code exists")
    void structuralFlagIsCarried() {
        assertThat(agentReplying(GOOD_REPLY).decompose(EPIC, JOURNEY).structural()).isTrue();

        String nonStructural = GOOD_REPLY.replace("\"structural\":true", "\"structural\":false");
        assertThat(agentReplying(nonStructural).decompose(EPIC, JOURNEY).structural()).isFalse();
    }

    @Test
    @DisplayName("the graph it produces is one the fan-out can actually schedule")
    void graphIsSchedulable() {
        List<Story> stories = agentReplying(GOOD_REPLY).decompose(EPIC, JOURNEY).stories();

        List<List<Story>> layers = EpicWorkflowImpl.dependencyLayers(stories);

        assertThat(layers).hasSize(2);
        assertThat(layers.get(0)).extracting(Story::id)
                .as("the two independent stories must be free to run at once")
                .containsExactlyInAnyOrder("LOOP-14-1", "LOOP-14-2");
        assertThat(layers.get(1)).extracting(Story::id).containsExactly("LOOP-14-3");
    }

    @Test
    @DisplayName("the prompt carries the settled journey, which is what it decomposes")
    void promptCarriesJourney() {
        String prompt = agentReplying(GOOD_REPLY).buildPrompt(EPIC, JOURNEY, List.of());

        assertThat(prompt).contains("Integrate", "Blocked", "KNOWN", "LOOP-14-1");
    }

    @Nested
    @DisplayName("a graph that would deadlock the build is rejected at the source")
    class GraphValidation {

        @Test
        @DisplayName("a dependency on a story that does not exist")
        void danglingDependency() {
            String reply = """
                    {"stories":[
                      {"id":"LOOP-14-1","title":"One","tier":1,"dependsOn":["LOOP-14-9"]}
                    ]}
                    """;

            assertThatThrownBy(() -> agentReplying(reply).decompose(EPIC, JOURNEY))
                    .as("the fan-out would simply never schedule it, and the epic would look busy")
                    .isInstanceOf(AgentReplyException.class)
                    .hasMessageContaining("LOOP-14-9");
        }

        @Test
        @DisplayName("a story that depends on itself")
        void selfDependency() {
            String reply = """
                    {"stories":[
                      {"id":"LOOP-14-1","title":"One","tier":1,"dependsOn":["LOOP-14-1"]}
                    ]}
                    """;

            assertThatThrownBy(() -> agentReplying(reply).decompose(EPIC, JOURNEY))
                    .isInstanceOf(AgentReplyException.class)
                    .hasMessageContaining("depends on itself");
        }

        @Test
        @DisplayName("a cycle between two stories")
        void cycle() {
            String reply = """
                    {"stories":[
                      {"id":"LOOP-14-1","title":"One","tier":1,"dependsOn":["LOOP-14-2"]},
                      {"id":"LOOP-14-2","title":"Two","tier":1,"dependsOn":["LOOP-14-1"]}
                    ]}
                    """;

            assertThatThrownBy(() -> agentReplying(reply).decompose(EPIC, JOURNEY))
                    .isInstanceOf(AgentReplyException.class)
                    .hasMessageContaining("cycle");
        }

        @Test
        @DisplayName("two stories sharing an id")
        void duplicateId() {
            String reply = """
                    {"stories":[
                      {"id":"LOOP-14-1","title":"One","tier":1,"dependsOn":[]},
                      {"id":"LOOP-14-1","title":"Also one","tier":2,"dependsOn":[]}
                    ]}
                    """;

            assertThatThrownBy(() -> agentReplying(reply).decompose(EPIC, JOURNEY))
                    .isInstanceOf(AgentReplyException.class)
                    .hasMessageContaining("duplicate story id");
        }
    }

    @Nested
    @DisplayName("an unusable reply fails loudly")
    class BadReplies {

        @Test
        @DisplayName("an out-of-range tier is rejected, never defaulted to 1")
        void badTier() {
            String reply = """
                    {"stories":[{"id":"LOOP-14-1","title":"One","tier":5,"dependsOn":[]}]}
                    """;

            assertThatThrownBy(() -> agentReplying(reply).decompose(EPIC, JOURNEY))
                    .as("defaulting would route cross-cutting work to the cheap agent")
                    .isInstanceOf(AgentReplyException.class)
                    .hasMessageContaining("expected 1 or 2");
        }

        @Test
        @DisplayName("a missing tier is the same mistake as a wrong one")
        void missingTier() {
            String reply = """
                    {"stories":[{"id":"LOOP-14-1","title":"One","dependsOn":[]}]}
                    """;

            assertThatThrownBy(() -> agentReplying(reply).decompose(EPIC, JOURNEY))
                    .isInstanceOf(AgentReplyException.class)
                    .hasMessageContaining("expected 1 or 2");
        }

        @Test
        @DisplayName("no stories at all")
        void noStories() {
            assertThatThrownBy(() -> agentReplying("{\"stories\":[]}").decompose(EPIC, JOURNEY))
                    .isInstanceOf(AgentReplyException.class)
                    .hasMessageContaining("no stories");
        }

        @Test
        @DisplayName("a story with no id")
        void noId() {
            String reply = """
                    {"stories":[{"title":"One","tier":1,"dependsOn":[]}]}
                    """;

            assertThatThrownBy(() -> agentReplying(reply).decompose(EPIC, JOURNEY))
                    .isInstanceOf(AgentReplyException.class)
                    .hasMessageContaining("had no id");
        }
    }
}
