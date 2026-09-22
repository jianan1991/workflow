package com.loop.orchestrator.workflow;

import com.loop.orchestrator.activity.StubBuildActivities;
import com.loop.orchestrator.activity.StubPlanningActivities;
import com.loop.orchestrator.domain.Domain.Answer;
import com.loop.orchestrator.domain.Domain.Epic;
import com.loop.orchestrator.domain.Domain.EpicStatus;
import com.loop.orchestrator.domain.Domain.Gate;
import com.loop.orchestrator.domain.Domain.GateDecision;
import com.loop.orchestrator.domain.Domain.Phase;
import com.loop.orchestrator.domain.Domain.Story;
import com.loop.orchestrator.domain.Domain.StoryState;
import com.loop.orchestrator.domain.Domain.StoryStatus;
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

class EpicWorkflowTest {

    private static final Epic EPIC =
            new Epic("LOOP-14", "Per-tenant API rate limiting",
                    "Developers on the Starter plan can exhaust the API.");

    private TestWorkflowEnvironment env;

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    @Test
    @DisplayName("blocks on a journey gap, survives the wait, and resumes when a person answers")
    void blocksThenResumes() {
        // Time skipping off: the point of this test is the wait itself, and a skipped
        // clock would fire the three-day timeout before the answer arrived.
        EpicWorkflow workflow = start(false);
        WorkflowStub stub = WorkflowStub.fromTyped(workflow);

        EpicStatus blocked = awaitStatus(workflow, s -> s.phase() == Phase.AWAITING_ANSWER);
        assertThat(blocked.openQuestion()).isNotNull();
        assertThat(blocked.openQuestion().searchesAttempted())
                .as("the searches already tried are what separate a real gap from a lazy agent")
                .isNotEmpty();
        assertThat(blocked.journey().holdsTogether()).isFalse();

        workflow.answerQuestion(new Answer(blocked.openQuestion().id(),
                "Self-serve, with approval above 250 req/min."));

        EpicStatus atGate = awaitStatus(workflow, s -> s.phase() == Phase.EPIC_REVIEW);
        assertThat(atGate.journey().holdsTogether())
                .as("the architect must not see an epic that still has gaps")
                .isTrue();
        assertThat(atGate.openQuestion()).isNull();
        assertThat(atGate.questionsAsked()).isEqualTo(1);
        assertThat(atGate.stories()).hasSize(6);

        assertThat(atGate.estimate().dollars())
                .as("a forecast has to exist before the gate, not after the build")
                .isGreaterThan(0.0);
        assertThat(atGate.estimate().humanMinutes()).isGreaterThan(0);

        workflow.decideGate(new GateDecision(Gate.EPIC_PLAN, true, "plan looks right"));

        // Tier 2 never auto-approves, so somebody has to clear gate 3. This thread is
        // standing in for that person.
        Thread reviewer = approveTierTwoPullRequestsAsTheyAppear();

        EpicStatus finished;
        try {
            finished = stub.getResult(EpicStatus.class);
        } finally {
            reviewer.interrupt();
        }

        assertThat(finished.phase()).isEqualTo(Phase.MERGED);
        assertThat(finished.storyStatuses()).hasSize(6);
        assertThat(finished.storyStatuses())
                .allSatisfy(s -> assertThat(s.state()).isEqualTo(StoryState.MERGED));
        assertThat(finished.storyStatuses().stream().mapToInt(StoryStatus::reworkLoops).sum())
                .as("tier 2 stories go round the loop once and no person sees it")
                .isGreaterThan(0);
        assertThat(finished.storyStatuses())
                .filteredOn(s -> s.tier() == 1)
                .allSatisfy(s -> assertThat(s.autoApproved()).isTrue());
    }

    @Test
    @DisplayName("stories run in dependency order, with independent ones together")
    void dependencyLayersRespectTheGraph() {
        List<Story> stories = List.of(
                new Story("S1", "extract the service", 1, List.of()),
                new Story("S2", "csv writer", 1, List.of("S1")),
                new Story("S3", "retention at query time", 2, List.of("S1")),
                new Story("S4", "wire the controller", 1, List.of("S2", "S3")));

        List<List<Story>> layers = EpicWorkflowImpl.dependencyLayers(stories);

        assertThat(layers).hasSize(3);
        assertThat(layers.get(0)).extracting(Story::id).containsExactly("S1");
        assertThat(layers.get(1)).extracting(Story::id)
                .as("nothing links S2 and S3, so they may run at the same time")
                .containsExactlyInAnyOrder("S2", "S3");
        assertThat(layers.get(2)).extracting(Story::id).containsExactly("S4");
    }

