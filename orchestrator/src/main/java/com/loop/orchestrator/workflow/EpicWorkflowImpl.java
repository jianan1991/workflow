package com.loop.orchestrator.workflow;

import com.loop.orchestrator.activity.PlanningActivities;
import com.loop.orchestrator.domain.Domain.AcceptanceCriterion;
import com.loop.orchestrator.domain.Domain.Agent;
import com.loop.orchestrator.domain.Domain.Answer;
import com.loop.orchestrator.domain.Domain.Epic;
import com.loop.orchestrator.domain.Domain.EpicStatus;
import com.loop.orchestrator.domain.Domain.Estimate;
import com.loop.orchestrator.domain.Domain.Gate;
import com.loop.orchestrator.domain.Domain.GateDecision;
import com.loop.orchestrator.domain.Domain.Journey;
import com.loop.orchestrator.domain.Domain.JourneyStage;
import com.loop.orchestrator.domain.Domain.Phase;
import com.loop.orchestrator.domain.Domain.Question;
import com.loop.orchestrator.domain.Domain.Story;
import com.loop.orchestrator.domain.Domain.StoryState;
import com.loop.orchestrator.domain.Domain.TestabilitySplit;
import com.loop.orchestrator.domain.Domain.StoryStatus;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One epic, from creation to a merged plan.
 *
 * <p><b>Everything in this class is replayed.</b> No clocks, no random numbers, no I/O, no
 * environment reads — all of that is behind {@link PlanningActivities}. Constants here are
 * literals rather than injected configuration for the same reason: a value that changes
 * between the original run and a replay changes history.
 *
 * <p>The part worth understanding is {@code Workflow.await}. When the PM agent finds a gap
 * the workflow stops on that line, the worker is free to do other work, and the process can
 * be killed and restarted. Days later a person answers, a signal arrives, and execution
 * continues from the same line with the same local variables. That is the reason Temporal
 * is here rather than a queue and a status column.
 */
public class EpicWorkflowImpl implements EpicWorkflow {

    private static final Logger log = Workflow.getLogger(EpicWorkflowImpl.class);

    /**
     * How long to hold an epic open waiting for a person. Days, not minutes.
     * If this fires with any regularity, nobody is watching the question queue.
     */
    private static final Duration HUMAN_ANSWER_TIMEOUT = Duration.ofDays(3);
    private static final Duration GATE_TIMEOUT = Duration.ofDays(5);

    /**
     * Stop asking after this many rounds. An agent that still cannot proceed after three
     * answers has a problem a fourth answer will not fix — escalate it to a person as a
     * blocked epic instead of grinding.
     */
    private static final int MAX_QUESTION_ROUNDS = 3;

    /** Recalibrate monthly from the last 20 stories. It drifts as the pipeline improves. */
    private static final double REWORK_MULTIPLIER = 1.45;

