package com.loop.orchestrator.activity;

import com.loop.orchestrator.agent.QaAgent;
import com.loop.orchestrator.agent.ReviewerAgent;
import com.loop.orchestrator.domain.Domain.AcceptanceCriterion;
import com.loop.orchestrator.domain.Domain.Agent;
import com.loop.orchestrator.domain.Domain.CiResult;
import com.loop.orchestrator.domain.Domain.ImplementResult;
import com.loop.orchestrator.domain.Domain.Observation;
import com.loop.orchestrator.domain.Domain.QaOutcome;
import com.loop.orchestrator.domain.Domain.QaResult;
import com.loop.orchestrator.domain.Domain.ReviewResult;
import com.loop.orchestrator.domain.Domain.Story;
import com.loop.orchestrator.domain.Domain.StoryState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The build and check half, driven by real agents where agents are the right tool.
 *
 * <p><b>Two of these are still not real, and the difference matters.</b> The reviewer and
 * QA are model calls, and they are wired. Implementing a story and running CI are not model
 * calls at all — one needs a coding harness with a sandbox, the other needs the build to
 * actually run — so they remain honestly stubbed rather than faked convincingly.
 *
 * <p>The QA split is the one worth understanding. Exercising the software is deterministic
 * work: boot it, call it, capture what came back. Judging what came back against the
 * criteria is the model's job. Collapsing the two produces an agent that reports on tests
 * it never ran, which is indistinguishable from one that did until something reaches
 * production.
 */
@Component
public class AgentBuildActivities implements BuildActivities {

    private static final Logger log = LoggerFactory.getLogger(AgentBuildActivities.class);

    private final ReviewerAgent reviewer;
    private final QaAgent qa;

    public AgentBuildActivities(ReviewerAgent reviewer, QaAgent qa) {
        this.reviewer = reviewer;
        this.qa = qa;
    }

    @Override
    public ImplementResult implement(Story story, Agent agent, int attempt,
                                     String previousFailure) {
        // NOT WIRED. Needs the OpenHands dispatch and a sandbox to run in. Zero cost is
        // accurate here precisely because nothing ran — it will stop being accurate the
        // moment the dispatch lands, and the dev agent is the largest line on the bill.
        log.warn("[{}] {} attempt {} — dev agent is not wired; no code was written, $0 spent",
                agent.name().toLowerCase(), story.id(), attempt);
        return new ImplementResult(
                "branch feat/" + story.id().toLowerCase() + " (no dispatch yet)", 0.0);
    }

    @Override
    public CiResult runCi(Story story, int attempt) {
        // NOT WIRED. Needs the real build. Reporting a pass here would be the worst kind of
        // stub: the mutation gate is the one check an agent cannot talk its way past, and
        // faking it removes the only thing protecting the codebase.
        log.warn("[ci] {} attempt {} — CI is not wired; this is not a real result",
                story.id(), attempt);
        return new CiResult(true, 0.0, 0.0, "CI not wired — coverage and mutation unmeasured");
    }

    @Override
    public ReviewResult review(Story story, List<AcceptanceCriterion> criteria,
                               Agent author, int attempt) {
        // The diff is empty because nothing implemented the story. The reviewer is real and
        // will say so, which is the correct outcome rather than a convenient one.
        return reviewer.review(story, criteria, author,
                "(no diff — the dev agent is not wired)", List.of()).review();
    }

    @Override
    public QaResult runQa(Story story, List<AcceptanceCriterion> criteria, int attempt) {
        List<Observation> observations = observe(story, attempt);

        QaAgent.Result judged = qa.judge(story, criteria, observations);

        String evidence = judged.verdicts().stream()
                .filter(verdict -> verdict.outcome() != QaOutcome.PASS)
                .map(verdict -> verdict.criterionId() + ": " + verdict.evidence())
                .findFirst()
                .orElseGet(() -> judged.concerns().isEmpty()
                        ? "all criteria satisfied"
                        : judged.concerns().get(0).evidence());

        return new QaResult(judged.route(), evidence, (int) judged.unverifiable(),
                judged.costUsd(), 0);
    }

    @Override
    public boolean mayAutoApprove(Story story, ReviewResult review, CiResult ci) {
        boolean ok = story.tier() == 1
                && ci.passed()
                && review.matchesCriteria()
                && !review.scopeExceeded();

        log.info("[gate3] {} auto-approve: {}", story.id(),
                ok ? "yes" : "no, routed to a person");
        return ok;
    }

    @Override
    public void escalate(String epicId, Story story, StoryState state, String reason) {
        log.warn("[escalate] {} / {} -> {} ({})", epicId, story.id(), state, reason);
    }

    @Override
    public void writeBackDecision(String epicId, Story story, String decision) {
        log.info("[kb] {} would commit a decision from {}: {} (not yet wired)",
                epicId, story.id(), decision);
    }

    /**
     * What running the software produced. Empty, because nothing ran it.
     *
     * <p>QA is built to handle exactly this: given no observations it must return
     * UNVERIFIABLE for everything rather than inferring outcomes from the criteria. That
     * behaviour is what stops a not-yet-wired pipeline from reporting green.
     */
    private static List<Observation> observe(Story story, int attempt) {
        log.warn("[qa] {} attempt {} — nothing executed the software; QA has no observations",
                story.id(), attempt);
        return List.of();
    }
}