    @Test
    @DisplayName("an unsatisfiable dependency surfaces as a failure rather than a dropped story")
    void unsatisfiableDependenciesAreNotSilentlyDropped() {
        List<Story> stories = List.of(
                new Story("S1", "depends on something that was never decomposed", 1, List.of("GHOST")),
                new Story("S2", "circular", 1, List.of("S3")),
                new Story("S3", "circular", 1, List.of("S2")));

        List<List<Story>> layers = EpicWorkflowImpl.dependencyLayers(stories);

        assertThat(layers.stream().flatMap(List::stream))
                .extracting(Story::id)
                .containsExactlyInAnyOrder("S1", "S2", "S3");
    }

    @Test
    @DisplayName("abandons the epic when nobody answers, rather than holding it open forever")
    void abandonsWhenNobodyAnswers() {
        // Time skipping on: the three-day timer fires immediately.
        EpicWorkflow workflow = start(true);

        EpicStatus finished = WorkflowStub.fromTyped(workflow).getResult(EpicStatus.class);

        assertThat(finished.phase()).isEqualTo(Phase.ABANDONED);
        assertThat(finished.note()).contains("no answer");
        assertThat(finished.questionsAsked())
                .as("an unanswered question was never absorbed into the knowledge base")
                .isZero();
    }

    @Test
    @DisplayName("stops asking after the rejected gate rather than building anyway")
    void rejectedGateStopsTheEpic() {
        EpicWorkflow workflow = start(false);
        WorkflowStub stub = WorkflowStub.fromTyped(workflow);

        EpicStatus blocked = awaitStatus(workflow, s -> s.phase() == Phase.AWAITING_ANSWER);
        workflow.answerQuestion(new Answer(blocked.openQuestion().id(), "Self-serve."));

        awaitStatus(workflow, s -> s.phase() == Phase.EPIC_REVIEW);
        workflow.decideGate(new GateDecision(Gate.EPIC_PLAN, false, "split story 3 first"));

        EpicStatus finished = stub.getResult(EpicStatus.class);
        assertThat(finished.phase()).isEqualTo(Phase.ABANDONED);
        assertThat(finished.note()).contains("split story 3 first");
    }

    // ------------------------------------------------------------------ setup

    private EpicWorkflow start(boolean useTimeskipping) {
        env = TestWorkflowEnvironment.newInstance(
                TestEnvironmentOptions.newBuilder()
                        .setUseTimeskipping(useTimeskipping)
                        .build());

        Worker worker = env.newWorker(EpicWorkflow.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(EpicWorkflowImpl.class, StoryWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                new StubPlanningActivities("../cost-model.properties"), new StubBuildActivities());
        env.start();

        WorkflowClient client = env.getWorkflowClient();
        EpicWorkflow workflow = client.newWorkflowStub(
                EpicWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(EpicWorkflow.TASK_QUEUE)
                        .setWorkflowId(EPIC.id())
                        // Must outlast the human-answer timeout, or a skipped clock ends
                        // the execution before the workflow gets to give up on its own.
                        .setWorkflowExecutionTimeout(Duration.ofDays(30))
                        .build());

        WorkflowClient.start(workflow::run, EPIC);
        return workflow;
    }

    /**
     * Watches for tier 2 stories arriving at gate 3 and approves them, the way a person
     * would. Tier 1 stories never reach here — they auto-approve.
     */
    private Thread approveTierTwoPullRequestsAsTheyAppear() {
        WorkflowClient client = env.getWorkflowClient();
        List<String> tierTwo = List.of("LOOP-14-3", "LOOP-14-4");

        Thread reviewer = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                for (String storyId : tierTwo) {
                    try {
                        StoryWorkflow story = client.newWorkflowStub(
                                StoryWorkflow.class, StoryWorkflow.workflowId(EPIC.id(), storyId));
                        if (story.status().state() == StoryState.AWAITING_PR_REVIEW) {
                            story.decidePullRequest(
                                    new GateDecision(Gate.PULL_REQUEST, true, "reviewed by hand"));
                        }
                    } catch (RuntimeException notStartedYet) {
                        // The child workflow may not exist until its layer begins.
                    }
                }
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        reviewer.setDaemon(true);
        reviewer.start();
        return reviewer;
    }

    /** Polls the workflow's own query. There is no status table to read instead. */
    private EpicStatus awaitStatus(EpicWorkflow workflow, Predicate<EpicStatus> until) {
        for (int attempt = 0; attempt < 200; attempt++) {
            EpicStatus status = workflow.status();
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
        throw new AssertionError("workflow never reached the expected phase");
    }
}
