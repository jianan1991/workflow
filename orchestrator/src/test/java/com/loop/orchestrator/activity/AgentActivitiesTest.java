package com.loop.orchestrator.activity;

import com.loop.orchestrator.agent.ArchitectAgent;
import com.loop.orchestrator.agent.BaAgent;
import com.loop.orchestrator.agent.PmAgent;
import com.loop.orchestrator.agent.QaAgent;
import com.loop.orchestrator.agent.ReviewerAgent;
import com.loop.orchestrator.cost.AgentCostModel;
import com.loop.orchestrator.domain.Domain.Agent;
import com.loop.orchestrator.domain.Domain.Coverage;
import com.loop.orchestrator.domain.Domain.Epic;
import com.loop.orchestrator.domain.Domain.Journey;
import com.loop.orchestrator.domain.Domain.JourneyStage;
import com.loop.orchestrator.domain.Domain.QaOutcome;
import com.loop.orchestrator.domain.Domain.Question;
import com.loop.orchestrator.domain.Domain.QaResult;
import com.loop.orchestrator.domain.Domain.Story;
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
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the agents are actually in the path.
 *
 * <p>Five agents can be written, tested and beaned, and the pipeline can still run entirely
 * on canned data if nothing calls them — which is exactly what was true before this. These
 * tests assert on the model being reached, not only on the values coming back, because a
 * hardcoded return is indistinguishable from a working agent when you only check the output.
 */
class AgentActivitiesTest {

    private static final Epic EPIC = new Epic("LOOP-14", "Per-tenant API rate limiting",
            "Developers on the Starter plan can exhaust the API.");

    @TempDir
    Path knowledgeBase;

    private KnowledgeIndexer knowledge;
    private RecordingChatModel model;

    /** Wraps the scripted model so a test can see which agents were actually asked. */
    private static final class RecordingChatModel implements ChatModel {
        private final ChatModel delegate;
        private final List<String> agentsCalled = new ArrayList<>();

