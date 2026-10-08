package com.loop.orchestrator.delivery;

import com.loop.orchestrator.domain.Domain.CiResult;
import com.loop.orchestrator.domain.Domain.PullRequest;
import com.loop.orchestrator.domain.Domain.QaResult;
import com.loop.orchestrator.domain.Domain.ReviewResult;
import com.loop.orchestrator.domain.Domain.Story;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens nothing, and logs the description it would have written.
 *
 * <p>The default. Opening a pull request is the first thing this pipeline does that other
 * people see, so it stays off until someone configures a repository and a token
 * deliberately.
 *
 * <p>The body is still assembled and logged, which is the point: the wording a reviewer
 * will read can be judged now, before any of it is public.
 */
public class ScriptedPullRequests implements PullRequests {

    private static final Logger log = LoggerFactory.getLogger(ScriptedPullRequests.class);

    @Override
    public PullRequest open(Story story, String branch, Diff diff,
                            CiResult ci, ReviewResult review, QaResult qa) {

        log.info("[pr] pr.provider=scripted — would open \"{}\" from {} with this body:\n{}",
                PullRequestBody.title(story), branch,
                PullRequestBody.of(story, diff, ci, review, qa));

        return PullRequest.notOpened("pr.provider=scripted — no pull request was opened");
    }

    @Override
    public String describe() {
        return "scripted pull requests — opens nothing";
    }
}
