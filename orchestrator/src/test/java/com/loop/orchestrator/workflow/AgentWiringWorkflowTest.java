package com.loop.orchestrator.workflow;

import com.loop.orchestrator.activity.AgentBuildActivities;
import com.loop.orchestrator.activity.AgentPlanningActivities;
import com.loop.orchestrator.agent.ArchitectAgent;
import com.loop.orchestrator.agent.BaAgent;
import com.loop.orchestrator.agent.PmAgent;
import com.loop.orchestrator.agent.QaAgent;
import com.loop.orchestrator.agent.ReviewerAgent;
import com.loop.orchestrator.agent.ScriptedDevAgent;
import com.loop.orchestrator.config.LlmConfig;
import com.loop.orchestrator.cost.AgentCostModel;
import com.loop.orchestrator.delivery.ScriptedBuildRunner;
import com.loop.orchestrator.delivery.ScriptedPullRequests;
import com.loop.orchestrator.delivery.ScriptedSourceControl;
import com.loop.orchestrator.domain.Domain.Answer;
import com.loop.orchestrator.domain.Domain.Epic;
import com.loop.orchestrator.domain.Domain.EpicStatus;
import com.loop.orchestrator.domain.Domain.Gate;
import com.loop.orchestrator.domain.Domain.GateDecision;
import com.loop.orchestrator.domain.Domain.Phase;
import com.loop.orchestrator.domain.Domain.Story;
import com.loop.orchestrator.domain.Domain.StoryState;
import com.loop.orchestrator.knowledge.HashingEmbedder;
import com.loop.orchestrator.knowledge.InMemoryVectorStore;
import com.loop.orchestrator.knowledge.KnowledgeIndexer;
import com.loop.orchestrator.llm.ChatModel;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The epic running on the <b>real</b> activities, not the stubs.
 *
 * <p>Every other workflow test registers the stub activities, which is right for proving
 * the orchestration on its own. But three bugs in this project were found by starting the
 * application rather than by the suite, and all three lived in exactly this join: the
 * workflow was correct, the agent activities were correct in isolation, and what they
 * handed each other was wrong. Nothing here needs a container or an API key - the chat
 * model is the same scripted one the application runs with when no key is configured.
 *
 * <p>What it pins down is the shape of the system as it actually stands today: CI is not
 * wired, so no story may auto-approve, and no story may be sent round the rework loop over
 * it either.
 */
class AgentWiringWorkflowTest {

    private static final Epic EPIC = new Epic("LOOP-21", "Per-tenant API rate limiting",
            "Developers on the Starter plan can exhaust the API.");

    private TestWorkflowEnvironment env;

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    @Test
    @DisplayName("an epic on the real activities reaches a person for every story, and only once each")
    void realActivitiesRouteEveryStoryToAPerson() {
        EpicWorkflow workflow = start();
        WorkflowStub stub = WorkflowStub.fromTyped(workflow);

        // The PM agent finds the gap the scripted journey carries.
        EpicStatus blocked = awaitStatus(workflow, s -> s.phase() == Phase.AWAITING_ANSWER);
        assertThat(blocked.openQuestion().id())
                .as("the id is the workflow's, scoped to the epic")
                .isEqualTo(EPIC.id() + "-Q1");

        workflow.answerQuestion(new Answer(blocked.openQuestion().id(),
                "Support grants a higher ceiling within one business day."));

        EpicStatus atGate = awaitStatus(workflow, s -> s.phase() == Phase.EPIC_REVIEW);
        assertThat(atGate.stories()).isNotEmpty();
        assertThat(atGate.estimate().dollars())
                .as("the estimate prices the stories the architect actually produced")
                .isGreaterThan(0.0);

        workflow.decideGate(new GateDecision(Gate.EPIC_PLAN, true, "plan looks right"));

        Thread reviewer = approvePullRequestsAsTheyAppear(atGate.stories());
        EpicStatus finished;
        try {
            finished = stub.getResult(EpicStatus.class);
        } finally {
            reviewer.interrupt();
        }

        assertThat(finished.storyStatuses())
                .as("every story the architect produced should have a result")
                .hasSameSizeAs(atGate.stories());

        assertThat(finished.storyStatuses()).allSatisfy(story -> {
            assertThat(story.state())
                    .as("%s should have merged on a person's approval", story.storyId())
                    .isEqualTo(StoryState.MERGED);

            assertThat(story.autoApproved())
                    .as("CI is not wired, so nothing may merge unwatched - %s auto-approving "
                            + "would mean a story merged with no coverage and no mutation "
                            + "score measured at all", story.storyId())
                    .isFalse();

            assertThat(story.devAttempts())
                    .as("an unmeasured CI result is not a failure to fix. Treating it as one "
                            + "sent %s round the loop six times at full price and ended in "
                            + "the same place", story.storyId())
                    .isEqualTo(1);

            assertThat(story.pullRequestUrl())
                    .as("pr.provider=scripted opens nothing, and %s must not carry a link "
                            + "to something that does not exist", story.storyId())
                    .isEmpty();
        });
    }

