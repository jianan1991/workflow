package com.loop.orchestrator.workflow;

import com.loop.orchestrator.activity.BuildActivities;
import com.loop.orchestrator.domain.Domain.AcceptanceCriterion;
import com.loop.orchestrator.domain.Domain.Agent;
import com.loop.orchestrator.domain.Domain.CiResult;
import com.loop.orchestrator.domain.Domain.Gate;
import com.loop.orchestrator.domain.Domain.GateDecision;
import com.loop.orchestrator.domain.Domain.ImplementResult;
import com.loop.orchestrator.domain.Domain.QaResult;
import com.loop.orchestrator.domain.Domain.ReviewResult;
import com.loop.orchestrator.domain.Domain.Story;
import com.loop.orchestrator.domain.Domain.StoryState;
import com.loop.orchestrator.domain.Domain.StoryStatus;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;

/**
 * The rework loop.
 *
 * <p>Every pass through {@code implement → CI → review → QA → implement} that does not
 * touch a person is the entire value of this system. A person only appears when the loop
 * cannot resolve it: the story is reassigned to a stronger agent first, and only a failure
 * by that agent reaches a human as a blocked ticket.
 *
 * <p>Three QA outcomes route three different ways, and conflating them is the expensive
 * mistake: a code defect goes back to the agent, a wrong criterion goes back to the PM
 * (sending it to a developer produces a second wrong implementation), and something QA
 * cannot verify goes to a person's checklist without failing the story.
 */
public class StoryWorkflowImpl implements StoryWorkflow {

    private static final Logger log = Workflow.getLogger(StoryWorkflowImpl.class);

    /** Two failed rounds under MID reassigns the story to SENIOR. */
    private static final int FAILURES_BEFORE_ESCALATION = 2;

    /**
     * How many rounds SENIOR gets before a person is involved.
     *
     * <p>Deliberately the total budget a tier 1 story gets — two rounds under MID plus two
     * after escalation — because a tier 2 story starts at SENIOR and would otherwise be
     * given half as many chances as the easier work. Two failures of different kinds (a
     * thin mutation score, then a defect QA found) is an ordinary story, not a stuck one.
     */
    private static final int SENIOR_FAILURES_BEFORE_HUMAN = FAILURES_BEFORE_ESCALATION * 2;

    /** Hard stop. An agent still failing after this is not going to get there. */
    private static final int MAX_ATTEMPTS = 6;

    private static final Duration PR_REVIEW_TIMEOUT = Duration.ofDays(5);

