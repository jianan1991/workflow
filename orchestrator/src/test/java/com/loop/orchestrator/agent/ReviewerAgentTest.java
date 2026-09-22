package com.loop.orchestrator.agent;

import com.loop.orchestrator.domain.Domain.AcceptanceCriterion;
import com.loop.orchestrator.domain.Domain.Agent;
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

class ReviewerAgentTest {

    private static final Story STORY =
            new Story("LOOP-14-3", "Redis-backed limiter", 2, List.of());

    private static final List<AcceptanceCriterion> CRITERIA = List.of(
            new AcceptanceCriterion("AC-1", "101st request returns 429.",
                    Testability.AUTOMATED, List.of()),
            new AcceptanceCriterion("AC-2", "The 429 body states usage.",
                    Testability.AUTOMATED, List.of()));

    private static final List<String> FILES =
            List.of("TokenBucket.java", "RateLimitFilter.java", "application.yml");

    private static final String DIFF = "+ public boolean tryConsume(String tenantId) { ... }";

    private ReviewerAgent agentReplying(String reply) {
        return new ReviewerAgent(ScriptedChatModel.of(Map.of(ReviewerAgent.NAME, reply)), 4096);
    }

    @Test
    @DisplayName("a clean review passes both checks")
    void cleanReview() {
        String reply = """
                {"matchesCriteria":true,"scopeExceeded":false,
                 "verdict":"Matches both criteria. Watch the refill maths under concurrency.",
                 "unmetCriteria":[],"outOfScopeFiles":[]}
                """;

        ReviewerAgent.Result result =
                agentReplying(reply).review(STORY, CRITERIA, Agent.SENIOR, DIFF, FILES);

        assertThat(result.review().matchesCriteria()).isTrue();
        assertThat(result.review().scopeExceeded()).isFalse();
        assertThat(result.review().verdict()).contains("refill maths");
    }

    @Test
    @DisplayName("scope creep is reported even when the code is correct — that is the point of it")
    void correctButOutOfScope() {
        String reply = """
                {"matchesCriteria":true,"scopeExceeded":true,
                 "verdict":"Correct, but it edits application.yml, which the story did not authorise.",
                 "unmetCriteria":[],"outOfScopeFiles":["application.yml"]}
                """;

        ReviewerAgent.Result result =
                agentReplying(reply).review(STORY, CRITERIA, Agent.SENIOR, DIFF, FILES);

        assertThat(result.review().matchesCriteria()).isTrue();
        assertThat(result.review().scopeExceeded())
                .as("a change can be both correct and unauthorised; saying so blocks auto-merge")
                .isTrue();
        assertThat(result.outOfScopeFiles()).containsExactly("application.yml");
    }

    @Nested
    @DisplayName("a reply that contradicts itself is rejected")
    class SelfContradiction {

        @Test
        @DisplayName("claims the criteria are met while listing unmet ones")
        void matchesButListsUnmet() {
            String reply = """
                    {"matchesCriteria":true,"scopeExceeded":false,
                     "verdict":"Looks fine.","unmetCriteria":["AC-2"],"outOfScopeFiles":[]}
                    """;

            assertThatThrownBy(() ->
                    agentReplying(reply).review(STORY, CRITERIA, Agent.SENIOR, DIFF, FILES))
                    .as("taking the optimistic half of a contradiction would merge it")
                    .isInstanceOf(AgentReplyException.class)
                    .hasMessageContaining("AC-2");
        }

        @Test
        @DisplayName("claims scope was respected while listing out-of-scope files")
        void inScopeButListsFiles() {
            String reply = """
                    {"matchesCriteria":true,"scopeExceeded":false,
                     "verdict":"Fine.","unmetCriteria":[],"outOfScopeFiles":["application.yml"]}
                    """;

            assertThatThrownBy(() ->
                    agentReplying(reply).review(STORY, CRITERIA, Agent.SENIOR, DIFF, FILES))
                    .isInstanceOf(AgentReplyException.class)
                    .hasMessageContaining("application.yml");
        }
    }

    @Nested
    @DisplayName("an unusable reply fails loudly")
    class BadReplies {

        @Test
        @DisplayName("a missing verdict is rejected — a person must be told what to look at")
        void missingVerdict() {
            String reply = """
                    {"matchesCriteria":true,"scopeExceeded":false,"verdict":"  "}
                    """;

            assertThatThrownBy(() ->
                    agentReplying(reply).review(STORY, CRITERIA, Agent.SENIOR, DIFF, FILES))
                    .isInstanceOf(AgentReplyException.class)
                    .hasMessageContaining("no verdict");
        }

        @Test
        @DisplayName("a missing matchesCriteria is rejected, never read as false or true")
        void missingMatches() {
            String reply = """
                    {"scopeExceeded":false,"verdict":"Looks fine."}
                    """;

            assertThatThrownBy(() ->
                    agentReplying(reply).review(STORY, CRITERIA, Agent.SENIOR, DIFF, FILES))
                    .as("a boolean quietly defaulting would clear a diff nobody checked")
                    .isInstanceOf(AgentReplyException.class)
                    .hasMessageContaining("matchesCriteria");
        }

        @Test
        @DisplayName("a non-boolean flag is rejected")
        void nonBooleanFlag() {
            String reply = """
                    {"matchesCriteria":"mostly","scopeExceeded":false,"verdict":"Fine."}
                    """;

            assertThatThrownBy(() ->
                    agentReplying(reply).review(STORY, CRITERIA, Agent.SENIOR, DIFF, FILES))
                    .isInstanceOf(AgentReplyException.class)
                    .hasMessageContaining("mostly");
        }
    }

    @Nested
    @DisplayName("the prompt")
    class Prompt {

        @Test
        @DisplayName("carries the criteria, the changed files and the diff")
        void carriesEverythingNeeded() {
            String prompt = agentReplying("{}")
                    .buildPrompt(STORY, CRITERIA, Agent.SENIOR, DIFF, FILES);

            assertThat(prompt).contains("AC-1", "AC-2", "application.yml", "tryConsume");
        }

        @Test
        @DisplayName("names the authoring agent, so scope can be judged against who was asked")
        void namesTheAuthor() {
            String prompt = agentReplying("{}")
                    .buildPrompt(STORY, CRITERIA, Agent.MID, DIFF, FILES);

            assertThat(prompt).contains("MID");
        }

        @Test
        @DisplayName("treats no criteria as a failure to match, not as nothing to check")
        void noCriteriaIsAFailure() {
            String prompt = agentReplying("{}")
                    .buildPrompt(STORY, List.of(), Agent.SENIOR, DIFF, FILES);

            assertThat(prompt).contains("Treat that as a failure to match");
        }
    }
}
