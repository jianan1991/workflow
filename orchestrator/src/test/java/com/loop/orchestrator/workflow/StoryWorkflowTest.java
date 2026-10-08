package com.loop.orchestrator.workflow;

import com.loop.orchestrator.activity.StubBuildActivities;
import com.loop.orchestrator.domain.Domain.AcceptanceCriterion;
import com.loop.orchestrator.domain.Domain.Agent;
import com.loop.orchestrator.domain.Domain.Gate;
import com.loop.orchestrator.domain.Domain.GateDecision;
import com.loop.orchestrator.domain.Domain.Story;
import com.loop.orchestrator.domain.Domain.StoryState;
import com.loop.orchestrator.domain.Domain.StoryStatus;
import com.loop.orchestrator.domain.Domain.Testability;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class StoryWorkflowTest {

    private static final String EPIC = "LOOP-14";

    /** Every story needs criteria now — nothing downstream can judge one without them. */
    private static final List<AcceptanceCriterion> CRITERIA = List.of(
            new AcceptanceCriterion("AC-1", "The limiter rejects past the ceiling.",
                    Testability.AUTOMATED, List.of()));

    private TestWorkflowEnvironment env;

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    @Test
    @DisplayName("a tier 1 story merges without a person ever seeing it")
    void tierOneAutoApproves() {
        Story story = new Story("LOOP-14-1", "Tenant config lookup", 1, List.of());

        StoryStatus result = runToCompletion(story, true);

        assertThat(result.state()).isEqualTo(StoryState.MERGED);
        assertThat(result.autoApproved()).isTrue();
        assertThat(result.agent()).isEqualTo(Agent.MID);
        assertThat(result.reworkLoops()).isZero();
    }

    @Test
    @DisplayName("a QA code defect goes back to the agent, not to a person, and the loop closes")
    void codeDefectReworksWithoutAHuman() {
        Story story = new Story("LOOP-14-3", "Redis-backed limiter", 2, List.of());

        StoryWorkflow workflow = start(story, false);
        WorkflowStub stub = WorkflowStub.fromTyped(workflow);

        // Tier 2 never auto-approves — the reviewer flags scope — so it stops at gate 3.
        // Getting that far at all means the earlier failures were absorbed by the loop.
        StoryStatus atGate = await(workflow, s -> s.state() == StoryState.AWAITING_PR_REVIEW);

        assertThat(atGate.reworkLoops())
                .as("QA sent it back to the dev agent and no person was involved")
                .isGreaterThanOrEqualTo(1);
        assertThat(atGate.devAttempts()).isGreaterThan(1);
        assertThat(atGate.qaRuns()).isGreaterThanOrEqualTo(1);

        workflow.decidePullRequest(new GateDecision(Gate.PULL_REQUEST, true, "looks right"));

        StoryStatus merged = stub.getResult(StoryStatus.class);
        assertThat(merged.state()).isEqualTo(StoryState.MERGED);
        assertThat(merged.autoApproved())
                .as("tier 2 touching shared config must never auto-approve")
                .isFalse();
        assertThat(merged.costUsd()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("a wrong criterion goes back to the PM, because retrying the agent would waste the money twice")
    void wrongCriterionRoutesToThePm() {
        Story story = new Story("LOOP-14-4",
                StubBuildActivities.CRITERION_GAP_MARKER + " Key on tenant identity",
                2, List.of());

        StoryStatus result = runToCompletion(story, true);

        assertThat(result.state()).isEqualTo(StoryState.RETURNED_TO_PM);
        assertThat(result.note()).contains("criterion was wrong");
        assertThat(result.reworkLoops())
                .as("the code was never the problem, so it must not have been rebuilt")
                .isZero();
    }

    @Test
    @DisplayName("nobody reviewing the pull request blocks the story instead of holding it open")
    void unreviewedPullRequestBlocks() {
        Story story = new Story("LOOP-14-3", "Redis-backed limiter", 2, List.of());

        StoryStatus result = runToCompletion(story, true);

        assertThat(result.state()).isEqualTo(StoryState.BLOCKED);
        assertThat(result.note()).contains("nobody reviewed");
    }

    @Test
    @DisplayName("story cost sums what every activity spent, not just QA")
    void costSumsEveryActivity() {
        Story story = new Story("LOOP-14-1", "Tenant config lookup", 1, List.of());

        StoryStatus result = runToCompletion(story, true);

        // dev 0.42 + reviewer 0.11 + qa 0.36 on a clean tier 1 run.
        assertThat(result.costUsd())
                .as("counting only QA under-reports a story by most of its cost, and the dev "
                        + "agent is the largest line on the bill")
                .isGreaterThan(0.36);
    }

    @Test
    @DisplayName("sub-cent spend is reported, not rounded away into looking like zero")
    void subCentSpendSurvives() {
        Story story = new Story("LOOP-14-1", "Tenant config lookup", 1, List.of());

        StoryStatus result = runToCompletion(story, true);

        assertThat(result.costUsd() * 10_000)
                .as("rounding real spend to currency reads exactly like cost tracking that "
                        + "was never wired")
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("a story with no criteria is blocked rather than built")
    void noCriteriaBlocks() {
        Story story = new Story("LOOP-14-9", "Something nobody specified", 1, List.of());

        StoryStatus result = runToCompletion(story, List.of(), true);

        assertThat(result.state())
                .as("building it would pass review against nothing and reach a person "
                        + "looking finished")
                .isEqualTo(StoryState.BLOCKED);
        assertThat(result.note()).contains("no acceptance criteria");
        assertThat(result.devAttempts())
                .as("no code should have been attempted at all")
                .isZero();
    }

    @Test
    @DisplayName("a dev agent that cannot run blocks the story instead of killing the workflow")
    void brokenDevAgentBlocksTheStory() {
        Story story = new Story("LOOP-14-8",
                StubBuildActivities.DEV_BROKEN_MARKER + " Redis-backed limiter", 2, List.of());

        StoryStatus result = runToCompletion(story, true);

        assertThat(result.state())
                .as("the failure used to be thrown, which escaped the escalation ladder "
                        + "entirely and failed the parent epic along with every sibling story")
                .isEqualTo(StoryState.BLOCKED);
        assertThat(result.note()).contains("the dev agent could not run");
        assertThat(result.devAttempts())
                .as("no stronger model fixes a missing workspace, so spending four more "
                        + "rounds to discover that is waste")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an ordinary dev failure goes through the rework loop and the story still lands")
    void retryableDevFailureReworks() {
        Story story = new Story("LOOP-14-7",
                StubBuildActivities.DEV_FAILS_ONCE_MARKER + " Tenant config lookup", 1, List.of());

        StoryStatus result = runToCompletion(story, true);

        assertThat(result.state()).isEqualTo(StoryState.MERGED);
        assertThat(result.devAttempts())
                .as("the first attempt failed and the second was told why")
                .isEqualTo(2);
        assertThat(result.agent())
                .as("one failure does not reassign the story — that takes two")
                .isEqualTo(Agent.MID);
        assertThat(result.costUsd())
                .as("the failed attempt still spent tokens, and they are still charged")
                .isGreaterThan(0.42);
    }

    @Test
    @DisplayName("a story whose CI measured nothing goes to a person, not round the loop")
    void unmeasuredCiRoutesToAPerson() {
        Story story = new Story("LOOP-14-6",
                StubBuildActivities.CI_UNMEASURED_MARKER + " Tenant config lookup", 1, List.of());

        StoryWorkflow workflow = start(story, false);
        StoryStatus waiting = await(workflow, s -> s.state() == StoryState.AWAITING_PR_REVIEW);

        assertThat(waiting.devAttempts())
                .as("no amount of rework measures a build that does not run, so paying for "
                        + "six attempts to find that out is pure waste")
                .isEqualTo(1);
        assertThat(waiting.autoApproved())
                .as("an unmeasured result is not a lenient pass")
                .isFalse();

        workflow.decidePullRequest(
                new GateDecision(Gate.PULL_REQUEST, true, "read the diff by hand"));

        StoryStatus merged = WorkflowStub.fromTyped(workflow).getResult(StoryStatus.class);
        assertThat(merged.state()).isEqualTo(StoryState.MERGED);
        assertThat(merged.autoApproved()).isFalse();
    }

    @Test
    @DisplayName("a tier 1 story with no pull request waits for a person instead of auto-merging")
    void noPullRequestMeansNoAutoApproval() {
        Story story = new Story("LOOP-14-5",
                StubBuildActivities.PR_FAILS_MARKER + " Tenant config lookup", 1, List.of());

        StoryWorkflow workflow = start(story, false);
        StoryStatus waiting = await(workflow, s -> s.state() == StoryState.AWAITING_PR_REVIEW);

        assertThat(waiting.autoApproved())
                .as("every check passed, so this would have auto-merged — but recording a "
                        + "story as MERGED when no pull request exists is the exact failure "
                        + "this chain was built to end")
                .isFalse();
        assertThat(waiting.pullRequestUrl()).isEmpty();
        assertThat(waiting.note())
                .as("a person is told there is no pull request to read, rather than left "
                        + "to wonder where it is")
                .contains("no pull request to read", "no commits between");

        workflow.decidePullRequest(
                new GateDecision(Gate.PULL_REQUEST, true, "read the branch by hand"));

        StoryStatus merged = WorkflowStub.fromTyped(workflow).getResult(StoryStatus.class);
        assertThat(merged.state()).isEqualTo(StoryState.MERGED);
        assertThat(merged.autoApproved()).isFalse();
    }

    @Test
    @DisplayName("a merged story carries the pull request a person decided on")
    void mergedStoryCarriesItsPullRequest() {
        Story story = new Story("LOOP-14-1", "Tenant config lookup", 1, List.of());

        StoryStatus result = runToCompletion(story, true);

        assertThat(result.state()).isEqualTo(StoryState.MERGED);
        assertThat(result.pullRequestUrl())
                .as("the branch used to be returned by the implement activity and dropped "
                        + "on the next line; nothing downstream could point at the change")
                .contains("/pull/");
    }

    // ------------------------------------------------------------------ setup

    private StoryWorkflow start(Story story, boolean useTimeskipping) {
        return start(story, CRITERIA, useTimeskipping);
    }

    private StoryWorkflow start(Story story, List<AcceptanceCriterion> criteria,
                                boolean useTimeskipping) {
        env = TestWorkflowEnvironment.newInstance(
                TestEnvironmentOptions.newBuilder()
                        .setUseTimeskipping(useTimeskipping)
                        .build());

        Worker worker = env.newWorker(EpicWorkflow.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(StoryWorkflowImpl.class);
        worker.registerActivitiesImplementations(new StubBuildActivities());
        env.start();

        WorkflowClient client = env.getWorkflowClient();
        StoryWorkflow workflow = client.newWorkflowStub(
                StoryWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(EpicWorkflow.TASK_QUEUE)
                        .setWorkflowId(StoryWorkflow.workflowId(EPIC, story.id()))
                        .setWorkflowExecutionTimeout(Duration.ofDays(30))
                        .build());

        WorkflowClient.start(workflow::run, EPIC, story, criteria);
        return workflow;
    }

    private StoryStatus runToCompletion(Story story, boolean useTimeskipping) {
        return runToCompletion(story, CRITERIA, useTimeskipping);
    }

    private StoryStatus runToCompletion(Story story, List<AcceptanceCriterion> criteria,
                                        boolean useTimeskipping) {
        StoryWorkflow workflow = start(story, criteria, useTimeskipping);
        return WorkflowStub.fromTyped(workflow).getResult(StoryStatus.class);
    }

    private StoryStatus await(StoryWorkflow workflow, Predicate<StoryStatus> until) {
        for (int attempt = 0; attempt < 400; attempt++) {
            StoryStatus status = workflow.status();
            if (until.test(status)) {
                return status;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("story never reached the expected state");
    }
}