    private final BuildActivities build = Workflow.newActivityStub(
            BuildActivities.class,
            ActivityOptions.newBuilder()
                    // QA boots a Spring app against real containers. Minutes, not seconds.
                    .setStartToCloseTimeout(Duration.ofMinutes(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                    .build());

    private String epicId = "";
    private Story story;
    private List<AcceptanceCriterion> criteria = List.of();
    private Agent agent = Agent.MID;
    private StoryState state = StoryState.QUEUED;
    private int devAttempts;
    private int qaRuns;
    private int reworkLoops;
    private int failuresUnderCurrentAgent;
    private boolean autoApproved;
    private double costUsd;
    private String note = "";

    private GateDecision prDecision;

    @Override
    public StoryStatus run(String epicId, Story story, List<AcceptanceCriterion> criteria) {
        this.epicId = epicId;
        this.story = story;
        this.criteria = criteria == null ? List.of() : criteria;

        if (this.criteria.isEmpty()) {
            // Nothing downstream can judge this story. The reviewer would be asked whether
            // a diff satisfies requirements nobody wrote, and QA would refuse outright.
            return block("no acceptance criteria - the BA did not settle this story, so "
                    + "there is nothing for the reviewer or QA to judge against");
        }
        // Tier 1 is mechanical and single-module. Tier 2 is cross-cutting, ambiguous, or
        // touching shared abstractions.
        this.agent = story.tier() == 1 ? Agent.MID : Agent.SENIOR;

        String previousFailure = null;

        while (devAttempts < MAX_ATTEMPTS) {
            devAttempts++;

            state = StoryState.IMPLEMENTING;
            // Every activity reports what it spent, and the workflow sums. A running total
            // read from somewhere central would not survive a replay.
            ImplementResult implemented = build.implement(story, criteria, agent, devAttempts,
                    previousFailure);
            costUsd += implemented.costUsd();

            if (!implemented.succeeded()) {
                if (!implemented.retryable()) {
                    // The harness itself is broken. No model fixes a missing workspace, and
                    // escalating through MID to SENIOR to discover that costs four rounds
                    // and buries the message a person actually needs to read.
                    return block("the dev agent could not run: " + implemented.failure());
                }
                previousFailure = implemented.failure();
                if (!absorbFailure(previousFailure)) {
                    return status();
                }
                continue;
            }

            state = StoryState.CI;
            CiResult ci = build.runCi(story, devAttempts);

            // Three outcomes, not two. A build that ran and failed is the agent's problem
            // and goes back round. A build that did not run is nobody's problem to fix by
            // writing more code - reworking it buys six identical attempts at full price
            // and ends in the same place. It carries on to review and QA, both of which
            // are real, and gate 3 refuses to auto-approve on an unmeasured result, so a
            // person decides instead.
            if (ci.measured() && !ci.passed()) {
                previousFailure = ci.note();
                if (!absorbFailure(previousFailure)) {
                    return status();
                }
                continue;
            }
            if (!ci.measured()) {
                log.warn("story {} has no CI verdict ({}) - a person decides this one",
                        story.id(), ci.note());
            }

            state = StoryState.REVIEW;
            ReviewResult review = build.review(story, criteria, agent, devAttempts);
            costUsd += review.costUsd();
            if (!review.matchesCriteria()) {
                previousFailure = review.verdict();
                if (!absorbFailure(previousFailure)) {
                    return status();
                }
                continue;
            }

            state = StoryState.QA;
            qaRuns++;
            QaResult qa = build.runQa(story, criteria, qaRuns);
            costUsd += qa.costUsd();

            switch (qa.outcome()) {
                case CODE_DEFECT -> {
                    // The loop that pays for the system. No person sees this.
                    reworkLoops++;
                    previousFailure = qa.evidence();
                    if (!absorbFailure(previousFailure)) {
                        return status();
                    }
                    continue;
                }
                case CRITERION_WRONG -> {
                    // Not a code problem. Retrying the agent here would be pure waste.
                    state = StoryState.RETURNED_TO_PM;
                    note = "criterion was wrong, not the code: " + qa.evidence();
                    build.escalate(epicId, story, state, note);
                    return status();
                }
                case UNVERIFIABLE -> {
                    // Does not fail the story. It becomes a checklist item, which was
                    // already classified and shown at the epic gate — no surprise.
                    build.escalate(epicId, story, StoryState.AWAITING_PR_REVIEW,
                            qa.unverifiableCount() + " criteria need a person's judgement");
                }
                case PASS -> {
                    if (qa.unverifiableCount() > 0) {
                        build.escalate(epicId, story, StoryState.AWAITING_PR_REVIEW,
                                qa.unverifiableCount() + " criteria need a person's judgement");
                    }
                }
            }

            if (build.mayAutoApprove(story, review, ci)) {
                autoApproved = true;
                return merge("auto-approved: tier 1, all checks clear");
            }

            state = StoryState.AWAITING_PR_REVIEW;
            note = "gate 3 — " + review.verdict();

            boolean decided = Workflow.await(PR_REVIEW_TIMEOUT, () -> prDecision != null);
            if (!decided) {
                return block("nobody reviewed the pull request within "
                        + PR_REVIEW_TIMEOUT.toDays() + " days");
            }
            if (prDecision.approved()) {
                return merge("approved by a person");
            }

            previousFailure = prDecision.note();
            prDecision = null;
            if (!absorbFailure(previousFailure)) {
                return status();
            }
        }

        return block("gave up after " + MAX_ATTEMPTS + " attempts");
    }

    /**
     * Decide what a failure costs: another turn for the same agent, a reassignment to a
     * stronger one, or a person.
     *
     * @return false when the story has stopped and the caller should return
     */
    private boolean absorbFailure(String reason) {
        failuresUnderCurrentAgent++;
        log.info("story {} failure {} under {}: {}",
                story.id(), failuresUnderCurrentAgent, agent, reason);

        if (agent == Agent.MID) {
            if (failuresUnderCurrentAgent < FAILURES_BEFORE_ESCALATION) {
                return true;
            }
            // Reassign rather than escalate. A person is the last resort, not the second.
            agent = Agent.SENIOR;
            failuresUnderCurrentAgent = 0;
            note = "escalated to SENIOR after " + FAILURES_BEFORE_ESCALATION + " failed rounds";
            build.escalate(epicId, story, StoryState.IMPLEMENTING, note);
            return true;
        }

        if (failuresUnderCurrentAgent < SENIOR_FAILURES_BEFORE_HUMAN) {
            return true;
        }

        block("SENIOR agent failed " + failuresUnderCurrentAgent + " rounds: " + reason);
        return false;
    }

    private StoryStatus merge(String why) {
        state = StoryState.MERGED;
        note = why;
        build.writeBackDecision(epicId, story,
                "tier %d, %d rework loop(s), %s".formatted(story.tier(), reworkLoops, why));
        log.info("story {} merged — {} rework loop(s), ${}", story.id(), reworkLoops, costUsd);
        return status();
    }

    private StoryStatus block(String why) {
        state = StoryState.BLOCKED;
        note = why;
        build.escalate(epicId, story, state, why);
        return status();
    }

    @Override
    public void decidePullRequest(GateDecision decision) {
        if (decision.gate() == Gate.PULL_REQUEST) {
            prDecision = decision;
        }
    }

    @Override
    public StoryStatus status() {
        return new StoryStatus(
                story == null ? "" : story.id(),
                story == null ? 0 : story.tier(),
                agent, state, devAttempts, qaRuns, reworkLoops,
                autoApproved, round4(costUsd), note);
    }

    /**
     * Four places, not two. A cheap agent's call costs a fraction of a cent, and rounding
     * to currency makes real spend display as zero - which reads exactly like cost tracking
     * that was never wired. Sum first, round at the edge.
     */
    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
