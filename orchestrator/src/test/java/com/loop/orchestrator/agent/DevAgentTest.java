package com.loop.orchestrator.agent;

import com.loop.orchestrator.domain.Domain.AcceptanceCriterion;
import com.loop.orchestrator.domain.Domain.Agent;
import com.loop.orchestrator.domain.Domain.Story;
import com.loop.orchestrator.domain.Domain.Testability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DevAgentTest {

    private static final Story STORY =
            new Story("LOOP-14-3", "Redis-backed limiter", 2, List.of());

    private static final List<AcceptanceCriterion> CRITERIA = List.of(
            new AcceptanceCriterion("AC-1", "The 101st request returns 429.",
                    Testability.AUTOMATED, List.of()),
            new AcceptanceCriterion("AC-2", "The body states usage and reset time.",
                    Testability.AUTOMATED, List.of()));

    @Nested
    @DisplayName("the task handed to the harness")
    class Task {

        @Test
        @DisplayName("carries the acceptance criteria, which are what done means")
        void carriesCriteria() {
            String task = DevAgent.taskFor(STORY, CRITERIA, 1, null);

            assertThat(task).contains("LOOP-14-3", "Redis-backed limiter");
            assertThat(task).contains("AC-1", "AC-2", "429");
        }

        @Test
        @DisplayName("names the specific previous failure, so attempt two is a correction not a retry")
        void namesThePreviousFailure() {
            String task = DevAgent.taskFor(STORY, CRITERIA, 2,
                    "429 returned with content-length: 0");

            assertThat(task).contains("attempt 2", "content-length: 0");
            assertThat(task)
                    .as("an agent told only to try again produces the same thing more "
                            + "confidently")
                    .contains("Fix that specifically");
        }

        @Test
        @DisplayName("says nothing about a previous attempt on the first one")
        void firstAttemptHasNoFailure() {
            assertThat(DevAgent.taskFor(STORY, CRITERIA, 1, null))
                    .doesNotContain("previous attempt");
        }

        @Test
        @DisplayName("warns about the two things that get a pull request bounced")
        void warnsAboutTheGates() {
            String task = DevAgent.taskFor(STORY, CRITERIA, 1, null);

            assertThat(task)
                    .as("a test that asserts nothing passes coverage and fails mutation")
                    .contains("mutation gate");
            assertThat(task)
                    .as("scope creep blocks auto-approval even when the code is correct")
                    .contains("out of scope");
        }
    }

    @Nested
    @DisplayName("the scripted dev agent")
    class Scripted {

        private final ScriptedDevAgent agent = new ScriptedDevAgent();

        @Test
        @DisplayName("writes no code but lets everything downstream run")
        void succeedsWithoutWritingCode() {
            DevAgent.Result result =
                    agent.implement(STORY, CRITERIA, Agent.SENIOR, 1, null);

            assertThat(result.succeeded())
                    .as("failing here would stop CI, review, QA and the gates being exercised")
                    .isTrue();
            assertThat(result.summary()).contains("no code written");
        }

        @Test
        @DisplayName("reports a plausible cost, so the workflow's arithmetic has something to sum")
        void reportsCost() {
            double senior = agent.implement(STORY, CRITERIA, Agent.SENIOR, 1, null).costUsd();
            double mid = agent.implement(STORY, CRITERIA, Agent.MID, 1, null).costUsd();

            assertThat(senior).isGreaterThan(mid);
            assertThat(mid).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("says outright that it is scripted, in the description and the branch name")
        void isObviouslyScripted() {
            assertThat(agent.describe()).contains("writes no code");
            assertThat(agent.implement(STORY, CRITERIA, Agent.MID, 1, null).branch())
                    .contains("scripted");
        }
    }

    @Nested
    @DisplayName("the OpenHands dispatch")
    class OpenHands {

        @TempDir
        Path workspace;

        @Test
        @DisplayName("refuses to run when the workspace does not exist, rather than editing the wrong directory")
        void missingWorkspaceIsRefused() {
            OpenHandsDevAgent agent = new OpenHandsDevAgent(
                    "openhands", workspace.resolve("nope"), Duration.ofMinutes(1));

            DevAgent.Result result = agent.implement(STORY, CRITERIA, Agent.MID, 1, null);

            assertThat(result.succeeded()).isFalse();
            assertThat(result.failure()).contains("does not exist", "dev.workspace");
        }

        @Test
        @DisplayName("a missing executable fails with something a person can act on")
        void missingExecutableIsClear() {
            OpenHandsDevAgent agent = new OpenHandsDevAgent(
                    "openhands-does-not-exist", workspace, Duration.ofMinutes(1));

            DevAgent.Result result = agent.implement(STORY, CRITERIA, Agent.MID, 1, null);

            assertThat(result.succeeded()).isFalse();
            assertThat(result.failure()).contains("could not start", "on PATH");
        }

        @Test
        @DisplayName("reads token usage out of the event stream")
        void readsUsage() {
            OpenHandsDevAgent.Usage usage = OpenHandsDevAgent.readUsage(List.of(
                    "starting up",
                    "{\"action\":\"edit\",\"file\":\"TokenBucket.java\"}",
                    "{\"usage\":{\"prompt_tokens\":15000,\"completion_tokens\":2400},"
                            + "\"accumulated_cost\":0.42}"));

            assertThat(usage.inputTokens()).isEqualTo(15000);
            assertThat(usage.outputTokens()).isEqualTo(2400);
            assertThat(usage.costUsd()).isEqualTo(0.42);
        }

        @Test
        @DisplayName("a stream with no usage reports zero, having said so — zero is not the same as free")
        void missingUsageIsZeroNotInvented() {
            OpenHandsDevAgent.Usage usage = OpenHandsDevAgent.readUsage(List.of(
                    "just some log output", "{\"action\":\"read\"}"));

            assertThat(usage.inputTokens()).isZero();
            assertThat(usage.costUsd()).isZero();
        }

        @Test
        @DisplayName("non-JSON lines in the stream do not break the parse")
        void tolerantOfLogLines() {
            OpenHandsDevAgent.Usage usage = OpenHandsDevAgent.readUsage(List.of(
                    "WARN something", "{ not valid json",
                    "{\"prompt_tokens\":100,\"completion_tokens\":20}"));

            assertThat(usage.inputTokens()).isEqualTo(100);
        }

        @Test
        @DisplayName("says in its own description that it has never actually run")
        void admitsItIsUnverified() {
            assertThat(new OpenHandsDevAgent("openhands", workspace, Duration.ofMinutes(20))
                    .describe())
                    .as("a component nobody has executed should not look like one that works")
                    .contains("UNVERIFIED");
        }
    }
}