    // ------------------------------------------------------------------ setup

    private EpicWorkflow start() {
        env = TestWorkflowEnvironment.newInstance(
                TestEnvironmentOptions.newBuilder()
                        // Off: the waits for a person are the thing being driven here, and
                        // a skipped clock fires their timeouts before the signal lands.
                        .setUseTimeskipping(false)
                        .build());

        ChatModel model = LlmConfig.scriptedModel();
        KnowledgeIndexer knowledge = emptyKnowledgeBase();

        Worker worker = env.newWorker(EpicWorkflow.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(EpicWorkflowImpl.class, StoryWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                new AgentPlanningActivities(
                        new PmAgent(model, knowledge, 4096),
                        new BaAgent(model, knowledge, 4096),
                        new ArchitectAgent(model, knowledge, 4096),
                        costModel()),
                new AgentBuildActivities(
                        new ReviewerAgent(model, 4096),
                        new QaAgent(model, 4096),
                        new ScriptedDevAgent(),
                        new ScriptedSourceControl(),
                        new ScriptedBuildRunner(),
                        new ScriptedPullRequests()));
        env.start();

        EpicWorkflow workflow = env.getWorkflowClient().newWorkflowStub(
                EpicWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(EpicWorkflow.TASK_QUEUE)
                        .setWorkflowId(EPIC.id())
                        .setWorkflowExecutionTimeout(java.time.Duration.ofDays(30))
                        .build());

        WorkflowClient.start(workflow::run, EPIC);
        return workflow;
    }

    /**
     * An indexed knowledge base with nothing in it.
     *
     * <p>Deliberate: the agents must say they found nothing rather than invent sources, and
     * a populated one would hide that.
     */
    private static KnowledgeIndexer emptyKnowledgeBase() {
        KnowledgeIndexer indexer = new KnowledgeIndexer(
                new HashingEmbedder(256), new InMemoryVectorStore(), 0.25, 3);
        try {
            indexer.indexDirectory(Files.createTempDirectory("loop-empty-kb"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return indexer;
    }

    private static AgentCostModel costModel() {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of("..", "cost-model.properties"))) {
            properties.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new AgentCostModel(properties);
    }

    /** Stands in for the person at gate 3, approving each story as it arrives. */
    private Thread approvePullRequestsAsTheyAppear(List<Story> stories) {
        WorkflowClient client = env.getWorkflowClient();

        Thread reviewer = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                for (Story story : stories) {
                    try {
                        StoryWorkflow handle = client.newWorkflowStub(
                                StoryWorkflow.class,
                                StoryWorkflow.workflowId(EPIC.id(), story.id()));
                        if (handle.status().state() == StoryState.AWAITING_PR_REVIEW) {
                            handle.decidePullRequest(new GateDecision(
                                    Gate.PULL_REQUEST, true, "read the diff by hand"));
                        }
                    } catch (RuntimeException notStartedYet) {
                        // The child workflow does not exist until its layer begins.
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

    private EpicStatus awaitStatus(EpicWorkflow workflow, Predicate<EpicStatus> until) {
        for (int attempt = 0; attempt < 400; attempt++) {
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
        throw new AssertionError("the epic never reached the expected phase");
    }
}
