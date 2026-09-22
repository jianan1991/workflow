package com.loop.orchestrator.agent;

import com.loop.orchestrator.domain.Domain.AcceptanceCriterion;
import com.loop.orchestrator.domain.Domain.CriterionVerdict;
import com.loop.orchestrator.domain.Domain.Observation;
import com.loop.orchestrator.domain.Domain.QaOutcome;
import com.loop.orchestrator.domain.Domain.Story;
import com.loop.orchestrator.domain.Domain.Testability;
import com.loop.orchestrator.llm.ScriptedChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QaAgentTest {

    private static final Story STORY =
            new Story("LOOP-14-3", "Redis-backed limiter", 2, List.of());

    private static final List<AcceptanceCriterion> CRITERIA = List.of(
            new AcceptanceCriterion("AC-1", "101st request returns 429.",
                    Testability.AUTOMATED, List.of()),
            new AcceptanceCriterion("AC-2", "The 429 body states usage and reset time.",
                    Testability.AUTOMATED, List.of()),
            new AcceptanceCriterion("AC-3", "The message is clear enough to act on.",
                    Testability.HUMAN_ONLY, List.of()));

    private static final List<Observation> OBSERVATIONS = List.of(
            new Observation("100 requests in 60s, then one more",
                    "HTTP/1.1 429 Too Many Requests\nRetry-After: 23\ncontent-length: 0"));

    private QaAgent agentReplying(String reply) {
        return new QaAgent(ScriptedChatModel.of(Map.of(QaAgent.NAME, reply)), 4096);
    }

    private static final String GOOD_REPLY = """
            {"verdicts":[
              {"criterionId":"AC-1","outcome":"PASS","evidence":"429 on request 101."},
              {"criterionId":"AC-2","outcome":"CODE_DEFECT","evidence":"content-length: 0."},
              {"criterionId":"AC-3","outcome":"UNVERIFIABLE","evidence":"Body captured."}
            ],"concerns":[]}
            """;

    @Test
    @DisplayName("judges every criterion and keeps the evidence behind each verdict")
    void judgesEveryCriterion() {
        QaAgent.Result result = agentReplying(GOOD_REPLY).judge(STORY, CRITERIA, OBSERVATIONS);

        assertThat(result.verdicts()).hasSize(3);
        assertThat(result.verdicts()).extracting(CriterionVerdict::outcome)
                .containsExactly(QaOutcome.PASS, QaOutcome.CODE_DEFECT, QaOutcome.UNVERIFIABLE);
        assertThat(result.verdicts().get(1).evidence()).contains("content-length: 0");
    }

    @Nested
    @DisplayName("routing — the whole design in one decision")
    class Routing {

        @Test
        @DisplayName("a code defect goes back to the dev agent, with no person involved")
        void codeDefectRoutesToDev() {
            assertThat(agentReplying(GOOD_REPLY).judge(STORY, CRITERIA, OBSERVATIONS).route())
                    .isEqualTo(QaOutcome.CODE_DEFECT);
        }

        @Test
        @DisplayName("a wrong criterion outranks a code defect, because rebuilding against it is waste")
        void criterionWrongOutranksCodeDefect() {
            String reply = """
                    {"verdicts":[
                      {"criterionId":"AC-1","outcome":"PASS","evidence":"429 seen."},
                      {"criterionId":"AC-2","outcome":"CODE_DEFECT","evidence":"Empty body."},
                      {"criterionId":"AC-3","outcome":"UNVERIFIABLE","evidence":"Captured."}
                    ],"concerns":[
                      {"outcome":"CRITERION_WRONG","evidence":"Fails open with Redis down."}
                    ]}
                    """;

            assertThat(agentReplying(reply).judge(STORY, CRITERIA, OBSERVATIONS).route())
                    .as("fixing the code against a requirement nobody wanted changes nothing")
                    .isEqualTo(QaOutcome.CRITERION_WRONG);
        }

        @Test
        @DisplayName("unverifiable items do not fail the story — they become a checklist")
        void unverifiableDoesNotFail() {
            String reply = """
                    {"verdicts":[
                      {"criterionId":"AC-1","outcome":"PASS","evidence":"429 seen."},
                      {"criterionId":"AC-2","outcome":"PASS","evidence":"Body has usage."},
                      {"criterionId":"AC-3","outcome":"UNVERIFIABLE","evidence":"Captured."}
                    ],"concerns":[]}
                    """;

            QaAgent.Result result = agentReplying(reply).judge(STORY, CRITERIA, OBSERVATIONS);

            assertThat(result.route()).isEqualTo(QaOutcome.PASS);
            assertThat(result.unverifiable()).isEqualTo(1);
        }

        @Test
        @DisplayName("a concern with no criterion behind it still routes to the PM")
        void concernWithoutCriterionRoutes() {
            String reply = """
                    {"verdicts":[
                      {"criterionId":"AC-1","outcome":"PASS","evidence":"429 seen."},
                      {"criterionId":"AC-2","outcome":"PASS","evidence":"Body has usage."},
                      {"criterionId":"AC-3","outcome":"PASS","evidence":"Reads clearly."}
                    ],"concerns":[
                      {"outcome":"CRITERION_WRONG","evidence":"Fails open; nothing covers it."}
                    ]}
                    """;

            QaAgent.Result result = agentReplying(reply).judge(STORY, CRITERIA, OBSERVATIONS);

            assertThat(result.route())
                    .as("what nobody thought to ask for is exactly what QA exists to surface")
                    .isEqualTo(QaOutcome.CRITERION_WRONG);
            assertThat(result.concerns()).singleElement()
                    .satisfies(concern -> assertThat(concern.criterionId()).isNull());
        }
    }

    @Nested
    @DisplayName("it cannot quietly skip anything")
    class Completeness {

        @Test
        @DisplayName("a criterion with no verdict is rejected, not treated as passing")
        void missingVerdictIsRejected() {
            String reply = """
                    {"verdicts":[
                      {"criterionId":"AC-1","outcome":"PASS","evidence":"429 seen."}
                    ],"concerns":[]}
                    """;

            assertThatThrownBy(() -> agentReplying(reply).judge(STORY, CRITERIA, OBSERVATIONS))
                    .as("an unchecked criterion would merge looking exactly like a passing one")
                    .isInstanceOf(AgentReplyException.class)
                    .hasMessageContaining("AC-2")
                    .hasMessageContaining("AC-3");
        }

        @Test
        @DisplayName("a verdict for a criterion that does not exist is rejected")
        void inventedCriterionIsRejected() {
            String reply = """
                    {"verdicts":[
                      {"criterionId":"AC-1","outcome":"PASS","evidence":"x"},
                      {"criterionId":"AC-2","outcome":"PASS","evidence":"x"},
                      {"criterionId":"AC-3","outcome":"PASS","evidence":"x"},
                      {"criterionId":"AC-9","outcome":"PASS","evidence":"x"}
                    ],"concerns":[]}
                    """;

            assertThatThrownBy(() -> agentReplying(reply).judge(STORY, CRITERIA, OBSERVATIONS))
                    .isInstanceOf(AgentReplyException.class)
                    .hasMessageContaining("AC-9");
        }

        @Test
        @DisplayName("two verdicts for the same criterion is rejected")
        void duplicateVerdictIsRejected() {
            String reply = """
                    {"verdicts":[
                      {"criterionId":"AC-1","outcome":"PASS","evidence":"x"},
                      {"criterionId":"AC-1","outcome":"CODE_DEFECT","evidence":"x"},
                      {"criterionId":"AC-2","outcome":"PASS","evidence":"x"},
                      {"criterionId":"AC-3","outcome":"PASS","evidence":"x"}
                    ],"concerns":[]}
                    """;

            assertThatThrownBy(() -> agentReplying(reply).judge(STORY, CRITERIA, OBSERVATIONS))
                    .isInstanceOf(AgentReplyException.class)
                    .hasMessageContaining("two verdicts");
        }

        @Test
        @DisplayName("judging with no criteria at all is refused outright")
        void noCriteriaIsRefused() {
            assertThatThrownBy(() ->
                    agentReplying(GOOD_REPLY).judge(STORY, List.of(), OBSERVATIONS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no acceptance criteria");
        }
    }

    @Nested
    @DisplayName("the prompt")
    class Prompt {

        @Test
        @DisplayName("carries the observations, which are the only thing it may judge from")
        void carriesObservations() {
            String prompt = agentReplying(GOOD_REPLY).buildPrompt(STORY, CRITERIA, OBSERVATIONS);

            assertThat(prompt).contains("429 Too Many Requests", "Retry-After: 23");
            assertThat(prompt).contains("AC-1", "AC-2", "AC-3");
        }

        @Test
        @DisplayName("states plainly when nothing was captured, so nothing is inferred from the criteria")
        void emptyObservationsAreStated() {
            String prompt = agentReplying(GOOD_REPLY).buildPrompt(STORY, CRITERIA, List.of());

            assertThat(prompt)
                    .as("a model given no observations will otherwise report passes for a run "
                            + "that never happened")
                    .contains("Nothing was captured", "Every criterion is UNVERIFIABLE");
        }

        @Test
        @DisplayName("never shows the developer agent's own tests")
        void neverShowsDeveloperTests() {
            String prompt = agentReplying(GOOD_REPLY).buildPrompt(STORY, CRITERIA, OBSERVATIONS);

            assertThat(prompt.toLowerCase())
                    .as("if the agent writes the tests and QA confirms they pass, the same "
                            + "author has graded its own exam")
                    .doesNotContain("@test", "junit", "src/test");
        }
    }

    @Test
    @DisplayName("an unreadable outcome is rejected — neither PASS nor a failure is safe to assume")
    void unreadableOutcomeIsRejected() {
        String reply = """
                {"verdicts":[
                  {"criterionId":"AC-1","outcome":"probably fine","evidence":"x"},
                  {"criterionId":"AC-2","outcome":"PASS","evidence":"x"},
                  {"criterionId":"AC-3","outcome":"PASS","evidence":"x"}
                ],"concerns":[]}
                """;

        assertThatThrownBy(() -> agentReplying(reply).judge(STORY, CRITERIA, OBSERVATIONS))
                .isInstanceOf(AgentReplyException.class)
                .hasMessageContaining("expected PASS, CODE_DEFECT");
    }
}
