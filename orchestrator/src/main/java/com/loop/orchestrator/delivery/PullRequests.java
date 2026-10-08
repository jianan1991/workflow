package com.loop.orchestrator.delivery;

import com.loop.orchestrator.domain.Domain.CiResult;
import com.loop.orchestrator.domain.Domain.PullRequest;
import com.loop.orchestrator.domain.Domain.QaResult;
import com.loop.orchestrator.domain.Domain.ReviewResult;
import com.loop.orchestrator.domain.Domain.Story;

/**
 * Opens the pull request a person is asked to look at.
 *
 * <p>This is the hand-off the whole pipeline exists to produce, and until now it did not
 * exist: {@code StoryState.MERGED} was a label the workflow set on itself, with no branch
 * pushed, no pull request opened and nothing for a reviewer to click. A gate that asks
 * somebody to approve a change they cannot see is not a gate.
 *
 * <p>What goes in the body matters as much as the fact of opening it. The reviewer agent's
 * verdict, QA's evidence and the measured gates go in the description, so the person
 * deciding sees what the machine already concluded and can disagree with something
 * specific. A pull request that says only "automated change" trains people to rubber-stamp.
 */
public interface PullRequests {

    /**
     * @return the pull request, or one marked {@link PullRequest#opened()} false whose note
     *         says why not. Never throws for an ordinary failure: a story that cannot get a
     *         pull request opened still has to reach a person as blocked rather than take
     *         its epic down.
     */
    PullRequest open(Story story, String branch, Diff diff,
                     CiResult ci, ReviewResult review, QaResult qa);

    String describe();
}