        RecordingChatModel(ChatModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChatResponse complete(ChatRequest request) {
            agentsCalled.add(request.agent());
            return delegate.complete(request);
        }

        @Override
        public String describe() {
            return "recording over " + delegate.describe();
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(knowledgeBase.resolve("throttling-policy.md"), """
                ---
                date: 2026-09-18
                author: jianan
                ---

                ## Plan ceilings

                Starter is 100 requests per minute. Limits key on tenant identifier rather
                than api key, because several keys share one bucket.
                """);

        knowledge = new KnowledgeIndexer(
                new HashingEmbedder(256), new InMemoryVectorStore(), 0.25, 3);
        knowledge.indexDirectory(knowledgeBase);

        model = new RecordingChatModel(scripted());
    }

    private AgentPlanningActivities planning() {
        return new AgentPlanningActivities(
                new PmAgent(model, knowledge, 4096),
                new BaAgent(model, knowledge, 4096),
                new ArchitectAgent(model, knowledge, 4096),
                costModel());
    }

    private AgentBuildActivities build() {
        return new AgentBuildActivities(
                new ReviewerAgent(model, 4096), new QaAgent(model, 4096));
    }

    @Nested
    @DisplayName("planning activities reach the agents")
    class Planning {

        @Test
        @DisplayName("mapping a journey goes through the PM agent, not a canned list")
        void journeyGoesThroughThePm() {
            Journey journey = planning().mapJourney(EPIC, List.of());

            assertThat(model.agentsCalled)
                    .as("the whole point of this wiring is that the agent is reached")
                    .containsExactly(PmAgent.NAME);
            assertThat(journey.stages()).isNotEmpty();
            assertThat(journey.holdsTogether())
                    .as("the scripted journey carries a gap, so blocking stays exercised")
                    .isFalse();
        }

        @Test
        @DisplayName("an answer closes the gap, so block-and-resume still works end to end")
        void answerClosesTheGap() {
            Journey blocked = planning().mapJourney(EPIC, List.of());
            Journey settled = planning().mapJourney(EPIC,
                    List.of(new com.loop.orchestrator.domain.Domain.Answer("Q-101", "Self-serve.")));

            assertThat(blocked.holdsTogether()).isFalse();
            assertThat(settled.holdsTogether()).isTrue();
        }

        @Test
        @DisplayName("a question records the searches that were actually run first")
        void questionCarriesAttemptedSearches() {
            JourneyStage gap = new JourneyStage(5, "Ask for more",
                    "They want a higher ceiling. By which route?", Coverage.GAP);

            Question question = planning().raiseQuestion(EPIC.id(), PmAgent.NAME, gap);

            assertThat(question.text()).isNotBlank();
            assertThat(question.searchesAttempted())
                    .as("a question with nothing tried behind it cannot be told apart from "
                            + "an agent that did not look")
                    .isNotEmpty();
            assertThat(question.attemptsAllowed()).isEqualTo(3);
            assertThat(question.journeyStage()).isEqualTo(5);
        }

        @Test
        @DisplayName("decomposition goes through the architect and produces a schedulable graph")
        void decompositionGoesThroughTheArchitect() {
            List<Story> stories = planning().decompose(EPIC, settledJourney());

            assertThat(model.agentsCalled).contains(ArchitectAgent.NAME);
            assertThat(stories).isNotEmpty();
            assertThat(stories).allSatisfy(story ->
                    assertThat(story.tier()).isBetween(1, 2));
        }

        @Test
        @DisplayName("the estimate prices the stories the architect actually produced")
        void estimateUsesRealStories() {
            List<Story> stories = planning().decompose(EPIC, settledJourney());

            var estimate = planning().estimate(EPIC.id(), stories, 1.45);

            assertThat(estimate.dollars()).isGreaterThan(0.0);
            assertThat(estimate.humanMinutes()).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("build activities reach the agents")
    class Build {

        private static final Story STORY =
                new Story("LOOP-14-3", "Redis-backed limiter", 2, List.of());

        @Test
        @DisplayName("review goes through the reviewer agent")
        void reviewGoesThroughTheAgent() {
            var review = build().review(STORY, Agent.SENIOR, 1);

            assertThat(model.agentsCalled).containsExactly(ReviewerAgent.NAME);
            assertThat(review.verdict()).isNotBlank();
        }

        @Test
        @DisplayName("QA reports UNVERIFIABLE while nothing runs the software — it does not report green")
        void qaDoesNotReportGreenOnNothing() {
            QaResult result = build().runQa(STORY, 1);

            assertThat(model.agentsCalled).containsExactly(QaAgent.NAME);
            assertThat(result.unverifiableCount())
                    .as("a pipeline that executes nothing must not report passes — this is "
                            + "the failure the whole observation split exists to prevent")
                    .isGreaterThan(0);
            assertThat(result.outcome())
                    .as("unverifiable does not fail a story; it becomes a person's checklist")
                    .isEqualTo(QaOutcome.PASS);
        }

        @Test
        @DisplayName("a tier 2 story never auto-approves, whatever the reviewer says")
        void tierTwoNeverAutoApproves() {
            var review = build().review(STORY, Agent.SENIOR, 1);
            var ci = build().runCi(STORY, 1);

            assertThat(build().mayAutoApprove(STORY, review, ci)).isFalse();
        }
    }

    // ------------------------------------------------------------------ setup

    private static Journey settledJourney() {
        return new Journey(List.of(
                new JourneyStage(1, "Integrate", "Wires up the API.", Coverage.KNOWN),
                new JourneyStage(2, "Blocked", "Requests return 429.", Coverage.KNOWN)));
    }

    /** Mirrors the production script closely enough to prove the wiring, without Spring. */
    private static ChatModel scripted() {
        Map<String, Function<ChatModel.ChatRequest, String>> script = new LinkedHashMap<>();

        String journey = """
                {"stages":[
                  {"index":1,"name":"Integrate","coverage":"KNOWN","description":"Wires up."},
                  {"index":5,"name":"Ask for more","coverage":"%s","description":"Who grants it?"}
                ]}
                """;

        script.put(PmAgent.NAME, request -> {
            if (request.system().contains("Write the question you would ask")) {
                return """
                        {"question":"Who grants a higher limit, and how quickly?",
                         "searchQueries":["rate limit increase","plan ceiling approval","who approves limits"],
                         "targetKbFile":"kb/throttling-policy.md"}
                        """;
            }
            return journey.formatted(
                    request.user().contains("Decisions already made") ? "KNOWN" : "GAP");
        });

        script.put(BaAgent.NAME, request -> """
                {"criteria":[{"text":"Returns 429 after the ceiling.","testability":"AUTOMATED",
                              "sources":["throttling-policy.md"]}],"unanswerable":[]}
                """);

        script.put(ArchitectAgent.NAME, request -> """
                {"structural":true,"stories":[
                  {"id":"LOOP-14-1","title":"Tenant config lookup","tier":1,"dependsOn":[]},
                  {"id":"LOOP-14-2","title":"Redis-backed limiter","tier":2,"dependsOn":["LOOP-14-1"]}
                ]}
                """);

        script.put(ReviewerAgent.NAME, request -> """
                {"matchesCriteria":false,"scopeExceeded":false,
                 "verdict":"There is no diff to review — nothing implemented this story.",
                 "unmetCriteria":["AC-1"],"outOfScopeFiles":[]}
                """);

        script.put(QaAgent.NAME, request -> {
            // Mirrors the real behaviour: nothing captured means nothing is settled.
            StringBuilder verdicts = new StringBuilder();
            for (String id : List.of("AC-1", "AC-2")) {
                if (!verdicts.isEmpty()) {
                    verdicts.append(",");
                }
                verdicts.append("""
                        {"criterionId":"%s","outcome":"UNVERIFIABLE",
                         "evidence":"Nothing was captured."}""".formatted(id));
            }
            return "{\"verdicts\":[" + verdicts + "],\"concerns\":[]}";
        });

        return new ScriptedChatModel(script);
    }

    private static AgentCostModel costModel() {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of("..", "cost-model.properties"))) {
            properties.load(reader);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return new AgentCostModel(properties);
    }
}
