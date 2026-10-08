package com.loop.orchestrator.activity;

import com.loop.orchestrator.domain.Domain.AcceptanceCriterion;
import com.loop.orchestrator.domain.Domain.Agent;
import com.loop.orchestrator.domain.Domain.CiResult;
import com.loop.orchestrator.domain.Domain.ImplementResult;
import com.loop.orchestrator.domain.Domain.QaOutcome;
import com.loop.orchestrator.domain.Domain.QaResult;
import com.loop.orchestrator.domain.Domain.ReviewResult;
import com.loop.orchestrator.domain.Domain.Story;
import com.loop.orchestrator.domain.Domain.StoryState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Stub build agents. Nothing is compiled, no container is started, no model is called.
 *
 * <p>Not a Spring bean — see StubPlanningActivities for why.
 *
 * <p>The failure pattern is deliberate rather than random: it exercises each of the three
 * QA routes at least once across a six-story epic, so the orchestration can be proved
 * before any of it is real.
 *
 * <ul>
 *   <li>tier 2 fails QA on the first attempt with a code defect — the rework loop
 *   <li>one story returns a wrong criterion — the route back to the PM
 *   <li>tier 2 review flags scope, which blocks auto-approval
 * </ul>
 */
public class StubBuildActivities implements BuildActivities {

    private static final Logger log = LoggerFactory.getLogger(StubBuildActivities.class);

    /**
     * Opt-in marker for a story whose acceptance criteria are wrong rather than whose code
     * is wrong. Explicit rather than incidental, so a plain demo epic merges and only a
     * test that wants the route back to the PM gets it.
     */
    public static final String CRITERION_GAP_MARKER = "[criteria-gap]";

    /**
     * The harness itself cannot run — no workspace, no executable. Not something a
     * stronger model fixes, so it must reach a person directly rather than burning four
     * escalation rounds to find that out.
     */
    public static final String DEV_BROKEN_MARKER = "[dev-broken]";

    /**
     * The dev agent ran and failed once. An ordinary failure: it belongs in the rework
     * loop, and the next attempt is told what went wrong.
     */
    public static final String DEV_FAILS_ONCE_MARKER = "[dev-fails-once]";

    /**
     * CI is not wired for this story, which is the truth about the running system today.
     * It is not a failure and not a pass — it is an absence of information.
     */
    public static final String CI_UNMEASURED_MARKER = "[ci-unmeasured]";

    @Override
    public ImplementResult implement(Story story, List<AcceptanceCriterion> criteria,
                                     Agent agent, int attempt, String previousFailure) {
        log.info("[{}] {} attempt {}{}", agent.name().toLowerCase(), story.id(), attempt,
                previousFailure == null ? "" : " — fixing: " + previousFailure);
        if (story.title().startsWith(DEV_BROKEN_MARKER)) {
            return ImplementResult.broken("the workspace directory does not exist");
        }
        if (story.title().startsWith(DEV_FAILS_ONCE_MARKER) && attempt == 1) {
            // Costed. A failed attempt still spent tokens, and a rework loop reporting
            // zero would make the cost measurement a fiction.
            return ImplementResult.failed("the harness exited 1 before writing anything", 0.07);
        }

        // A plausible figure, because a stub reporting zero would make the cost arithmetic
        // in the workflow untestable.
        double cost = agent == Agent.SENIOR ? 1.85 : 0.42;
        return ImplementResult.built("branch feat/" + story.id().toLowerCase() + " · 4 files", cost);
    }

    @Override
    public CiResult runCi(Story story, int attempt) {
        // Mutation score is the real gate. A first-attempt tier 2 story lands just under
        // it — the tests ran and asserted too little, which is exactly what a coverage-only
        // gate would have waved through.
        if (story.title().startsWith(CI_UNMEASURED_MARKER)) {
            return CiResult.notRun("CI not wired - coverage and mutation unmeasured");
        }

        boolean thin = story.tier() == 2 && attempt == 1;

        // Measured either way - this stub stands in for a build that ran, which is what
        // makes the auto-approval path testable at all.
        CiResult result = thin
                ? new CiResult(true, false, 0.93, 0.61, "mutation score 61% is below the 70% gate")
                : new CiResult(true, true, 0.92, 0.74, "diff coverage 92%, mutation 74%");

        log.info("[ci] {} attempt {} -> {}", story.id(), attempt, result.note());
        return result;
    }

    @Override
    public ReviewResult review(Story story, List<AcceptanceCriterion> criteria,
                               Agent author, int attempt) {
        boolean scopeExceeded = story.tier() == 2;

        ReviewResult result = scopeExceeded
                ? new ReviewResult(true, true,
                        "correct, but edits application.yml — outside the ticket, so no auto-approval",
                        0.11)
                : new ReviewResult(true, false, "matches the acceptance criteria", 0.11);

        log.info("[reviewer] {} (author {}) -> {}", story.id(), author, result.verdict());
        return result;
    }

    @Override
    public QaResult runQa(Story story, List<AcceptanceCriterion> criteria, int attempt) {
        if (story.title().startsWith(CRITERION_GAP_MARKER)) {
            // The code is not wrong. Nobody wrote the criterion that would have caught
            // this, so sending it back to a developer produces a second wrong answer.
            return logged(story, attempt, new QaResult(
                    QaOutcome.CRITERION_WRONG,
                    "fails open when Redis is unavailable; no criterion covers it",
                    0, 0.38, 278));
        }

        if (story.tier() == 2 && attempt == 1) {
            return logged(story, attempt, new QaResult(
                    QaOutcome.CODE_DEFECT,
                    "429 returned with an empty body; unit tests asserted only the status code",
                    0, 0.38, 278));
        }

        return logged(story, attempt, new QaResult(
                QaOutcome.PASS,
                "429 on request 101, Retry-After present; two keys share one bucket",
                story.tier() == 2 ? 3 : 1, 0.36, 269));
    }

    @Override
    public boolean mayAutoApprove(Story story, ReviewResult review, CiResult ci) {
        boolean ok = story.tier() == 1
                && ci.trustworthy()
                && ci.passed()
                && review.matchesCriteria()
                && !review.scopeExceeded();

        log.info("[gate3] {} auto-approve: {}", story.id(), ok ? "yes" : "no, routed to a person");
        return ok;
    }

    @Override
    public void escalate(String epicId, Story story, StoryState state, String reason) {
        log.warn("[escalate] {} / {} -> {} ({})", epicId, story.id(), state, reason);
    }

    @Override
    public void writeBackDecision(String epicId, Story story, String decision) {
        // Filter first. Nineteen merges a week will bury the knowledge base in status noise
        // otherwise. The test: would a future agent need this, and could it derive the same
        // thing by reading the code?
        log.info("[kb] {} would commit a decision from {}: {}", epicId, story.id(), decision);
    }

    private QaResult logged(Story story, int attempt, QaResult result) {
        log.info("[qa] {} run {} -> {} ({})",
                story.id(), attempt, result.outcome(), result.evidence());
        return result;
    }
}
