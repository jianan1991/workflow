package com.loop.orchestrator.activity;

import com.loop.orchestrator.agent.DevAgent;
import com.loop.orchestrator.delivery.BuildRunner;
import com.loop.orchestrator.delivery.Diff;
import com.loop.orchestrator.delivery.PullRequests;
import com.loop.orchestrator.delivery.SourceControl;
import com.loop.orchestrator.agent.QaAgent;
import com.loop.orchestrator.agent.ReviewerAgent;
import com.loop.orchestrator.domain.Domain.AcceptanceCriterion;
import com.loop.orchestrator.domain.Domain.Agent;
import com.loop.orchestrator.domain.Domain.CiResult;
import com.loop.orchestrator.domain.Domain.CriterionVerdict;
import com.loop.orchestrator.domain.Domain.ImplementResult;
import com.loop.orchestrator.domain.Domain.Observation;
import com.loop.orchestrator.domain.Domain.PullRequest;
import com.loop.orchestrator.domain.Domain.QaOutcome;
import com.loop.orchestrator.domain.Domain.QaResult;
import com.loop.orchestrator.domain.Domain.ReviewResult;
import com.loop.orchestrator.domain.Domain.Story;
import com.loop.orchestrator.domain.Domain.StoryState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Stream;

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
    private final DevAgent dev;
    private final SourceControl scm;
    private final BuildRunner ci;
    private final PullRequests pullRequests;

    public AgentBuildActivities(ReviewerAgent reviewer, QaAgent qa, DevAgent dev,
                                SourceControl scm, BuildRunner ci, PullRequests pullRequests) {
        this.reviewer = reviewer;
        this.qa = qa;
        this.dev = dev;
        this.scm = scm;
        this.ci = ci;
        this.pullRequests = pullRequests;
    }

    @Override
    public ImplementResult implement(Story story, List<AcceptanceCriterion> criteria,
                                     Agent agent, int attempt, String previousFailure) {

        DevAgent.Result result = dev.implement(story, criteria, agent, attempt, previousFailure);

        if (result.succeeded()) {
            return ImplementResult.built(result.branch(), result.costUsd());
        }

        // A failure is returned, not thrown. Throwing propagated past the workflow's
        // escalation ladder entirely - MID never got promoted to SENIOR, the story never
        // reached a person as blocked, and the epic's Promise.allOf took its siblings down
        // with it. A business outcome belongs in the return value.
        log.warn("[{}] {} attempt {} failed: {}",
                agent.name().toLowerCase(), story.id(), attempt, result.failure());

        // Nothing a stronger model fixes. A missing workspace or an uninstalled executable
        // needs a person, and spending four escalation rounds to discover that wastes money
        // and buries the real message.
        boolean harnessBroken = result.failure() != null
                && (result.failure().contains("could not start")
                || result.failure().contains("does not exist"));

        return harnessBroken
                ? ImplementResult.broken(result.failure())
                : ImplementResult.failed(result.failure(), result.costUsd());
    }

    /** What is actually wired, for the startup log and the command centre. */
    public String describeDelivery() {
        return "dev: %s | scm: %s | ci: %s | pr: %s".formatted(
                dev.describe(), scm.describe(), ci.describe(), pullRequests.describe());
    }

    @Override
    public CiResult runCi(Story story, String branch, int attempt) {
        // Scoped to the diff, which is why the branch had to be threaded through: the
        // numbers worth gating on are coverage of the lines this story added and mutation
        // of the classes it touched, not the repository's running totals.
        return ci.run(scm.diffFor(branch), branch, attempt);
    }

    @Override
    public ReviewResult review(Story story, List<AcceptanceCriterion> criteria,
                               Agent author, String branch, int attempt) {

        Diff diff = scm.diffFor(branch);

        if (diff.isEmpty()) {
            // Say it rather than send the reviewer an empty patch and record its opinion.
            // A verdict on nothing is not a verdict, and this used to be a hardcoded
            // string that stayed in place even when a dev agent was dispatching for real.
            log.warn("[reviewer] {} has no diff to review: {}", story.id(), diff.unified());
        }
        if (diff.truncated()) {
            log.warn("[reviewer] {} diff was truncated — the verdict covers part of the "
                    + "change only", story.id());
        }

        return reviewer.review(story, criteria, author, diff.unified(), List.of()).review();
    }

    @Override
    public PullRequest openPullRequest(Story story, String branch, CiResult ci,
                                       ReviewResult review, QaResult qa) {
        return pullRequests.open(story, branch, scm.diffFor(branch), ci, review, qa);
    }

    @Override
    public QaResult runQa(Story story, List<AcceptanceCriterion> criteria, int attempt) {
        List<Observation> observations = observe(story, attempt);

        QaAgent.Result judged = qa.judge(story, criteria, observations);

        QaOutcome route = judged.route();

        return new QaResult(route, evidenceFor(judged, route), (int) judged.unverifiable(),
                judged.costUsd(), 0);
    }

    @Override
    public boolean mayAutoApprove(Story story, ReviewResult review, CiResult ci) {
        // Before anything else: did CI give us anything to approve on? Auto-approval is
        // the one decision in this pipeline that happens with nobody watching, so it is
        // also the one that must not read silence as consent.
        if (!ci.trustworthy()) {
            log.warn("[gate3] {} auto-approve: no — {}", story.id(), ci.measured()
                    ? "mutation came back as zero, so the build reported a pass it cannot "
                            + "have earned"
                    : "CI measured nothing: " + ci.note());
            return false;
        }

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
     * The evidence belonging to the outcome that actually routed the story.
     *
     * <p>This used to take the first verdict that was not a PASS, in criterion order,
     * which has nothing to do with how the story was routed. {@code route()} has its own
     * precedence — a wrong criterion outranks a code defect — so with AC-1 unverifiable
     * and AC-2 defective, the story was correctly routed back to the dev agent and then
     * handed "AC-1: nothing was captured" as the thing to fix. The agent was told to
     * correct the criterion QA could not check rather than the defect it found, and a
     * story routed to the PM had the same problem: concerns were only consulted when every
     * verdict passed, so the PM read an unverifiable note instead of the real gap.
     */
    private static String evidenceFor(QaAgent.Result judged, QaOutcome route) {
        if (route == QaOutcome.PASS) {
            // Nothing failed, so quoting a passing verdict says nothing. What is worth
            // carrying is anything QA could not check: it is about to become a person's
            // checklist item, and "all criteria satisfied" would be untrue of it.
            return allVerdicts(judged)
                    .filter(verdict -> verdict.outcome() == QaOutcome.UNVERIFIABLE)
                    .map(AgentBuildActivities::describe)
                    .findFirst()
                    .orElse("all criteria satisfied");
        }

        return allVerdicts(judged)
                .filter(verdict -> verdict.outcome() == route)
                .map(AgentBuildActivities::describe)
                .findFirst()
                // route() derives the outcome from these verdicts, so there is always one
                // to find. Saying so beats a cheerful default if that stops being true.
                .orElse("QA routed this as " + route + " but reported no evidence for it");
    }

    private static Stream<CriterionVerdict> allVerdicts(QaAgent.Result judged) {
        return Stream.concat(judged.verdicts().stream(), judged.concerns().stream());
    }

    /** Concerns carry no criterion id, by definition: no criterion covers them. */
    private static String describe(CriterionVerdict verdict) {
        return verdict.criterionId() == null || verdict.criterionId().isBlank()
                ? verdict.evidence()
                : verdict.criterionId() + ": " + verdict.evidence();
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
