package com.loop.orchestrator.activity;

import com.loop.orchestrator.domain.Domain.Agent;
import com.loop.orchestrator.domain.Domain.CiResult;
import com.loop.orchestrator.domain.Domain.QaResult;
import com.loop.orchestrator.domain.Domain.ReviewResult;
import com.loop.orchestrator.domain.Domain.Story;
import com.loop.orchestrator.domain.Domain.StoryState;
import io.temporal.activity.ActivityInterface;

/**
 * The build and check half of the pipeline. Same determinism rule as
 * {@link PlanningActivities} — nothing here may be called from workflow code directly.
 */
@ActivityInterface
public interface BuildActivities {

    /**
     * Dispatch a story to a coding agent. Real implementation runs OpenHands headless
     * against the repository with the story, its acceptance criteria, and the symbols the
     * code index says are involved.
     *
     * @param attempt 1-based. The agent is told what failed last time, so attempt 2 is not
     *                a blind retry.
     */
    String implement(Story story, Agent agent, int attempt, String previousFailure);

    /**
     * Run the build. An agent cannot open a pull request until this passes.
     *
     * <p>Mutation score is the gate that matters. Coverage is the most gameable number you
     * can hand an agent — told to reach 90% it will, with tests that call every method and
     * assert almost nothing. A test with no assertions kills no mutants.
     */
    CiResult runCi(Story story, int attempt);

    /** Reviewer agent. Runs on a different model family from whichever agent authored. */
    ReviewResult review(Story story, Agent author, int attempt);

    /**
     * QA agent. Boots the app against a throwaway database, calls the real endpoints, and
     * asserts against the PM journey and the BA's acceptance criteria — never against the
     * dev agent's own tests. If the agent writes the tests and QA confirms they pass,
     * nothing has been measured.
     */
    QaResult runQa(Story story, int attempt);

    /**
     * Decide whether this story may merge without a person.
     *
     * <p>Tier 1 only, and only when nothing touches shared code, configuration, security
     * or a public interface. Every auto-approval is logged so the hit rate can be audited
     * and the rule tightened before it costs anything.
     */
    boolean mayAutoApprove(Story story, ReviewResult review, CiResult ci);

    /** Send a story that needs a person to the right queue, with why. */
    void escalate(String epicId, Story story, StoryState state, String reason);

    /** Record the durable decision this story produced, if it produced one. */
    void writeBackDecision(String epicId, Story story, String decision);
}
