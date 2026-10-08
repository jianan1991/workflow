package com.loop.orchestrator.activity;

import com.loop.orchestrator.agent.ArchitectAgent;
import com.loop.orchestrator.agent.BaAgent;
import com.loop.orchestrator.agent.DevAgent;
import com.loop.orchestrator.agent.ScriptedDevAgent;
import com.loop.orchestrator.agent.PmAgent;
import com.loop.orchestrator.agent.QaAgent;
import com.loop.orchestrator.agent.ReviewerAgent;
import com.loop.orchestrator.cost.AgentCostModel;
import com.loop.orchestrator.domain.Domain.AcceptanceCriterion;
import com.loop.orchestrator.domain.Domain.Agent;
import com.loop.orchestrator.domain.Domain.CiResult;
import com.loop.orchestrator.domain.Domain.Coverage;
import com.loop.orchestrator.domain.Domain.Epic;
import com.loop.orchestrator.domain.Domain.Journey;
import com.loop.orchestrator.domain.Domain.JourneyStage;
import com.loop.orchestrator.domain.Domain.QaOutcome;
import com.loop.orchestrator.domain.Domain.Question;
import com.loop.orchestrator.domain.Domain.QaResult;
import com.loop.orchestrator.domain.Domain.ReviewResult;
import com.loop.orchestrator.domain.Domain.Story;
import com.loop.orchestrator.domain.Domain.Testability;
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

    /**
     * A dev agent that remembers what it was asked to build, and can be told to fail.
     *
     * <p>The scripted agent cannot answer either question these tests ask: whether the
     * acceptance criteria actually arrived, and what a failure turns into on the way back
     * out. Both were wrong, and both looked fine from the outside.
     */
    private static final class RecordingDevAgent implements DevAgent {
        private final Result canned;
        private List<AcceptanceCriterion> criteriaSeen;

        private RecordingDevAgent(Result canned) {
            this.canned = canned;
        }

        static RecordingDevAgent succeeding() {
            return new RecordingDevAgent(
                    new Result(true, "built", "feat/x", 0.42, 0, 0, null));
        }

        static RecordingDevAgent failing(String failure) {
            return new RecordingDevAgent(Result.failed(failure));
        }

        @Override
        public Result implement(Story story, List<AcceptanceCriterion> criteria,
                                Agent agent, int attempt, String previousFailure) {
            this.criteriaSeen = criteria;
            return canned;
        }

        @Override
        public String describe() {
            return "recording dev agent";
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
        return build(new ScriptedDevAgent());
    }

    private AgentBuildActivities build(DevAgent dev) {
        return new AgentBuildActivities(
                new ReviewerAgent(model, 4096), new QaAgent(model, 4096), dev);
    }

    /** Build activities whose QA agent returns one exact reply, for the routing tests. */
    private static AgentBuildActivities buildWithQaScript(String qaReply) {
        Map<String, Function<ChatModel.ChatRequest, String>> script = new LinkedHashMap<>();
        script.put(QaAgent.NAME, request -> qaReply);
        ChatModel scripted = new ScriptedChatModel(script);

        return new AgentBuildActivities(
                new ReviewerAgent(scripted, 4096), new QaAgent(scripted, 4096),
                new ScriptedDevAgent());
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

            Question question = planning()
                    .raiseQuestion("LOOP-14-Q7", EPIC, PmAgent.NAME, gap);

            assertThat(question.id())
                    .as("the id must be the one the workflow generated. It used to come "
                            + "from a counter on this activity instance, which reset on "
                            + "every worker restart, and since answers are keyed by id a "
                            + "reused id let a reply to one question release a different one")
                    .isEqualTo("LOOP-14-Q7");

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

        private static final List<AcceptanceCriterion> CRITERIA = List.of(
                new AcceptanceCriterion("AC-1", "Rejects past the ceiling.",
                        Testability.AUTOMATED, List.of()),
                new AcceptanceCriterion("AC-2", "Change stays in scope.",
                        Testability.OBSERVABLE, List.of()));

        @Test
        @DisplayName("review goes through the reviewer agent")
        void reviewGoesThroughTheAgent() {
            var review = build().review(STORY, CRITERIA, Agent.SENIOR, 1);

            assertThat(model.agentsCalled).containsExactly(ReviewerAgent.NAME);
            assertThat(review.verdict()).isNotBlank();
        }

        @Test
        @DisplayName("QA reports UNVERIFIABLE while nothing runs the software — it does not report green")
        void qaDoesNotReportGreenOnNothing() {
            QaResult result = build().runQa(STORY, CRITERIA, 1);

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
        @DisplayName("the dev agent is handed the same criteria the reviewer and QA judge against")
        void devAgentGetsTheCriteria() {
            RecordingDevAgent dev = RecordingDevAgent.succeeding();

            build(dev).implement(STORY, CRITERIA, Agent.SENIOR, 1, null);

            assertThat(dev.criteriaSeen)
                    .as("it used to be handed an empty list and built from the story title "
                            + "alone, while the reviewer and QA marked the work against the "
                            + "real criteria — the agent was being graded on a brief it "
                            + "never saw")
                    .isEqualTo(CRITERIA);
        }

        @Test
        @DisplayName("a dev failure comes back as a result, not an exception")
        void devFailureIsReturnedNotThrown() {
            var result = build(RecordingDevAgent.failing("the harness exited 1"))
                    .implement(STORY, CRITERIA, Agent.SENIOR, 1, null);

            assertThat(result.succeeded()).isFalse();
            assertThat(result.retryable())
                    .as("an ordinary failure belongs in the rework loop. Thrown, it went "
                            + "past the escalation ladder and took the sibling stories with it")
                    .isTrue();
            assertThat(result.failure()).contains("exited 1");
        }

        @Test
        @DisplayName("a broken harness is not retryable, because no stronger model fixes it")
        void brokenHarnessIsNotRetryable() {
            var result = build(RecordingDevAgent.failing("the workspace does not exist"))
                    .implement(STORY, CRITERIA, Agent.MID, 1, null);

            assertThat(result.succeeded()).isFalse();
            assertThat(result.retryable()).isFalse();
        }

        @Test
        @DisplayName("an unwired CI reports that it measured nothing, rather than a pass")
        void unwiredCiDoesNotReportAPass() {
            CiResult ci = build().runCi(STORY, 1);

            assertThat(ci.measured()).isFalse();
            assertThat(ci.passed())
                    .as("it used to return passed=true with zero coverage and zero mutation")
                    .isFalse();
            assertThat(ci.trustworthy()).isFalse();
        }

        @Test
        @DisplayName("auto-approval refuses a CI result that measured nothing")
        void unmeasuredCiCannotAutoApprove() {
            Story tierOne = new Story("LOOP-14-1", "Tenant config lookup", 1, List.of());
            ReviewResult clean = new ReviewResult(true, false, "matches the criteria");

            assertThat(build().mayAutoApprove(tierOne, clean, CiResult.notRun("not wired")))
                    .as("merging with nobody watching on the strength of a build that never "
                            + "ran is the worst failure this pipeline can have")
                    .isFalse();
        }

        @Test
        @DisplayName("auto-approval refuses a pass whose mutation score came back as zero")
        void zeroMutationCannotAutoApprove() {
            Story tierOne = new Story("LOOP-14-1", "Tenant config lookup", 1, List.of());
            ReviewResult clean = new ReviewResult(true, false, "matches the criteria");
            CiResult suspicious = new CiResult(true, true, 0.95, 0.0, "reported a pass");

            assertThat(build().mayAutoApprove(tierOne, clean, suspicious))
                    .as("a zero on the gate an agent cannot game means the reporting is "
                            + "broken, not that the code is perfect")
                    .isFalse();
        }

        @Test
        @DisplayName("auto-approval allows a tier 1 story whose CI really did measure a pass")
        void measuredPassAutoApprovesTierOne() {
            Story tierOne = new Story("LOOP-14-1", "Tenant config lookup", 1, List.of());
            ReviewResult clean = new ReviewResult(true, false, "matches the criteria");
            CiResult measured = new CiResult(true, true, 0.92, 0.74, "coverage 92%, mutation 74%");

            assertThat(build().mayAutoApprove(tierOne, clean, measured))
                    .as("the guards must not have closed the auto-approval path altogether")
                    .isTrue();
        }

        @Test
        @DisplayName("the evidence QA reports belongs to the outcome that routed the story")
        void evidenceMatchesTheRoute() {
            // AC-1 could not be checked. AC-2 is the defect. The route is CODE_DEFECT, and
            // the evidence used to be AC-1's, in criterion order - so the dev agent was
            // retried with instructions to fix the thing QA could not check.
            QaResult result = buildWithQaScript("""
                    {"verdicts":[
                      {"criterionId":"AC-1","outcome":"UNVERIFIABLE",
                       "evidence":"Nothing was captured for the wording."},
                      {"criterionId":"AC-2","outcome":"CODE_DEFECT",
                       "evidence":"429 returned with an empty body."}
                    ],"concerns":[]}
                    """).runQa(STORY, CRITERIA, 1);

            assertThat(result.outcome()).isEqualTo(QaOutcome.CODE_DEFECT);
            assertThat(result.evidence())
                    .as("this string is handed to the dev agent as the thing to correct")
                    .isEqualTo("AC-2: 429 returned with an empty body.");
        }

        @Test
        @DisplayName("a story routed to the PM carries the concern, not an unrelated unverifiable note")
        void criterionWrongCarriesTheConcern() {
            QaResult result = buildWithQaScript("""
                    {"verdicts":[
                      {"criterionId":"AC-1","outcome":"UNVERIFIABLE",
                       "evidence":"Nothing was captured for the wording."},
                      {"criterionId":"AC-2","outcome":"PASS",
                       "evidence":"The diff touches only the limiter."}
                    ],"concerns":[
                      {"outcome":"CRITERION_WRONG",
                       "evidence":"It fails open when Redis is down; no criterion covers that."}
                    ]}
                    """).runQa(STORY, CRITERIA, 1);

            assertThat(result.outcome()).isEqualTo(QaOutcome.CRITERION_WRONG);
            assertThat(result.evidence())
                    .as("concerns were only read when every verdict passed, so the PM got "
                            + "an unverifiable note instead of the gap")
                    .isEqualTo("It fails open when Redis is down; no criterion covers that.");
        }

        @Test
        @DisplayName("a pass with something unchecked names it, rather than claiming everything was satisfied")
        void passNamesWhatWasNotChecked() {
            QaResult result = buildWithQaScript("""
                    {"verdicts":[
                      {"criterionId":"AC-1","outcome":"PASS","evidence":"429 after 100."},
                      {"criterionId":"AC-2","outcome":"UNVERIFIABLE",
                       "evidence":"Nothing was captured for the wording."}
                    ],"concerns":[]}
                    """).runQa(STORY, CRITERIA, 1);

            assertThat(result.outcome()).isEqualTo(QaOutcome.PASS);
            assertThat(result.evidence())
                    .as("it is about to become a person's checklist item, so say which one")
                    .contains("AC-2");
        }

        @Test
        @DisplayName("a tier 2 story never auto-approves, whatever the reviewer says")
        void tierTwoNeverAutoApproves() {
            var review = build().review(STORY, CRITERIA, Agent.SENIOR, 1);
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