    private final PlanningActivities planning = Workflow.newActivityStub(
            PlanningActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(10))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(2))
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    // ------------------------------------------------------------- local state

    private final Map<String, Answer> answers = new LinkedHashMap<>();
    private final Map<Gate, GateDecision> gates = new EnumMap<>(Gate.class);

    private String epicId = "";
    private Phase phase = Phase.JOURNEY_MAPPING;
    private Journey journey;
    private Question openQuestion;
    private List<Story> stories = List.of();
    private final Map<String, List<AcceptanceCriterion>> criteria = new LinkedHashMap<>();
    private TestabilitySplit testability = new TestabilitySplit(0, 0, 0, 0);
    private final List<StoryStatus> storyStatuses = new ArrayList<>();
    private Estimate estimate;
    private int questionsAsked;

    /**
     * Question ids are generated here, in workflow state.
     *
     * <p>They used to come from a counter on the activity instance, which reset on every
     * worker restart. Since answers are keyed by id, a reused id meant an answer to an
     * earlier question already satisfied the await for a later one - the epic resumed on a
     * reply to a different question and nobody could tell.
     *
     * <p>Workflow state is safe for this: it is rebuilt deterministically from history on
     * replay, so the same epic always generates the same ids in the same order. Scoping the
     * id to the epic makes a collision between epics impossible too.
     */
    private int questionSeq;
    private String note = "";

    // ------------------------------------------------------------------- main

    @Override
    public EpicStatus run(Epic epic) {
        this.epicId = epic.id();
        log.info("epic {} started", epic.id());

        if (!mapJourneyUntilItHoldsTogether(epic)) {
            return status();
        }

        move(Phase.DECOMPOSING, "architect decomposing against the code index");
        stories = planning.decompose(epic, journey);

        move(Phase.DECOMPOSING, "BA writing acceptance criteria per story");
        if (!writeCriteria(epic)) {
            return status();
        }

        move(Phase.ESTIMATING, "forecasting cost and human minutes");
        estimate = planning.estimate(epic.id(), stories, REWORK_MULTIPLIER);

        if (!passGate(Gate.EPIC_PLAN, "gate 1 — plan, testability split and forecast")) {
            return status();
        }

        move(Phase.BUILDING, ("%d stories, estimated $%.2f and %d human minutes; %d of %d "
                + "criteria need a person whatever the agents do")
                .formatted(stories.size(), estimate.dollars(), estimate.humanMinutes(),
                        testability.total() - testability.automated(), testability.total()));
        buildStories();
        return status();
    }

    /**
     * Writes acceptance criteria for every story, and records the testability split.
     *
     * <p>The split is what a person sees at gate 1: how much of this epic lands on a human
     * whatever the agents do. Shown before any code is written, so the exposure is known
     * upfront rather than discovered at the end.
     *
     * @return false when a story could not be settled, which stops the epic
     */
    private boolean writeCriteria(Epic epic) {
        List<AcceptanceCriterion> all = new ArrayList<>();

        for (Story story : stories) {
            List<AcceptanceCriterion> written = planning.writeCriteria(epic, story, journey);
            if (written.isEmpty()) {
                // Building a story nobody can judge is worse than not building it. It would
                // pass review against nothing and reach a person looking finished.
                move(Phase.ABANDONED, "the BA could not settle " + story.id()
                        + " - it needs a person before anything is built");
                return false;
            }
            criteria.put(story.id(), written);
            all.addAll(written);
        }

        testability = TestabilitySplit.of(all);
        return true;
    }

    /**
     * Run the stories, respecting the dependency graph.
     *
     * <p>Two orderings are at work and they belong to different owners. The <b>dependency
     * graph</b> is the architect's, derived from the code index — the CSV writer cannot
     * exist before the service it writes from is extracted. It is a hard constraint. The
     * PM's <b>priority</b> only decides the sequence among stories that are technically
     * free to run in any order, which here is the order within a layer.
     *
     * <p>Stopping between layers is what handles a rejection cascade. If a story is sent
     * back, the ones that depend on its assumptions have not started yet, so nothing is
     * left running against a design that has changed.
     */
    private void buildStories() {
        for (List<Story> layer : dependencyLayers(stories)) {
            List<Promise<StoryStatus>> running = new ArrayList<>();

            for (Story story : layer) {
                StoryWorkflow child = Workflow.newChildWorkflowStub(
                        StoryWorkflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId(StoryWorkflow.workflowId(epicId, story.id()))
                                .build());
                // handle(), not a bare get() later. A child that fails outright — an
                // unusable model reply, or a 429 the activity retries ran out on — used
                // to throw out of Promise.allOf and fail this epic. Two things were lost
                // with it: the stories that had already merged in the same layer were
                // never recorded, because storyStatuses is filled on the line after the
                // get(); and the epic's own status query went on answering BUILDING for a
                // workflow that was dead, so nothing escalated to a person. A story whose
                // workflow died is a blocked story, which this epic already knows how to
                // handle.
                running.add(Async.function(child::run, epicId, story,
                                criteria.getOrDefault(story.id(), List.of()))
                        .handle((status, failure) ->
                                failure == null ? status : crashed(story, failure)));
            }

            Promise.allOf(running).get();
            running.forEach(p -> storyStatuses.add(p.get()));

            List<StoryStatus> stopped = storyStatuses.stream()
                    .filter(s -> s.state() == StoryState.BLOCKED
                            || s.state() == StoryState.RETURNED_TO_PM)
                    .toList();

            if (!stopped.isEmpty()) {
                move(Phase.ABANDONED, stopped.size() + " story/stories need a person: "
                        + stopped.stream().map(StoryStatus::storyId).toList()
                        + " — dependent stories were not started");
                return;
            }
        }

        int rework = storyStatuses.stream().mapToInt(StoryStatus::reworkLoops).sum();
        double actual = storyStatuses.stream().mapToDouble(StoryStatus::costUsd).sum();

        move(Phase.MERGED, "%d stories merged, %d rework loop(s) with no human in them, $%.2f against a $%.2f estimate"
                .formatted(storyStatuses.size(), rework, actual, estimate.dollars()));
    }

    /**
     * A story whose workflow died rather than reaching a verdict.
     *
     * <p>Reported as BLOCKED, which routes it to a person through the path that already
     * exists. The spend is reported as zero and said to be zero: the child's running total
     * died with it, and claiming a figure we do not have would be worse than admitting the
     * gap.
     */
    private static StoryStatus crashed(Story story, RuntimeException failure) {
        return new StoryStatus(
                story.id(), story.tier(),
                story.tier() == 1 ? Agent.MID : Agent.SENIOR,
                StoryState.BLOCKED, 0, 0, 0, false, 0.0,
                "the story workflow failed outright: " + rootMessage(failure)
                        + " — whatever it spent before it died is not in this figure");
    }

    /**
     * The deepest message in the chain. A ChildWorkflowFailure wrapping an
     * ActivityFailure wrapping the real cause reads as nothing at the top level.
     */
    private static String rootMessage(Throwable failure) {
        Throwable deepest = failure;
        while (deepest.getCause() != null) {
            deepest = deepest.getCause();
        }
        String message = deepest.getMessage();
        return message == null || message.isBlank()
                ? deepest.getClass().getSimpleName()
                : message;
    }

    /**
     * Group stories into layers where everything in a layer can run at once.
     *
     * <p>Pure computation on data the workflow already holds, so it is safe to do here
     * rather than in an activity. A story whose dependency never resolves — a cycle, or a
     * reference to something that was not decomposed — is placed in the final layer rather
     * than dropped, so it surfaces as a failure instead of vanishing.
     */
    public static List<List<Story>> dependencyLayers(List<Story> stories) {
        List<List<Story>> layers = new ArrayList<>();
        List<Story> remaining = new ArrayList<>(stories);
        Set<String> placed = new HashSet<>();

        while (!remaining.isEmpty()) {
            List<Story> layer = remaining.stream()
                    .filter(s -> placed.containsAll(s.dependsOn()))
                    .toList();

            if (layer.isEmpty()) {
                // Unsatisfiable dependencies. Run what is left together and let the
                // stories themselves fail loudly.
                layers.add(List.copyOf(remaining));
                return layers;
            }

            layers.add(layer);
            layer.forEach(s -> placed.add(s.id()));
            remaining.removeAll(layer);
        }
        return layers;
    }

    // -------------------------------------------------------------- the loops

    /**
     * The PM agent blocks decomposition until the journey holds together. Each gap becomes
     * a question, and the workflow sleeps on the answer.
     *
     * @return false if the epic was abandoned waiting for a person
     */
    private boolean mapJourneyUntilItHoldsTogether(Epic epic) {
        move(Phase.JOURNEY_MAPPING, "PM agent mapping the customer journey");
        journey = planning.mapJourney(epic, List.copyOf(answers.values()));

        int round = 0;
        while (!journey.holdsTogether()) {
            if (round >= MAX_QUESTION_ROUNDS) {
                move(Phase.ABANDONED, "still has gaps after " + MAX_QUESTION_ROUNDS
                        + " rounds of questions — needs a person, not another round");
                return false;
            }

            JourneyStage gap = journey.gaps().getFirst();
            String questionId = "%s-Q%d".formatted(epic.id(), ++questionSeq);
            Question question = planning.raiseQuestion(questionId, epic, "pm", gap);
            openQuestion = question;
            move(Phase.AWAITING_ANSWER, "blocked on " + question.id()
                    + " — stage " + gap.index() + " (" + gap.name() + ")");

            // The workflow stops here. Not a thread parked on a latch — the worker is
            // free, the process can be restarted, and this resumes from the same line.
            boolean answered = Workflow.await(
                    HUMAN_ANSWER_TIMEOUT, () -> answers.containsKey(question.id()));

            if (!answered) {
                move(Phase.ABANDONED, "no answer to " + question.id()
                        + " after " + HUMAN_ANSWER_TIMEOUT.toDays() + " days");
                return false;
            }

            planning.recordAnswer(epic.id(), question, answers.get(question.id()));
            openQuestion = null;
            questionsAsked++;
            round++;

            move(Phase.JOURNEY_MAPPING, "re-mapping with " + questionsAsked + " answer(s)");
            journey = planning.mapJourney(epic, List.copyOf(answers.values()));
        }

        log.info("epic {} journey holds together after {} question(s)", epic.id(), questionsAsked);
        return true;
    }

    /**
     * Wait for a person at one of the three gates.
     *
     * @return false if the gate was failed or nobody turned up
     */
    private boolean passGate(Gate gate, String description) {
        move(Phase.EPIC_REVIEW, description);

        boolean decided = Workflow.await(GATE_TIMEOUT, () -> gates.containsKey(gate));
        if (!decided) {
            move(Phase.ABANDONED, "nobody decided " + gate + " within "
                    + GATE_TIMEOUT.toDays() + " days");
            return false;
        }

        GateDecision decision = gates.get(gate);
        if (!decision.approved()) {
            move(Phase.ABANDONED, gate + " rejected: " + decision.note());
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- signals

    @Override
    public void answerQuestion(Answer answer) {
        // Signals can arrive at any time, including for a question already answered or
        // one that never existed. Record it and let the await predicate decide.
        answers.put(answer.questionId(), answer);
        log.info("epic {} received an answer to {}", epicId, answer.questionId());
    }

    @Override
    public void decideGate(GateDecision decision) {
        gates.put(decision.gate(), decision);
        log.info("epic {} gate {} -> {}", epicId, decision.gate(),
                decision.approved() ? "approved" : "rejected");
    }

    // ---------------------------------------------------------------- queries

    @Override
    public EpicStatus status() {
        return new EpicStatus(
                epicId, phase, journey, openQuestion,
                new ArrayList<>(stories), new ArrayList<>(storyStatuses),
                estimate, testability, questionsAsked, note);
    }

    private void move(Phase next, String why) {
        phase = next;
        note = why;
        planning.publishStatus(epicId, next, why);
    }
}
